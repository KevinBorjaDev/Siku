package com.qhana.siku.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.auth.AuthResult
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.remote.HttpStatus
import com.qhana.siku.data.remote.retryAfterSeconds
import com.qhana.siku.data.remote.OneDriveApi
import com.qhana.siku.data.coordinator.RequestCoordinator
import com.qhana.siku.data.util.AudioFileAnalyzer

@Singleton
class OneDriveRepository @Inject constructor(
    private val oneDriveApi: OneDriveApi,
    private val authManager: AuthManager,
    private val requestCoordinator: RequestCoordinator,
    private val audioFileAnalyzer: AudioFileAnalyzer,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "OneDriveRepo"

        /**
         * Reintento de UNA petición ante un fallo de transporte ([isTransient]): tres intentos con
         * la espera duplicándose, repartiendo un presupuesto de **3 segundos de ESPERA entre
         * intentos** (1 s + 2 s).
         *
         * **Cuidado con leer eso como el tiempo total antes de rendirse: no lo es.** Solo mide las
         * pausas del backoff; lo que tarda cada intento lo decide [AppConfig.API_READ_TIMEOUT_SECONDS].
         * Con fallos rápidos (un 5xx, una conexión rechazada) el conjunto se resuelve en esos ~3 s,
         * que es el caso común; con un socket COLGADO, cada intento se come el timeout entero y el
         * peor caso pasa de minuto y medio. Los dos números viven en sitios distintos y no se
         * conocen, así que conviene mirarlos juntos antes de tocar cualquiera de los dos.
         *
         * **El presupuesto de espera sale de la latencia percibida, no de la red.** Esta función
         * cubre también el camino PRIORITARIO —resolver la URL de la canción que el usuario acaba de
         * tocar—, así que al otro lado hay alguien mirando una pantalla que no suena. Pasados unos
         * segundos es mejor fallar y dejar que reprograme quien sabe hacerlo sin prisa (la cola
         * persistente de descargas con su `nextRetryAt`, el reintento del `ScanWorker`), que seguir
         * ocupando un permiso del `RequestCoordinator`.
         *
         * **Deliberadamente MÁS CORTO que la política oficial de Graph**, que para sus SDK especifica
         * base 3 s y multiplicador `n²` — con esos valores el primer intento fallido ya costaría 3 s
         * y el segundo 12, o sea quince segundos de play colgado. Esa política es la correcta para un
         * backend sin nadie delante, y es exactamente la que usa el freno por throttling del
         * `RequestCoordinator`, donde no hay usuario esperando. Aquí sí lo hay.
         *
         * El factor 2 es el backoff binario de toda la vida: duplicar es la convención, y lo que
         * habría que justificar es apartarse de ella.
         */
        private const val RETRY_WAIT_BUDGET_MS = 3_000L
        private const val RETRY_ATTEMPTS = 3
        private const val RETRY_BACKOFF_FACTOR = 2.0

        /**
         * Techo de lo que puede tardar en resolverse la URL de algo que se está intentando
         * REPRODUCIR, contando la cola, los tres intentos y sus esperas.
         *
         * Sin él, el peor caso lo componen números que no se conocen entre sí: hasta ~6 s de cola en
         * el rate limiter, más tres intentos que pueden agotar [AppConfig.API_READ_TIMEOUT_SECONDS]
         * cada uno, más el backoff. Eso pasa de minuto y medio con el reproductor en BUFFERING —y
         * como no hay error, `PlaybackErrorRecoveryUseCase` ni se entera: no puede reintentar ni
         * saltar de pista, porque desde su punto de vista no ha fallado nada. **Rendirse a tiempo es
         * lo que convierte una espera muda en algo que la app sabe manejar.**
         *
         * Diez segundos es el umbral clásico de atención en interfaces (Nielsen): por encima, quien
         * espera da la tarea por rota y actúa por su cuenta. Y encaja con lo que hay debajo — deja
         * sitio a un connect completo ([AppConfig.API_CONNECT_TIMEOUT_SECONDS]) más la lectura de un
         * JSON de unos pocos KB, así que solo corta cuando de verdad no iba a llegar.
         */
        private const val PRIORITY_URL_BUDGET_MS = 10_000L

        /**
         * Primera espera del backoff. **No se elige: se DERIVA** de [RETRY_WAIT_BUDGET_MS] repartido
         * entre los huecos que dejan [RETRY_ATTEMPTS] intentos creciendo a [RETRY_BACKOFF_FACTOR]
         * (con los valores actuales, `1 + 2` unidades → 1 s).
         *
         * Está así porque "¿cuánto debe valer el primer retardo de un backoff?" no es una pregunta
         * que se pueda responder: no hay nada que medir ni ninguna referencia externa que consultar,
         * y cualquier número que se escriba ahí es indefendible. La pregunta que SÍ se puede
         * responder —y discutir— es cuánto es tolerable esperar a que falle algo que el usuario está
         * esperando, y esa es el presupuesto. El retardo es su consecuencia aritmética.
         *
         * La propiedad que gana el código: si mañana alguien sube los intentos a cuatro, la espera
         * total sigue siendo la misma en vez de dispararse a 7 s sin que nadie lo note.
         */
        private val RETRY_INITIAL_DELAY_MS: Long = run {
            var units = 0.0
            var step = 1.0
            repeat(RETRY_ATTEMPTS - 1) {
                units += step
                step *= RETRY_BACKOFF_FACTOR
            }
            if (units <= 0.0) 0L else (RETRY_WAIT_BUDGET_MS / units).toLong()
        }
    }

    
    /**
     * Reintentos con backoff exponencial, SOLO para fallos transitorios ([isTransient]).
     *
     * Antes reintentaba cualquier excepción que no fuera 401/403/404, lo que hacía esperar
     * 3 segundos en vano ante errores que nunca iban a cambiar de respuesta (un 400, un fallo
     * de parseo) y, peor, trataba la CANCELACIÓN como un fallo más: se la tragaba, la anotaba
     * como "reintento fallido" y seguía el bucle. Cancelar el sync (logout, red medida, worker
     * reemplazado) debe cortar aquí y ahora — es lo que hace el resto de la app.
     */
    private suspend fun <T> executeWithRetry(
        times: Int = RETRY_ATTEMPTS,
        initialDelay: Long = RETRY_INITIAL_DELAY_MS,
        factor: Double = RETRY_BACKOFF_FACTOR,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        var lastException: Exception? = null

        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isTransient(e)) throw e
                lastException = e

                android.util.Log.w(TAG, "Reintento fallido (${attempt + 1}/$times). Error: ${e.message}")

                if (attempt < times - 1) {
                    kotlinx.coroutines.delay(currentDelay)
                    currentDelay = (currentDelay * factor).toLong()
                }
            }
        }

        android.util.Log.e(TAG, "Todos los $times intentos fallaron")
        throw lastException ?: IllegalStateException("Retry failed without exception")
    }

    /**
     * ¿Vale la pena repetir esta petición tal cual? Solo si el fallo es de transporte o del
     * servidor. Un 429 NO entra: tiene su propio mecanismo (cabecera `Retry-After` →
     * `RequestCoordinator.notifyThrottled`), y machacarlo con reintentos rápidos solo alarga
     * el castigo de OneDrive.
     */
    private fun isTransient(e: Exception): Boolean = when (e) {
        // Una señal de throttle NUNCA se repite en caliente, aunque el código sea 5xx: ese caso lo
        // gobierna el freno global del coordinador esperando lo que dijo el servidor. Volver a los
        // 1-2 s de este backoff sería ignorar su `Retry-After`, que es justo el patrón por el que
        // Microsoft documenta que se bloquea a una app.
        is retrofit2.HttpException ->
            !HttpStatus.isThrottleSignal(e.code(), e.retryAfterSeconds() != null) &&
                HttpStatus.isRetriableTransport(e.code())
        is java.io.IOException -> true
        else -> false
    }

    private suspend fun fetchDownloadUrl(remoteId: String, usePriorityPermit: Boolean): String? {
        // El presupuesto envuelve TODO, incluida la espera del permiso: desde el punto de vista de
        // quien pulsó play da igual si el tiempo se fue en la cola del rate limiter, en el
        // handshake o esperando la respuesta. `withTimeoutOrNull` y no `withTimeout` porque esto ya
        // devuelve `String?` y "no se pudo" es un valor legítimo aquí — lanzar convertiría un fallo
        // esperable en una cancelación que hay que ir cazando arriba.
        if (usePriorityPermit) {
            return withTimeoutOrNull(PRIORITY_URL_BUDGET_MS) {
                fetchDownloadUrlInner(remoteId, usePriorityPermit = true)
            } ?: run {
                android.util.Log.w(
                    TAG,
                    "Resolución prioritaria de $remoteId agotó su presupuesto de ${PRIORITY_URL_BUDGET_MS}ms"
                )
                null
            }
        }
        return fetchDownloadUrlInner(remoteId, usePriorityPermit = false)
    }

    private suspend fun fetchDownloadUrlInner(remoteId: String, usePriorityPermit: Boolean): String? {
        val authResult = authManager.getAccessToken().first()
        if (authResult !is AuthResult.Success) return null

        val tokenStr = authResult.token
        if (tokenStr.isBlank()) {
            android.util.Log.e(TAG, "Token de acceso vacío. Abortando solicitud.")
            return null
        }

        val token = "Bearer $tokenStr"

        return try {
            executeWithRetry {
                if (usePriorityPermit) {
                    requestCoordinator.acquirePriorityPermit()
                } else {
                    requestCoordinator.acquireRequestPermit()
                }
                
                val item = oneDriveApi.getItem(token, remoteId)
                // Cierra la progresión del backoff a ciegas del coordinador (ver notifyRequestSucceeded).
                requestCoordinator.notifyRequestSucceeded()
                item.downloadUrl
            }
        } catch (e: retrofit2.HttpException) {
            val retryAfter = e.retryAfterSeconds()
            if (HttpStatus.isThrottleSignal(e.code(), retryAfter != null)) {
                // Se pasa TAL CUAL, `null` incluido: quién decide el plazo cuando el servidor no lo
                // dicta es política de throttling y vive en el coordinador, no aquí. Taparlo con un
                // valor propio era lo que mantenía dos respuestas distintas para el mismo caso.
                requestCoordinator.notifyThrottled(retryAfter)
            }
            android.util.Log.e(TAG, "HTTP error refrescando URL ($remoteId): ${e.code()}", e)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error refrescando URL ($remoteId)", e)
            null
        }
    }

    /**
     * URL firmada para un item de OneDrive.
     *
     * @param forPlayback la pide el reproductor para algo que YA está sonando (o intentándolo). Es
     *   lo que activa el permiso prioritario —se salta la cola del rate limiter, aunque sigue
     *   respetando un throttle real— y el presupuesto de [PRIORITY_URL_BUDGET_MS].
     *
     * **Estuvo siempre en `false`.** El parámetro `usePriorityPermit` y toda la rama de
     * `acquirePriorityPermit` existían desde el primer commit sin que nadie los activara, así que la
     * canción recién pulsada hacía cola detrás de las hasta 32 resoluciones del sync masivo, pagando
     * el retardo mínimo entre peticiones como una más. La prioridad estaba escrita, pero no ocurría.
     */
    suspend fun getDownloadUrl(remoteId: String, forPlayback: Boolean = false): String? {
        return fetchDownloadUrl(remoteId, usePriorityPermit = forPlayback)
    }

    /**
     * Foto de perfil de la cuenta de Microsoft (Graph `/me/photo/$value`), para el avatar del
     * header de la biblioteca. Devuelve los bytes de la imagen, o `null` si la cuenta no tiene foto
     * (Graph responde 404), no hay token, o falla la red — en todos esos casos el avatar cae a la
     * inicial del nombre. NO pasa por el rate limiter del sync: es una petición suelta de UI, ajena
     * al escaneo. Reusa `downloadFile` (GET con @Url a URL absoluta), que se salta la base de Graph.
     */
    suspend fun downloadProfilePhoto(): ByteArray? = withContext(Dispatchers.IO) {
        val authResult = authManager.getAccessToken().first()
        if (authResult !is AuthResult.Success || authResult.token.isBlank()) return@withContext null
        try {
            val body = oneDriveApi.downloadFile(
                "Bearer ${authResult.token}",
                "https://graph.microsoft.com/v1.0/me/photo/\$value"
            )
            body.bytes().takeIf { it.isNotEmpty() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.d(TAG, "Sin foto de perfil: ${e.message}")
            null
        }
    }

    /**
     * Función para worker en segundo plano: Lee metadata real del archivo y extrae carátula
     */
    suspend fun extractMetadata(song: Song): Song = withContext(Dispatchers.IO) {
        // Sin reintento: leer los tags de un archivo es determinista, así que repetirlo da el
        // mismo resultado, y el analizador YA trae sus propios timeouts (generosos a propósito).
        // Reintentar solo los duplicaba, dejando a un archivo ilegible bloqueando el pipeline el
        // doble de tiempo.
        val analysis = try {
            if (song.path.startsWith("file://")) {
                val file = File(song.path.removePrefix("file://"))
                audioFileAnalyzer.analyzeFile(file)
            } else {
                audioFileAnalyzer.analyzeUrl(song.path)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Análisis de metadata fallido (${song.title})", e)
            null
        }

        if (analysis != null && analysis.isValid) {
            // Una sola vía de escritura para las carátulas (nombre por contenido, deduplicado):
            // aquí había una copia a mano de la misma lógica, y fue la que se quedó atrás cuando
            // el esquema de nombres cambió.
            val artUri = analysis.embeddedArt
                ?.let { audioFileAnalyzer.persistArtwork(it) }
                ?.let { Uri.parse(it) }
                ?: song.albumArtUri

            song.copy(
                title = analysis.title ?: song.title,
                artist = analysis.artist ?: song.artist,
                album = analysis.album ?: song.album,
                genre = analysis.genre ?: song.genre,
                albumArtist = analysis.albumArtist ?: song.albumArtist,
                artistCredits = if (analysis.albumArtist != null) analysis.artistCredits else song.artistCredits,
                trackNumber = analysis.trackNumber.takeIf { it > 0 } ?: song.trackNumber,
                year = analysis.year.takeIf { it > 0 } ?: song.year,
                duration = if (song.duration == 0L) analysis.duration else song.duration,
                albumArtUri = artUri
            )
        } else {
            song
        }
    }

}