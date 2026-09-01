package com.qhana.siku.data.util

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.qhana.siku.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sistema de logging interno para diagnosticar problemas de reproduccion.
 * Guarda logs en un archivo interno de la app con rotacion automatica.
 *
 * La persistencia va por un [Channel] con UN solo consumidor que mantiene un
 * [BufferedWriter] abierto y flushea por lote (≥50 entradas o ≥5s). Antes, cada
 * log() lanzaba una corrutina y abría/escribía/cerraba el archivo por línea, lo
 * que provocaba I/O constante durante toda la reproducción.
 *
 * **El volcado a disco es SOLO en debug** ([BuildConfig.DEBUG]): en release no existe ninguna
 * pantalla ni exportación que lea `playback_debug.log`, así que sin root el archivo es inaccesible
 * — escribirlo sería I/O de por vida (con el servicio de reproducción el proceso vive días) para un
 * archivo que nadie puede abrir. En release ni se arranca el consumidor ni se encola nada; el
 * logcat, en cambio, se emite siempre (es barato y sí se puede capturar con la app conectada). Si
 * algún día se añade una pantalla de diagnóstico, el gate pasa a esa preferencia.
 */
@Singleton
class AppLogger @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AppLogger"
        private const val LOG_FILE_NAME = "playback_debug.log"
        private const val MAX_LOG_SIZE_BYTES = 512 * 1024 // 512 KB
        private const val FLUSH_INTERVAL_MS = 5000L
        private const val FLUSH_THRESHOLD = 50

        const val CAT_PLAYBACK = "PLAYBACK"
        const val CAT_LIFECYCLE = "LIFECYCLE"
        const val CAT_CONTROLLER = "CONTROLLER"
        const val CAT_ERROR = "ERROR"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private val logFile: File by lazy {
        File(context.filesDir, LOG_FILE_NAME)
    }

    // Buffer acotado: si el consumidor no da abasto, se descartan las entradas más viejas
    // (preferimos perder líneas de diagnóstico antes que bloquear el hilo que loguea).
    private val channel = Channel<LogEntry>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        // Sin lector en release, el consumidor —y con él el writer y toda la I/O— solo existe en debug.
        if (BuildConfig.DEBUG) scope.launch { consumeLoop() }
    }

    fun log(category: String, message: String, level: LogLevel = LogLevel.INFO) {
        val timestamp = System.currentTimeMillis()
        val entry = LogEntry(timestamp, category, message, level)

        // Logcat síncrono, tal cual estaba.
        val logTag = "AppLog/$category"
        when (level) {
            LogLevel.DEBUG -> Log.d(logTag, message)
            LogLevel.INFO -> Log.i(logTag, message)
            LogLevel.WARN -> Log.w(logTag, message)
            LogLevel.ERROR -> Log.e(logTag, message)
        }

        // Persistencia: solo encolar (sin lanzar corrutinas por llamada), y solo en debug — en
        // release no hay consumidor que vacíe el canal ni archivo que nadie pueda leer.
        if (BuildConfig.DEBUG) channel.trySend(entry)
    }

    fun playback(message: String, level: LogLevel = LogLevel.INFO) = log(CAT_PLAYBACK, message, level)
    fun lifecycle(message: String, level: LogLevel = LogLevel.INFO) = log(CAT_LIFECYCLE, message, level)
    fun controller(message: String, level: LogLevel = LogLevel.INFO) = log(CAT_CONTROLLER, message, level)
    fun error(message: String) = log(CAT_ERROR, message, LogLevel.ERROR)

    /**
     * Único consumidor del canal: mantiene el writer abierto y flushea por lote.
     * Flush cuando pasaron ≥5s desde el último flush O hay ≥50 entradas acumuladas.
     *
     * La espera es BLOQUEANTE cuando no hay nada pendiente de volcar, y solo con plazo cuando sí
     * lo hay. La versión anterior ponía el timeout siempre, así que sin actividad el bucle
     * despertaba cada 5 s para comprobar que no había nada que hacer y volver a dormirse —
     * unos 17 000 despertares al día en un proceso que, por el servicio de reproducción, vive
     * días enteros. Con el buffer vacío no existe ningún plazo que vigilar: lo único que puede
     * dar trabajo es que llegue una entrada, y eso ya es una señal (convención 13).
     */
    private suspend fun consumeLoop() {
        var writer: BufferedWriter? = openWriter()
        var pending = 0
        // Monótono: el flush por tiempo es un intervalo, no una hora (la hora de cada línea la pone
        // `timestamp`, que sí es wall-clock porque se escribe en el archivo). Ver ListeningClock.
        var lastFlush = SystemClock.elapsedRealtime()
        try {
            while (true) {
                val entry = if (pending == 0) {
                    // Nada escrito sin volcar: dormir hasta que alguien loguee.
                    channel.receive()
                } else {
                    // Hay líneas en el buffer: despertar a más tardar cuando toque el flush por
                    // tiempo, para que no se queden ahí si el logueo se detiene de golpe.
                    val elapsed = SystemClock.elapsedRealtime() - lastFlush
                    val remaining = (FLUSH_INTERVAL_MS - elapsed).coerceAtLeast(0L)
                    withTimeoutOrNull(remaining) { channel.receive() }
                }
                if (entry != null) {
                    try {
                        writer?.write(formatEntryForFile(entry))
                        writer?.newLine()
                        pending++
                    } catch (e: Exception) {
                        Log.e(TAG, "Error writing to log buffer: ${e.message}")
                    }
                }

                val now = SystemClock.elapsedRealtime()
                val shouldFlush = pending > 0 &&
                    (pending >= FLUSH_THRESHOLD || now - lastFlush >= FLUSH_INTERVAL_MS)
                if (shouldFlush) {
                    try {
                        writer?.flush()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error flushing log file: ${e.message}")
                    }
                    pending = 0
                    lastFlush = now

                    // Rotación tras flush (la longitud del archivo ya refleja lo escrito).
                    if (logFile.length() > MAX_LOG_SIZE_BYTES) {
                        try { writer?.close() } catch (_: Exception) {}
                        rotateLogFile()
                        writer = openWriter()
                    }
                }
            }
        } finally {
            // Garantiza vaciar el buffer si el scope muere.
            try {
                writer?.flush()
                writer?.close()
            } catch (_: Exception) {}
        }
    }

    private fun openWriter(): BufferedWriter? {
        return try {
            BufferedWriter(FileWriter(logFile, /* append = */ true))
        } catch (e: Exception) {
            Log.e(TAG, "Error opening log file: ${e.message}")
            null
        }
    }

    private fun formatEntryForFile(entry: LogEntry): String {
        val time = fileDateFormat.format(Date(entry.timestamp))
        val levelChar = when (entry.level) {
            LogLevel.DEBUG -> "D"
            LogLevel.INFO -> "I"
            LogLevel.WARN -> "W"
            LogLevel.ERROR -> "E"
        }
        return "$time [$levelChar/${entry.category}] ${entry.message}"
    }

    private fun rotateLogFile() {
        try {
            val backupFile = File(context.filesDir, "playback_debug_old.log")
            if (backupFile.exists()) backupFile.delete()
            logFile.renameTo(backupFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error rotating log file: ${e.message}")
        }
    }
}

data class LogEntry(
    val timestamp: Long,
    val category: String,
    val message: String,
    val level: LogLevel
)

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}
