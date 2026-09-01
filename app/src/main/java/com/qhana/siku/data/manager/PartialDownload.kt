package com.qhana.siku.data.manager

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Un tramo `[start, endExclusive)` del archivo remoto, con lo que ya se trajo de él.
 *
 * Existe para que una descarga sea **reanudable** y **divisible**, que son la misma necesidad vista
 * desde dos lados: si se sabe qué bytes faltan, se pueden pedir tras un corte (reanudar) y se pueden
 * pedir por varias conexiones a la vez (segmentar).
 *
 * **Un solo lector por segmento.** `reserve`/`confirm` los llama siempre la corrutina que lo está
 * bajando, en ese orden y sin solaparse consigo misma; lo único que llega de fuera es [split]. Por
 * eso basta un lock corto y no hace falta nada atómico más sofisticado.
 *
 * **`reserved` vs `confirmed` no es redundancia.** `reserved` es lo que este segmento se ha
 * adjudicado (y por tanto el punto por el que va pidiendo bytes); `confirmed` es lo que de verdad
 * llegó al archivo. El sidecar persiste SOLO `confirmed`, porque reanudar por encima de lo escrito
 * dejaría un hueco de basura en mitad del audio — y un hueco no se detecta por tamaño, que es el
 * único control que hay al final.
 */
internal class DownloadSegment(
    val start: Long,
    endExclusive: Long,
    confirmed: Long = 0L
) {
    private val lock = Any()

    @Volatile
    var endExclusive: Long = endExclusive
        private set

    /** Bytes de este tramo escritos en el archivo. Lo que se persiste. */
    @Volatile
    var confirmed: Long = confirmed
        private set

    /** Bytes adjudicados a este tramo (≥ [confirmed]). Marca por dónde se pide al servidor. */
    private var reserved: Long = confirmed

    /**
     * Cuándo avanzó este tramo por última vez. Lo vigila el watchdog anti-stall.
     *
     * Es POR TRAMO y no un contador global de la descarga, que es lo que había: con varias
     * conexiones sobre el mismo archivo, un caudal agregado sigue subiendo mientras cualquiera de
     * ellas avance, así que un tramo colgado no se detectaba hasta quedarse solo — hasta un minuto
     * más tarde de lo que debía, y para entonces el corte se llevaba por delante a los que sí iban.
     *
     * Arranca en "ahora" y se refresca al TOMAR el tramo ([rewindToConfirmed], que es lo primero que
     * hace su dueño) para no contar como parado el rato que pasó en la cola sin nadie.
     *
     * **Reloj MONÓTONO, y no es un detalle**: esto mide un plazo de 60 s contra el que se ABORTA una
     * descarga. Con `currentTimeMillis` —lo que había— una corrección de hora del sistema hacia
     * adelante mataba una conexión que iba perfectamente, y hacia atrás dejaba pasar un cuelgue real
     * hasta que el reloj se pusiera al día. No se persiste en el sidecar (ahí solo va `confirmed`),
     * así que el valor no tiene que sobrevivir al proceso y puede ser de un reloj sin época.
     */
    @Volatile
    var lastProgressAtMs: Long = SystemClock.elapsedRealtime()
        private set

    /** Le da al tramo una ventana nueva de vigilancia sin haber escrito nada (lo usa el watchdog). */
    fun markActive() {
        lastProgressAtMs = SystemClock.elapsedRealtime()
    }

    /** Primer byte que falta pedir. Solo tiene sentido leerlo con el segmento parado. */
    val nextByte: Long get() = start + confirmed

    /** Lo que le queda por traer. Aproximado mientras corre: sirve para decidir un [split]. */
    val pending: Long get() = endExclusive - (start + confirmed)

    val isComplete: Boolean get() = start + confirmed >= endExclusive

    /**
     * Adjudica sitio para [count] bytes recién leídos y devuelve dónde escribirlos y cuántos caben.
     *
     * Devuelve menos de lo pedido —o cero— cuando un [split] concurrente le recortó el final: esos
     * bytes ya son de otro segmento, así que se descartan y los volverá a pedir su nuevo dueño. Se
     * pierde como mucho un buffer de lectura, y solo en el instante del split.
     */
    fun reserve(count: Int): Pair<Long, Int> = synchronized(lock) {
        val position = start + reserved
        val room = endExclusive - position
        if (room <= 0L) return 0L to 0
        val take = minOf(count.toLong(), room).toInt()
        reserved += take
        position to take
    }

    /** Los [count] bytes reservados ya están escritos en el archivo. */
    fun confirm(count: Int) = synchronized(lock) {
        confirmed += count
        lastProgressAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * Devuelve la marca de reserva al último byte confirmado. Lo llama quien toma el tramo, ANTES de
     * pedir nada.
     *
     * Sin esto, un tramo que se retoma tras un fallo pediría al servidor desde `confirmed` (que es
     * lo que hay escrito) pero colocaría los bytes desde `reserved` (que quedó por delante si la
     * escritura falló entre reservar y confirmar): el contenido entraría DESPLAZADO en el archivo,
     * con el tamaño final correcto y el audio roto. Es seguro porque solo puede llamarse con el
     * tramo parado y en poder de un único dueño.
     */
    fun rewindToConfirmed() = synchronized(lock) {
        reserved = confirmed
        // Tomarlo cuenta como actividad: la espera en la cola no es un stall.
        lastProgressAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * Cede la segunda mitad de lo que falta para que otra conexión la baje en paralelo, o `null` si
     * no queda bastante como para que valga la pena.
     *
     * Recorta el final por debajo del lector en marcha, que es seguro por construcción: [reserve]
     * solo entrega sitio por debajo de `endExclusive`, así que en cuanto se mueve el corte el lector
     * deja de adjudicarse bytes y termina. El punto de corte se toma sobre `reserved` y no sobre
     * `confirmed` para no ceder un tramo que el lector ya se había adjudicado.
     */
    fun split(minTailBytes: Long): DownloadSegment? = synchronized(lock) {
        val position = start + reserved
        val remaining = endExclusive - position
        // Se exige el doble del mínimo: partir deja DOS tramos y ninguno debe quedar por debajo.
        if (remaining < minTailBytes * 2) return null
        val mid = position + remaining / 2
        val tail = DownloadSegment(mid, endExclusive)
        endExclusive = mid
        tail
    }

    /**
     * Da por terminado el tramo donde va escrito ahora mismo.
     *
     * Es para el único caso en que el final no se sabe de antemano: un cuerpo sin `Content-Length`,
     * donde el fin del stream ES el fin del archivo y el segmento se creó abierto hasta
     * `Long.MAX_VALUE`. Sin esto nunca se daría por completo y una descarga correcta se reportaría
     * como truncada.
     */
    fun sealAtWritten() = synchronized(lock) {
        endExclusive = start + confirmed
    }

    override fun toString(): String = "[$start,$endExclusive) +$confirmed"
}

/**
 * Lo que hay que saber para retomar una descarga a medias: qué archivo remoto era, cuánto medía y
 * qué tramos faltan.
 *
 * Se guarda junto al `.part` en un sidecar de texto. NO va a Room a propósito: es estado de un
 * archivo en disco, muere con él y no lo consulta nadie más; meterlo en la base de datos añadiría
 * una migración y un segundo sitio del que puede desincronizarse.
 */
internal class PartialDownloadState(
    val validator: String?,
    val totalBytes: Long,
    segments: List<DownloadSegment>
) {
    /**
     * `CopyOnWriteArrayList` y no una lista normal: la recorren los tramos en vuelo para sumar el
     * progreso mientras el expansor puede estar añadiendo uno nuevo. Con una `ArrayList` eso es un
     * `ConcurrentModificationException` en el camino caliente de la descarga. Las escrituras son
     * rarísimas —una por split, con un tope de un puñado por archivo— así que copiar no cuenta.
     */
    val segments: MutableList<DownloadSegment> = CopyOnWriteArrayList(segments)

    val downloadedBytes: Long get() = segments.sumOf { it.confirmed }

    val isComplete: Boolean get() = segments.all { it.isComplete }

    /**
     * Añade un tramo recién separado, manteniendo el orden por posición: así el sidecar se guarda
     * ordenado y la reanudación coge siempre el hueco más temprano primero, que es el que permite
     * que el archivo se vaya completando de principio a fin.
     */
    fun addSegment(segment: DownloadSegment) {
        segments.add(segment)
        segments.sortBy { it.start }
    }

    fun serialize(): String = buildString {
        append(FORMAT_VERSION).append('\n')
        if (validator != null) append(KEY_VALIDATOR).append('=').append(validator).append('\n')
        append(KEY_TOTAL).append('=').append(totalBytes).append('\n')
        for (segment in segments) {
            append(segment.start).append(',')
                .append(segment.endExclusive).append(',')
                .append(segment.confirmed).append('\n')
        }
    }

    companion object {
        private const val TAG = "PartialDownload"

        /**
         * Versión del sidecar. Un formato desconocido no se intenta interpretar: se descarta el
         * parcial y se baja de cero, que siempre es correcto aunque cueste ancho de banda.
         */
        private const val FORMAT_VERSION = 1
        private const val KEY_VALIDATOR = "validator"
        private const val KEY_TOTAL = "total"

        /**
         * Estado guardado para [partFile], o `null` si no hay ninguno utilizable.
         *
         * Se descarta —en vez de intentar arreglarlo— ante cualquier señal de que el `.part` y el
         * sidecar no se corresponden: tamaño del archivo por debajo de lo que el sidecar dice haber
         * escrito, tramos que se salen del total o formato ilegible. Un parcial dudoso no ahorra
         * nada frente a un archivo de audio corrupto que solo se descubre al reproducirlo.
         */
        fun load(metaFile: File, partFile: File): PartialDownloadState? {
            if (!metaFile.exists() || !partFile.exists()) return null
            return try {
                val lines = metaFile.readLines().filter { it.isNotBlank() }
                if (lines.isEmpty() || lines[0].trim().toIntOrNull() != FORMAT_VERSION) return null

                var validator: String? = null
                var total = -1L
                val segments = mutableListOf<DownloadSegment>()
                for (line in lines.drop(1)) {
                    when {
                        line.startsWith("$KEY_VALIDATOR=") -> validator = line.substringAfter('=')
                        line.startsWith("$KEY_TOTAL=") ->
                            total = line.substringAfter('=').trim().toLongOrNull() ?: return null
                        else -> {
                            val parts = line.split(',')
                            if (parts.size != 3) return null
                            val start = parts[0].trim().toLongOrNull() ?: return null
                            val end = parts[1].trim().toLongOrNull() ?: return null
                            val confirmed = parts[2].trim().toLongOrNull() ?: return null
                            if (start < 0 || end < start || confirmed < 0 || start + confirmed > end) return null
                            segments.add(DownloadSegment(start, end, confirmed))
                        }
                    }
                }
                if (total <= 0L || segments.isEmpty()) return null
                // Los tramos tienen que cubrir el archivo ENTERO y sin huecos: nacen de partir uno
                // que iba de 0 a `total`, así que cualquier otra cosa significa que el sidecar no
                // describe este archivo. Sin esta comprobación, un hueco entre dos tramos daría un
                // archivo del tamaño correcto con basura en medio — que es el único fallo que el
                // control de tamaño del final no puede ver.
                if (segments.first().start != 0L) return null
                if (segments.last().endExclusive != total) return null
                for (i in 1 until segments.size) {
                    if (segments[i].start != segments[i - 1].endExclusive) return null
                }
                // El archivo tiene que ser al menos tan grande como el byte más alto ya escrito; si
                // se quedó corto (proceso muerto antes del flush), el sidecar miente y no sirve.
                val highestWritten = segments.maxOf { it.start + it.confirmed }
                if (partFile.length() < highestWritten) {
                    Log.w(TAG, "Sidecar por delante del archivo (${partFile.length()} < $highestWritten): se descarta")
                    return null
                }
                PartialDownloadState(validator, total, segments)
            } catch (e: Exception) {
                Log.w(TAG, "Sidecar ilegible en ${metaFile.name}: se descarta", e)
                null
            }
        }

        /** Borra los restos de una descarga parcial. Se llama cuando dejan de ser aprovechables. */
        fun discard(partFile: File, metaFile: File) {
            try { partFile.delete() } catch (_: Exception) {}
            try { metaFile.delete() } catch (_: Exception) {}
        }
    }
}

/**
 * Conexiones libres que una descarga puede tomar prestadas para bajarse a sí misma por varios
 * tramos a la vez.
 *
 * **Por qué existe.** OneDrive limita por CONEXIÓN, no por cuenta, así que el caudal del sync es
 * proporcional al número de descargas simultáneas. Cuando la cola se vacía y quedan dos archivos
 * grandes, quedan dos conexiones trabajando y treinta paradas: el final de cada sync masivo se
 * arrastra a ~1 MB/s con el enlace ocioso. Repartir esas conexiones ociosas DENTRO de los archivos
 * que quedan es lo que convierte ese arrastre en la misma velocidad que el resto de la corrida.
 *
 * **Solo se abre en el tramo final** (ver `SyncManager.processQueue`), y esa restricción es la que
 * mantiene esto simple: mientras el productor pueda servir canciones nuevas, un permiso prestado
 * habría que reclamarlo de vuelta —abandonando un tramo a medias— para no dejar sin conexión a la
 * canción que entra. Cerrada la cola no entra nadie más, así que lo que se presta no se devuelve
 * hasta que la descarga termina.
 */
interface ExtraConnectionBudget {
    /** Toma una conexión libre, o `false` si no hay. */
    fun tryAcquire(): Boolean

    /** Devuelve [count] conexiones tomadas con [tryAcquire]. */
    fun release(count: Int)
}
