package com.qhana.siku.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.contrast.Contrast
import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import com.qhana.siku.data.model.AlbumColors
import com.qhana.siku.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

@Singleton
class ArtworkRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: IMusicRepository,
    private val musicPreferences: com.qhana.siku.data.preferences.MusicPreferences,
    private val appLogger: com.qhana.siku.data.util.AppLogger
) {

    // Optimización: Cache aumentado para cubrir bibliotecas medianas-grandes (aprox. 16KB de RAM)
    private val memoryCache = LruCache<String, AlbumColors>(COLOR_CACHE_ENTRIES)

    // Mutexes para evitar extracción duplicada de la misma canción
    private val extractionMutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Seeds de imágenes que NO son la carátula de una canción — hoy, la foto de artista de Deezer
     * que seedea el tema de su pantalla de detalle. Va aparte de [memoryCache] porque la clave es
     * otra: allí es el id de la canción y aquí el URI de la imagen, que es lo único que identifica
     * a una foto que no pertenece a ninguna fila de `songs`.
     *
     * **Solo en memoria, a propósito.** Persistirlo pedía una columna en `artists` y su migración,
     * y el cálculo no lo merece: la imagen ya está en el caché de DISCO de Coil (lo caro es
     * bajarla, no cuantizarla), así que tras un reinicio la re-extracción son unos milisegundos
     * sobre un bitmap de 256 px. Si algún día se nota, la columna es un cambio aditivo.
     */
    private val imageSeedCache = LruCache<String, AlbumColors>(IMAGE_SEED_CACHE_ENTRIES)

    /**
     * Tope de análisis de color SIMULTÁNEOS (decodificar la carátula a 256 px + cuantizarla).
     *
     * Es trabajo de FONDO que nadie está mirando —el backfill recorre la biblioteca entera— y sin
     * tope se reparte por `Dispatchers.Default`, que trae un hilo por núcleo: con eso el backfill se
     * queda con la máquina y la UI, el RenderThread y el hilo de audio pelean por lo que sobra.
     * Medido en un trace de Perfetto (release, biblioteca recién escaneada): 349 decodificaciones de
     * bitmap en 30 s, **305 de ellas a 256×256** —o sea de este análisis—, `DefaultDispatch` con
     * 31 364 ms de CPU contra 7038 del hilo principal, y un GC cada 600-700 ms. Justo encima de eso
     * caía el arranque de una canción y sus frames perdidos.
     *
     * NO se acota la decodificación de Coil en general, que es lo que alimenta las listas y los
     * carruseles: ésa sí la está esperando alguien. Lo que se acota es lo invisible.
     *
     * Fracción de los núcleos y no un número fijo: en un teléfono de 8 núcleos deja 2 para el
     * backfill y 6 para todo lo demás, y en uno de 4 no lo estrangula a 1. Con [Semaphore] y no
     * `limitedParallelism` porque el trabajo ya cambia de dispatcher dos veces por llamada (IO para
     * la imagen, Default para la cuantización) y lo que hay que limitar es la operación ENTERA.
     */
    private val analysisSemaphore = Semaphore(
        (Runtime.getRuntime().availableProcessors() / COLOR_ANALYSIS_CORE_FRACTION).coerceAtLeast(1)
    )


    /**
     * Invalida el caché de colores para una canción específica (RAM y DB).
     * Útil cuando el artwork se actualiza en background.
     *
     * Los colores elegidos A MANO por el usuario ([saveManualColor]) son inmunes salvo
     * [force]: la invalidación automática (descarga completada con carátula embebida mejor,
     * healing de artwork) no debe pisar una elección explícita — era el bug de "elijo un color
     * y al relanzar vuelve el anterior": el auto-download terminaba, esto ponía las columnas
     * en NULL y la re-extracción restauraba el color natural de la carátula.
     *
     * @param force también borra colores manuales Y su marca (acciones explícitas del usuario,
     *   como "regenerar colores" en Ajustes).
     */
    suspend fun invalidateCache(songId: String, force: Boolean = false) {
        if (!force && songId in musicPreferences.loadManualColorIds()) return
        if (force) musicPreferences.removeManualColorId(songId)
        memoryCache.remove(songId)
        musicRepository.saveColors(songId, null, null)
    }

    /**
     * Limpia todo el caché de colores en memoria.
     * Usar al cerrar sesión.
     */
    fun clearCache() {
        memoryCache.evictAll()
    }

    /**
     * Guarda un color seleccionado manualmente por el usuario y lo PROPAGA a todo el álbum:
     * el color sigue a la carátula y la carátula es por-álbum, así que todas las canciones
     * con el mismo tag `album` (mismo criterio de agrupado que el browse) reciben el override.
     * Con [album] vacío/null no se propaga (canciones sueltas sin tag no forman un álbum real).
     */
    suspend fun saveManualColor(songId: String, album: String?, color: Int, isDarkTheme: Boolean) {
        val targetIds = if (album.isNullOrBlank()) {
            listOf(songId)
        } else {
            val albumIds = musicRepository.getSongIdsByAlbum(album)
            if (songId in albumIds) albumIds else albumIds + songId
        }
        // Marca de "elegido a mano" ANTES de escribir: protege estos colores de las
        // invalidaciones automáticas (fin de descarga, redescarga, healing de artwork)
        // desde el primer instante. Ver invalidateCache.
        musicPreferences.addManualColorIds(targetIds)
        applyManualColor(targetIds, color, isDarkTheme)
    }

    /**
     * Aplica el override a [songIds] —el álbum entero— conservando el color del OTRO tema de cada
     * canción. Genera automáticamente las variantes light/dark a partir del color elegido.
     *
     * **Una sola escritura para todo el álbum.** Era una por canción, cada una su transacción y su
     * invalidación de `songs`: un toque en el selector sobre un disco de 14 pistas disparaba 14
     * invalidaciones, y Room invalida por TABLA, así que re-ejecutaba las agregaciones de la
     * biblioteca 14 veces. `saveColorsBatch` ya existía —lo usa el backfill de colores— y va sobre
     * un `@Transaction`, así que es UNA invalidación.
     *
     * Las LECTURAS siguen siendo una por canción: no invalidan nada, y el valor que buscan es
     * distinto en cada fila (el color del otro tema, que puede no ser el mismo aunque compartan
     * carátula).
     */
    private suspend fun applyManualColor(songIds: List<String>, color: Int, isDarkTheme: Boolean) {
        // MISMO mutex que la extracción de getAlbumColors: sin él, una extracción EN VUELO
        // (canción recién abierta, colores aún resolviéndose) termina después de este guardado
        // y pisa el override con el color natural — "elijo un color y vuelve el anterior".
        // Serializado: si la extracción va primero, este write gana al esperar el lock; si el
        // manual va primero, la extracción ve el override en RAM (double-check) y ni extrae.
        //
        // Ahora hay que sostener los de TODAS las canciones a la vez, porque el guardado es uno
        // solo. **Se toman en orden de id**, que es lo que evita el abrazo mortal entre dos
        // propagaciones simultáneas con álbumes que se solapan; una extracción nunca toma más de
        // uno, así que no puede cerrar el ciclo por su lado. Y la limpieza de `extractionMutexes`
        // solo retira los que NO están tomados, así que sostenerlos es seguro.
        val ordered = songIds.distinct().sorted()
        if (ordered.isEmpty()) return
        val mutexes = ordered.map { extractionMutexes.getOrPut(it) { Mutex() } }

        mutexes.forEach { it.lock() }
        try {
            val updates = ordered.map { songId ->
                val existing = musicRepository.getColors(songId)
                val primary: Int
                val secondary: Int

                if (isDarkTheme) {
                    secondary = color
                    primary = existing?.primary ?: color
                } else {
                    primary = color
                    secondary = existing?.secondary ?: color
                }
                Triple(songId, primary, secondary)
            }

            musicRepository.saveColorsBatch(updates)
            // La RAM se puebla DESPUÉS del guardado y con los locks aún puestos: es lo que hace que
            // una extracción que entre justo detrás vea el override en su double-check y ni extraiga.
            updates.forEach { (songId, primary, secondary) ->
                memoryCache.put(songId, AlbumColors(primary, secondary))
            }
        } finally {
            mutexes.asReversed().forEach { it.unlock() }
        }
    }

    /**
     * Obtiene los colores de una canción con estrategia de 3 niveles:
     * 1. Memoria RAM (Instantáneo)
     * 2. Base de Datos (Muy rápido)
     * 3. Extracción Optimizada (Rápido - Miniatura 144px)
     *
     * Devuelve `null` cuando NO hay carátula (o la extracción falla): "sin carátula" es un
     * estado distinto de "una carátula que da un color oscuro/claro". El caller decide el
     * neutro a usar. NUNCA se devuelve un gris centinela: eso hacía indistinguible el caso
     * sin-arte de una lectura real oscura y forzaba parches de contraste sobre colores legítimos.
     *
     * Los dos campos de [AlbumColors] son **seeds crudos** por tema, no colores de rol: para
     * pintar un acento 1:1 hay que pasarlos por [accentForTheme].
     */
    suspend fun getAlbumColors(song: Song): AlbumColors? {
        // NIVEL 1: Memoria RAM (Fast path sin lock)
        memoryCache.get(song.id)?.let { return it }

        val mutex = extractionMutexes.getOrPut(song.id) { Mutex() }

        // try/finally y NO `.also {}`: dentro del `withLock` había `return` NO LOCALES (salían de
        // la función entera), así que el bloque de limpieza era inalcanzable y el mapa se quedaba
        // con un Mutex por cada canción cuyo color se hubiera pedido, de por vida del proceso.
        try {
            return mutex.withLock {
                // Double-check después de adquirir lock
                memoryCache.get(song.id) ?: withContext(Dispatchers.IO) {
                    // NIVEL 2: Base de Datos (Persistencia)
                    val dbColors = musicRepository.getColors(song.id)
                    if (dbColors != null) {
                        memoryCache.put(song.id, dbColors)
                        return@withContext dbColors
                    }

                    // NIVEL 3: Extracción bajo demanda (Si no existe)
                    val uri = song.albumArtUriString
                    if (uri != null) {
                        val extracted = extractColorsOptimized(song.id, uri)
                        if (extracted != null) {
                            // Guardar en DB y RAM solo resultados REALES. Un fallo de extracción
                            // (imagen aún no descargada, error de carga) NO se persiste: persistirlo
                            // con colores fallback lo hacía indistinguible de una carátula
                            // legítimamente oscura/monocroma y bloqueaba el reintento natural
                            // (columnas NULL en BD = se re-extrae cuando el artwork esté).
                            musicRepository.saveColors(song.id, extracted.primary, extracted.secondary)
                            memoryCache.put(song.id, extracted)
                            return@withContext extracted
                        }
                    }

                    // Sin carátula (o extracción fallida): null, NO un gris centinela. Ver KDoc.
                    return@withContext null
                }
            }
        } finally {
            // Se quita solo si nadie lo tiene tomado, y con `remove(key, value)` para no borrar un
            // Mutex distinto que otra llamada haya puesto en el mismo hueco.
            //
            // Queda una ventana: entre este `isLocked` y el `remove`, otra llamada puede haber
            // recogido ESTE mutex y estar a punto de bloquearlo, con lo que una tercera crearía uno
            // nuevo y esas dos no se serializarían entre sí. Es inofensivo —el peor caso es extraer
            // dos veces el mismo color, que es determinista e idempotente en RAM y BD— y cerrarlo
            // exigiría un refcount que no compensa para un caché de colores.
            val m = extractionMutexes[song.id]
            if (m != null && !m.isLocked) {
                extractionMutexes.remove(song.id, m)
            }
        }
    }

    /**
     * Extrae colores usando una versión reducida pero detallada (256px) y "Democracia".
     * Ejecuta el procesamiento de imagen en Dispatchers.Default (CPU bound).
     *
     * @return null si la extracción FALLÓ (imagen no cargable, error de procesamiento).
     *         Los callers no deben persistir el fallo: se reintenta cuando haya artwork.
     */
    suspend fun extractColorsOptimized(id: String, uri: String): AlbumColors? = analysisSemaphore.withPermit {
        // Paso 1: Carga de Imagen (I/O Bound)
        val bitmap = withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(uri)
                    .size(ANALYSIS_BITMAP_PX)
                    .allowHardware(false)
                    // El caché de MEMORIA queda fuera a propósito. Este bitmap de 256 px es un
                    // insumo del análisis y no lo vuelve a pedir NADIE: guardarlo solo servía para
                    // ocupar sitio en el mismo pool que las carátulas de las listas (el 25 % del
                    // heap, ver `IMAGE_MEMORY_CACHE_FRACTION`) y expulsarlas. Se notaba justo
                    // cuando más molesta: durante un backfill de colores —lotes de 500— la lista
                    // que el usuario está scrolleando se queda sin sus miniaturas y hay que
                    // redecodificarlas. El de DISCO sí se conserva: ese evita volver a BAJAR la
                    // imagen, que es lo caro de verdad, y no compite con nada.
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()

                val result = context.imageLoader.execute(request)
                (result as? SuccessResult)?.image?.toBitmap()
            } catch (e: CancellationException) {
                // Se relanza SIEMPRE: esta extracción vive en un job que se cancela en cuanto
                // cambia la canción, y tragarse la cancelación la convertía en un `null`
                // indistinguible de "esta carátula no da color" — el caller lo trataba como
                // fallo real en vez de como "ya no interesa".
                throw e
            } catch (e: Exception) {
                Log.e("ArtworkRepo", "Error loading image for $id", e)
                null
            }
        } ?: return@withPermit null

        // Paso 2: Procesamiento de Color (CPU Bound)
        return@withPermit withContext(Dispatchers.Default) {
            try {
                // El MISMO seed en los dos slots: la extracción no distingue por tema, porque no
                // hay nada del seed que dependa del tema (ver [extractSeeds]). Los slots siguen
                // separados por el override MANUAL, que sí es por tema (ver [applyManualColor]).
                extractSeeds(bitmap, logId = id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ArtworkRepo", "Error processing colors for $id", e)
                null
            }
        }
    }

    /**
     * Seeds de una imagen SUELTA, identificada por su URI y no por una canción: la foto de artista
     * con la que el detalle de artista seedea su tema local. Uno por tema, como una carátula.
     *
     * Misma extracción que la de una carátula ([extractColorsOptimized] → Celebi + Score), así que
     * una foto y una portada dan colores comparables y las dos pantallas de detalle se ven
     * hermanas. Cachea en memoria por URI y devuelve `null` si la imagen no se pudo cargar — que
     * el caller debe tratar como "esta pantalla no tiene color propio", nunca como un gris.
     *
     * Los fallos NO se cachean: una foto que no cargó por falta de red tiene que poder reintentarse
     * al volver a entrar, igual que en [getAlbumColors].
     */
    suspend fun seedForImage(uri: String): AlbumColors? {
        imageSeedCache.get(uri)?.let { return it }
        val seeds = extractColorsOptimized(uri, uri) ?: return null
        imageSeedCache.put(uri, seeds)
        return seeds
    }

    // --- DEBUGGING ---

    data class DebugColorInfo(
        val bitmap: Bitmap,
        val candidates: List<ColorCandidate>,
        /**
         * Acento GUARDADO de la canción (el de `songs.primary`/`secondary` según el tema). No es
         * lo que se ve en pantalla: de aquí sale el seed del que el estilo de paleta deriva el
         * `primary` real, que puede triplicarle el croma.
         */
        val winnerColor: Int
    )

    data class ColorCandidate(
        val color: Int,
        val weight: Int,
        val r: Int,
        val g: Int,
        val b: Int,
        /** Es el candidato resaltado: o ES el acento guardado, o es el de matiz más cercano. */
        val isWinner: Boolean,
        /**
         * El seed guardado es EXACTAMENTE este color, no solo su pariente de matiz. Es el caso
         * NORMAL desde que el seed se persiste crudo (29 jul 2026), tanto si lo eligió el usuario
         * como si lo eligió el análisis. Solo falla en las filas guardadas antes de ese cambio,
         * que llevan el seed proyectado a T40/T80 y por tanto no coinciden en RGB con ningún
         * candidato. La UI usa esto para no llamar "actual" a un color que no es el guardado.
         *
         * OJO: "guardado" no es lo mismo que "aplicado". El color aplicado es el `primary` que
         * MaterialKolor deriva de este seed, y ese no está en la lista ni tiene por qué estarlo.
         */
        val isExact: Boolean = false
    )

    suspend fun debugExtractColors(
        uri: String,
        isDarkTheme: Boolean = false,
        savedColors: AlbumColors? = null
    ): DebugColorInfo? {
        return withContext(Dispatchers.Default) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(uri)
                    // MISMA resolución que la extracción real, y por eso comparten CONSTANTE: el
                    // visor de candidatos tiene que enseñar lo que el algoritmo vio de verdad, y
                    // dos literales acoplados por un comentario dejan de coincidir en cuanto uno
                    // se toca — el visor mentiría sin que nada fallara.
                    .size(ANALYSIS_BITMAP_PX)
                    .allowHardware(false)
                    .memoryCachePolicy(CachePolicy.DISABLED) // Forzar carga fresca
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()

                val result = context.imageLoader.execute(request)
                val bitmap = (result as? SuccessResult)?.image?.toBitmap()

                if (bitmap != null) {
                    val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    analyzeBitmapForDebug(mutableBitmap, isDarkTheme, savedColors)
                } else {
                    null
                }
            } catch (e: CancellationException) {
                // Igual que en `extractColorsOptimized`: cambiar de canción con el visor de
                // candidatos abierto cancela este job, y tragarse la cancelación lo presentaba
                // como "esta carátula no tiene candidatos" en vez de como "ya no interesa".
                throw e
            } catch (e: Exception) {
                appLogger.error("Error in debugExtractColors: ${e.message}")
                null
            }
        }
    }

    // --- CORE LOGIC (UNIFICADA) ---

    /**
     * Candidatos de color de la carátula, ya rankeados por idoneidad para UI.
     *
     * Pipeline ESTÁNDAR de Material You (el mismo que usa Android para los colores del fondo de
     * pantalla), no uno propio: [QuantizerCelebi] agrupa los píxeles con Wu + k-means en espacio
     * **CAM16** —perceptualmente uniforme— y [Score] los rankea por proporción del hue y croma,
     * descartando lo que no sirve como acento.
     *
     * Sustituye al histograma propio de cubos RGB de paso fijo, que tenía dos defectos de raíz:
     * (a) los bordes de cubo son arbitrarios, así que un degradado —fondo de portada habitual—
     * se partía en bandas y el peso del color dominante se repartía entre varios cubos vecinos,
     * pudiendo perder contra un color plano menos representativo; y (b) mezclaba cuatro métricas
     * de claridad distintas (saturación HSV, luma Rec.601, lightness HSL y luminancia WCAG) cuyos
     * umbrales NO son comparables: con el mismo 0.5 de lightness, un amarillo tiene luminancia
     * 0.93 y un azul 0.07, de modo que un mismo umbral aceptaba uno y descartaba el otro.
     */
    private fun extractCandidates(bitmap: Bitmap): List<ColorCandidate> =
        rankCandidates(quantizeOpaquePixels(bitmap))

    /** Rankea con [Score] un mapa ya cuantizado (color ARGB → nº de píxeles). */
    private fun rankCandidates(populationByColor: Map<Int, Int>): List<ColorCandidate> {
        if (populationByColor.isEmpty()) return emptyList()

        // PASO PREVIO: fuera los colores de tono extremo. [Score] solo descarta por croma
        // (CUTOFF_CHROMA = 5.0) y pondera la PROPORCIÓN con peso 0.7 contra 0.3 del croma, así que
        // en una portada mayoritariamente negra gana el negro del fondo si su croma pasa el corte
        // por poco. Caso REAL medido (Scenes from a Memory, Dream Theater): ganaba #07070B, croma
        // 5.3 —0.3 por encima del corte— y tono 2.0; el tema entero se sembraba con el fondo negro
        // y, al re-tonalizarlo a T80 para `secondary`, el croma caía bajo el mismo 5.0 y la app se
        // iba a Monochrome (gris) con una carátula llena de naranjas. System UI, sobre la MISMA
        // portada, sacaba el naranja porque descarta los extremos de luminancia antes de rankear.
        //
        // Un color de tono 2 no es un acento: es el fondo. El corte se calibró sobre los seeds
        // reales del log de una biblioteca de 777 canciones — los dos casos rotos estaban en tono
        // 2.0 y 4.6, y el seed legítimo más oscuro (Black Clouds & Silver Linings) en 18.0, con el
        // rojo de Fall Out Boy en 22.6. El hueco entre 4.6 y 18 es donde vive [MIN_SEED_TONE].
        //
        // El filtro va ANTES de [Score], no sobre su resultado: Score mide la proporción de cada
        // hue sobre la población que recibe, así que quitar la masa negra sube el peso relativo de
        // lo que sí sirve como acento. Filtrando después, una portada donde solo el negro pasara
        // el corte devolvería lista vacía = "acromática", que es justo el diagnóstico equivocado.
        val usable = populationByColor.filterKeys { argb ->
            val tone = Hct.fromInt(argb).tone
            tone >= MIN_SEED_TONE && tone <= MAX_SEED_TONE
        }
        // Si NO queda nada, la portada es íntegramente muy oscura o muy clara: se rankea el mapa
        // original y que decida el croma. Vaciar la entrada aquí convertiría en "acromática" a
        // cualquier portada de rango tonal estrecho, que es el error opuesto.
        val scoreInput = usable.ifEmpty { populationByColor }

        // filter = true descarta acromáticos (croma < 5) y hues con presencia < 1%: es el filtro
        // de "sirve como acento" que al algoritmo anterior le faltaba (solo tenía tope de
        // saturación, nunca mínimo, y por eso un gris sucio masivo podía ganar).
        //
        // fallbackColorArgb = null es DELIBERADO: por defecto Score devuelve un azul de relleno
        // cuando nada pasa el filtro, y una carátula en blanco y negro saldría azul. Con null la
        // lista vuelve VACÍA, que es justo la señal de "carátula acromática".
        val ranked = Score.score(
            scoreInput,
            desired = CANDIDATE_COUNT,
            fallbackColorArgb = null,
            filter = true
        )

        return ranked.map { argb ->
            ColorCandidate(
                color = argb,
                // Score devuelve las MISMAS claves del mapa (`Hct.toInt()` conserva el argb con
                // el que se construyó), así que la población se lee directo.
                weight = populationByColor[argb] ?: 0,
                r = android.graphics.Color.red(argb),
                g = android.graphics.Color.green(argb),
                b = android.graphics.Color.blue(argb),
                isWinner = false
            )
        }
    }

    /**
     * Distancia angular entre dos matices HCT (0..180), tratando el cierre del círculo: 350° y
     * 10° están a 20°, no a 340°. Se calcula aquí en vez de usar el `MathUtils` de la librería
     * porque ese es interno.
     */
    private fun hueDistance(a: Double, b: Double): Double {
        val diff = kotlin.math.abs(a - b) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }

    /** Píxeles OPACOS de la carátula agrupados por color (ARGB → nº de píxeles). */
    private fun quantizeOpaquePixels(bitmap: Bitmap): Map<Int, Int> {
        val width = bitmap.width
        val height = bitmap.height
        // Una sola llamada JNI para toda la imagen: `bitmap[x, y]` por píxel cruza a nativo
        // ~65.000 veces y ese cruce dominaba el coste.
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // El quantizer NO mira el alpha (su QuantizerMap cuenta el int crudo), así que los
        // píxeles transparentes entrarían como negro y arrastrarían el resultado. Se descartan
        // aquí, y a los que quedan se les fuerza alpha 255: sin eso, un mismo color con dos
        // alphas distintos serían dos claves separadas y su población quedaría dividida.
        val opaque = IntArray(pixels.size)
        var opaqueCount = 0
        for (pixel in pixels) {
            if (android.graphics.Color.alpha(pixel) < MIN_OPAQUE_ALPHA) continue
            opaque[opaqueCount++] = pixel or OPAQUE_ALPHA_MASK
        }
        if (opaqueCount == 0) return emptyMap()

        return QuantizerCelebi.quantize(opaque.copyOf(opaqueCount), MAX_QUANTIZED_COLORS)
    }

    private fun analyzeBitmapForDebug(
        bitmap: Bitmap,
        isDarkTheme: Boolean = false,
        savedColors: AlbumColors? = null
    ): DebugColorInfo {
        // La lógica REAL de producción, para el tema en curso: sin `savedColors`, lo que la
        // extracción automática elegiría ahora.
        val winnerColor = savedColors
            ?.let { if (isDarkTheme) it.secondary else it.primary }
            ?: extractSeeds(bitmap).let { if (isDarkTheme) it.secondary else it.primary }

        // La lista NO se toca: los candidatos salen siempre en su orden de ranking, ni se
        // reordena ni se añaden filas. Lo único que cambia es cuál va resaltado — que el color
        // elegido saltara de posición entre aperturas del diálogo era justo lo que confundía.
        val candidates = extractCandidates(bitmap).take(CANDIDATE_COUNT)

        // Cuál resaltar. Ahora que el seed se persiste CRUDO, lo normal es que coincida EXACTO con
        // el candidato del que salió (elegido a mano o por el análisis, da igual). El emparejado
        // por matiz sigue haciendo falta para las filas guardadas ANTES del cambio, que llevan el
        // seed ya proyectado a T40/T80: ahí no coincide en RGB pero conserva el hue, así que se
        // resalta el candidato del que proviene. Esas filas se arreglan al regenerar colores.
        val current = Hct.fromInt(winnerColor)
        val exactIndex = candidates.indexOfFirst {
            (it.color or OPAQUE_ALPHA_MASK) == (winnerColor or OPAQUE_ALPHA_MASK)
        }
        val highlighted = when {
            exactIndex >= 0 -> exactIndex
            // Un actual sin croma (carátula acromática → negro/blanco) no proviene de ningún
            // candidato; emparejarlo por matiz daría un resultado arbitrario. No se marca nada.
            current.chroma < MIN_ACCENT_CHROMA -> NO_HIGHLIGHT
            else -> candidates.indices.minByOrNull { index ->
                hueDistance(Hct.fromInt(candidates[index].color).hue, current.hue)
            } ?: NO_HIGHLIGHT
        }

        return DebugColorInfo(
            bitmap,
            candidates.mapIndexed { index, candidate ->
                candidate.copy(
                    isWinner = index == highlighted,
                    isExact = index == exactIndex
                )
            },
            winnerColor
        )
    }

    /**
     * El **seed** de la carátula: el candidato mejor puntuado por [Score], TAL CUAL está en la
     * imagen. Sin re-tonalizar —la proyección perdía croma de forma irreversible (medido: 80.1 →
     * 29.4)— y sin más filtros. Va en los dos slots de [AlbumColors] (`primary` para el tema claro,
     * `secondary` para el oscuro), que siguen existiendo porque el override MANUAL sí es por tema.
     *
     * **Aquí no se elige por tema, y el motivo es que el TONO del seed no llega al esquema**
     * (17 sep 2026). Del seed, MaterialKolor solo toma el HUE para construir la paleta primaria —y
     * además el CROMA con Fidelidad y Fiel a la carátula—; el tono de cada rol lo pone el spec 2021
     * como constante (`primary` T40 en claro, T80 en oscuro). Así que un seed claro y uno oscuro
     * del mismo matiz producen EL MISMO acento, y filtrarlos por su contraste en bruto solo servía
     * para acabar pintando otro matiz de la portada sin ganar un punto de contraste.
     *
     * Eso es lo que hacía el filtro que vivió aquí del 11 al 17 sep 2026: con una carátula de
     * colores claros en tema claro descartaba el color más representativo —"no contrasta"— cuando
     * el acento que habría generado era T40, perfectamente legible. **No reintroducirlo.** Lo que
     * sí depende del tono del seed es el `primaryContainer` de Fidelidad y Fiel a la carátula, que
     * recibe el color crudo; eso se avisa en el punto de uso, donde el acento final ya se conoce
     * (ver `accentHasContrast`), y no se arregla eligiendo otro color para todos los estilos.
     */
    private fun extractSeeds(bitmap: Bitmap, logId: String? = null): AlbumColors {
        val populationByColor = quantizeOpaquePixels(bitmap)
        val candidates = rankCandidates(populationByColor)

        // Única condición de "sin color": que NINGÚN matiz pase el filtro de Score (croma ≥ 5 y
        // ≥1% de presencia). Es el caso del blanco y negro de verdad, y ahí no hay nada que
        // inventar. Hubo un intento de ir más allá —exigir que un % mínimo de la portada fuese
        // cromático para evitar que unas líneas de color tiñeran una foto en B&N— y se
        // DESCARTÓ (21 jul 2026): mandaba a gris portadas que sí tienen color, como el sepia
        // verdoso de Parasomnia, cuyos candidatos el algoritmo extrae correctamente. No
        // reintroducirlo sin un caso real que lo justifique.
        if (candidates.isEmpty()) {
            // El croma máximo dice a qué distancia quedó del corte de Score (5.0): un 4.8 es una
            // carátula EN EL FILO; un 0.3 es blanco y negro de verdad.
            val maxChroma = populationByColor.keys.maxOfOrNull { Hct.fromInt(it).chroma } ?: 0.0
            appLogger.log(
                COLOR_LOG_CATEGORY,
                "${logId ?: "debug"}: sin matiz — croma máx ${"%.1f".format(maxChroma)} " +
                    "en ${populationByColor.size} colores → gris neutro"
            )
            // GRIS PURO (croma 0), no negro/blanco: dispara el esquema Monochrome del tema igual de
            // bien, y como acento 1:1 se re-tonaliza al pintarlo (ver [NEUTRAL_SEED_TONE]).
            return AlbumColors(NEUTRAL_SEED_ARGB, NEUTRAL_SEED_ARGB)
        }

        val chosen = candidates.first().color
        val seed = Hct.fromInt(chosen)
        // El hex se formatea APARTE: el id puede traer un '%' (rutas locales) y aplicar format()
        // sobre la cadena ya interpolada reventaría con IllegalFormatException.
        val seedHex = "#%06X".format(0xFFFFFF and chosen)
        appLogger.log(
            COLOR_LOG_CATEGORY,
            // Croma con decimal porque el corte de Score está en 5.0, justo donde hay que
            // discriminar. El tono se sigue registrando aunque ya no decida nada: es lo que
            // distingue dos candidatos del mismo matiz al mirar un caso real en el log.
            "${logId ?: "debug"}: seed $seedHex croma ${"%.1f".format(seed.chroma)} " +
                "tono ${"%.1f".format(seed.tone)} de ${candidates.size} candidatos"
        )
        return AlbumColors(chosen, chosen)
    }

    companion object {
        /**
         * ¿Este acento es ACROMÁTICO (un gris) y no un color de verdad? Lo pregunta el tema para
         * decidir entre seedear el `ColorScheme` o irse al esquema neutro (`PaletteStyle.Monochrome`).
         *
         * Se mide con el **croma HCT** —la misma métrica con la que [Score] decidió si la carátula
         * tenía algún matiz aprovechable— y NO con la saturación HSV, que depende del brillo: el
         * MISMO acento cae a un lado u otro del umbral según el tono al que se lo re-encuadre. El
         * par de una carátula sepia de croma 5.6 (Train of Thought) sale #645D54 en T40 (saturación
         * 0.160) y #CFC5B9 en T80 (0.106), así que un umbral HSV de 0.15 la daba por cromática en
         * tema claro y por gris en oscuro — el tema oscuro tiraba a Monochrome un color que el
         * pipeline había extraído bien. Es el mismo error de fondo que ya se corrigió en la
         * extracción: umbrales de claridad que no son comparables entre sí.
         *
         * El neutro de una carátula sin matiz ([extractSeeds]) se construye con croma 0
         * EXACTO, así que el caso B&N real sigue detectándose por construcción.
         *
         * Desde el 29 jul 2026 juzga el seed CRUDO, que es lo correcto: es el mismo color sobre el
         * que [Score] aplicó su corte de croma. Antes juzgaba el seed ya proyectado a T40/T80, cuyo
         * croma es otro (medido: 80.1 crudo → 29.4 a T80), así que un color en el filo podía caer a
         * un lado del umbral en la extracción y al otro aquí.
         */
        fun isAchromatic(argb: Int): Boolean = Hct.fromInt(argb).chroma < MIN_ACCENT_CHROMA

        /**
         * ¿Se distingue [accentArgb] sobre [surfaceArgb]? La ÚNICA medición de contraste que queda
         * en el sistema de color, y mide **colores ya derivados** —el `primary` y el `surface` de un
         * esquema concreto—, nunca un color de la portada.
         *
         * Esa es toda la lección de esta parte: el color crudo no se pinta en ningún sitio. Del
         * seed, MaterialKolor solo se lleva el matiz (y el croma con Fidelidad y Fiel a la
         * carátula); el tono lo pone el spec. Medir el crudo era medir un color que nadie ve —ver
         * [extractSeeds], donde ese error hacía que una portada clara perdiera su color en tema
         * claro—, así que la pregunta se hace donde el acento final ya existe: en la UI, que es
         * quien conoce el tema y el estilo de paleta.
         *
         * Se mide por TONO, que en HCT es el L* de CIELAB y de donde sale la luminancia. El umbral
         * es el de un componente NO textual de WCAG (1.4.11): un acento acaba como relleno, y el
         * texto que caiga encima lo garantiza M3 con su propio par.
         */
        fun accentHasContrast(accentArgb: Int, surfaceArgb: Int): Boolean =
            Contrast.ratioOfTones(
                Hct.fromInt(accentArgb).tone,
                Hct.fromInt(surfaceArgb).tone
            ) >= ACCENT_MIN_CONTRAST

        /**
         * El acento LISTO PARA PINTAR que le toca a [seedArgb] en el tema en curso.
         *
         * Las columnas de color guardan el **seed CRUDO** de la carátula, no un color de rol (ver
         * [extractSeeds]). El tono es una propiedad del tema y no del álbum, así que la
         * proyección a T40/T80 se hace aquí, en el punto de uso. Este es el ÚNICO sitio que la
         * hace: tenerla en dos lados es la forma más fácil de que dos superficies pinten acentos
         * distintos para la misma canción.
         *
         * Lo llaman las superficies que usan el acento **1:1** — el widget (que no tiene un
         * `MaterialTheme` seedeado y necesita un color ya listo) y el resaltado de la fila que
         * suena. Quien seedea un tema M3 NO debe llamarlo: ahí el seed va crudo y MaterialKolor
         * reencuadra cada rol por su cuenta, que es justamente el croma que se estaba perdiendo.
         *
         * Es **idempotente sobre los datos viejos**: una fila guardada con el esquema anterior ya
         * contiene el T40/T80, y re-tonalizarla a su propio tono la deja igual. Por eso estas dos
         * superficies no cambian ni un píxel al migrar de esquema, ni siquiera antes de regenerar
         * los colores — lo único que sigue apagado hasta la regeneración es el seed del tema.
         */
        fun accentForTheme(seedArgb: Int, isDarkTheme: Boolean): Int =
            Hct.fromInt(seedArgb)
                .withTone(if (isDarkTheme) DARK_THEME_ACCENT_TONE else LIGHT_THEME_ACCENT_TONE)
                .toInt()

        // Entradas del caché de colores en RAM (~16KB): cubre bibliotecas medianas-grandes
        // sin re-extraer al scrollear.
        private const val COLOR_CACHE_ENTRIES = 2000

        /**
         * Entradas del caché de seeds por URI ([seedForImage]). Dos órdenes de magnitud menos que
         * [COLOR_CACHE_ENTRIES] porque el uso también lo es: son las pantallas de detalle de
         * artista que se visitan en una sesión, no una biblioteca entera scrolleándose. Con 200
         * caben de sobra las de cualquier sesión real y ocupan unos cientos de bytes.
         */
        private const val IMAGE_SEED_CACHE_ENTRIES = 200

        /**
         * Lado en px al que se reduce la carátula ANTES de cuantizarla. Lo comparten la
         * extracción real y el visor de candidatos del diálogo de color: si difirieran, el visor
         * enseñaría un ranking distinto del que la app aplicó.
         *
         * El coste del cuantizador es proporcional al nº de píxeles, así que doblar el lado lo
         * cuadruplica; a este tamaño la población de cada color ya es representativa (son
         * ~65.000 muestras para 128 grupos) y subir no cambia el ganador.
         */
        /**
         * Porción de los núcleos que puede ocupar el análisis de color. Ver [analysisSemaphore].
         * Un cuarto: bastante para que el backfill avance solo, poco para que se note.
         */
        private const val COLOR_ANALYSIS_CORE_FRACTION = 4
        private const val ANALYSIS_BITMAP_PX = 256

        // Colores que el quantizer produce antes de rankear. 128 es el valor que usa Android
        // para los colores del fondo de pantalla con este mismo pipeline.
        private const val MAX_QUANTIZED_COLORS = 128

        // Candidatos rankeados que se conservan: el primero es el seed y el resto alimenta el
        // color lab. Score ya devuelve como mucho uno por familia de hue, así que no hace falta
        // deduplicar a mano.
        private const val CANDIDATE_COUNT = 8

        // Un píxel por debajo de este alpha no cuenta (bordes suavizados, PNG con transparencia).
        private const val MIN_OPAQUE_ALPHA = 128
        private const val OPAQUE_ALPHA_MASK = 0xFF000000.toInt()

        // Croma HCT mínimo para considerar que un color TIENE matiz. Es el MISMO corte que aplica
        // Score al filtrar candidatos (CUTOFF_CHROMA = 5.0), y a propósito: lo que el ranking
        // aceptó como acento no puede luego declararse gris. Dos usos: [isAchromatic] (¿seedea el
        // tema o va a Monochrome?) y el emparejado del selector — un neutro (croma 0) no proviene
        // de ningún candidato y emparejarlo por hue daría un resultado arbitrario.
        private const val MIN_ACCENT_CHROMA = 5.0

        // Rango de TONO (L* de CIELAB, 0..100) en el que un color puede aspirar a ser el seed del
        // tema. Es un filtro distinto del croma y previo a [Score] (ver [rankCandidates]): el
        // croma dice "tiene matiz", el tono dice "es un color, no el fondo ni el papel".
        //
        // Calibrado con los seeds REALES de una biblioteca de 777 canciones: los dos únicos casos
        // rotos estaban en tono 2.0 (#07070B) y 4.6 (#110F14) —ambos con croma apenas sobre 5, que
        // es lo que les dejaba pasar— mientras que el seed legítimo más oscuro estaba en 18.0 y el
        // más claro en 93.2. El corte vive en el hueco entre 4.6 y 18, con margen para no rozar
        // ninguno de los dos lados.
        //
        // NO confundir con el filtro de FRACCIÓN CROMÁTICA que se probó y descartó el 21 jul 2026:
        // aquel medía qué porcentaje de la portada tenía color y mandaba a gris portadas que sí lo
        // tenían (Parasomnia). Este mira el tono de CADA candidato y no altera el caso B&N, cuyos
        // candidatos ya caen antes por croma.
        private const val MIN_SEED_TONE = 10.0
        private const val MAX_SEED_TONE = 95.0

        // Categoría propia en AppLogger: el diagnóstico de color no es un error ni pertenece a
        // reproducción.
        private const val COLOR_LOG_CATEGORY = "COLOR"

        /** Ningún candidato corresponde al color actual (índice imposible). */
        private const val NO_HIGHLIGHT = -1

        // Tonos HCT del rol `primary` de M3: T40 sobre fondo claro y T80 sobre fondo oscuro.
        // No son estéticos: 40 puntos de diferencia en tono HCT garantizan un contraste ≥ 3.0
        // contra el fondo del tema (ver KDoc de Hct), que es lo que antes se intentaba asegurar
        // a posteriori con umbrales de luminancia WCAG y colores de rescate hardcodeados.
        //
        // Los usa SOLO [accentForTheme], al pintar. La extracción ya no los aplica: persistir el
        // seed proyectado a estos tonos era lo que le costaba el croma al acento de toda la app.
        private const val LIGHT_THEME_ACCENT_TONE = 40.0
        private const val DARK_THEME_ACCENT_TONE = 80.0

        // Contraste mínimo de un acento contra la superficie donde se pinta ([accentHasContrast]).
        // No hay constantes de tono de superficie que lo acompañen: la superficie se PASA, porque
        // quien mide tiene delante el esquema entero y no tiene por qué suponer en cuál cae.
        private const val ACCENT_MIN_CONTRAST = 3.0

        // Tono del gris que representa una carátula SIN matiz. Da igual cuál sea —lo que importa
        // es el croma 0, que es lo que dispara el esquema Monochrome, y quien lo pinta lo
        // re-tonaliza igual—, así que se elige el centro de la escala: no sugiere ningún tema, a
        // diferencia de un T40 o un T80, que ahora significarían "ya proyectado".
        private const val NEUTRAL_SEED_TONE = 50.0

        /**
         * Gris PURO (croma 0) que representa una carátula SIN color. Dispara el esquema Monochrome
         * del tema igual que un neutro extraído. Lo usa la extracción ([extractSeeds]) y el
         * tema global para una canción sin carátula (seed fijo en vez de heredar el color anterior).
         */
        val NEUTRAL_SEED_ARGB: Int = Hct.from(0.0, 0.0, NEUTRAL_SEED_TONE).toInt()
    }
}
