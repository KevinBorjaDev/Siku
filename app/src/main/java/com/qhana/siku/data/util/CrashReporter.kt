package com.qhana.siku.data.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.qhana.siku.BuildConfig
import com.qhana.siku.ui.screens.CrashReportActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Captura del fallo que mata el proceso, y su informe.
 *
 * **Por qué existe**: la app se distribuye por GitHub, sin Play Console y sin telemetría, así que
 * un "me crashea al abrir" llegaba SIN nada que mirar — el usuario no puede conectar `adb`, y
 * `AppLogger` solo vuelca a disco en debug (y su archivo, fuera del provider, no sale del
 * almacenamiento privado). En agosto de 2026 llegó exactamente ese reporte sobre la 1.2.0 y no
 * hubo forma de diagnosticarlo: esta clase es la respuesta a ese caso.
 *
 * **Se instala desde `Application.attachBaseContext`, no desde `onCreate`**, y eso es lo
 * load-bearing: entre las dos corre la inicialización de Hilt y la del propio `onCreate`, que es
 * donde vive el arranque más frágil (base de datos, healing, widgets). Instalado en `onCreate` no
 * podría informar del crash que más falta hace.
 *
 * El informe se enseña en una pantalla PROPIA ([CrashReportActivity]) lanzada desde el handler, y
 * no solo con una entrada en Ajustes: si la app no llega a abrir, a Ajustes no se entra, así que un
 * informe que solo viviera ahí no serviría para el único caso que motivó todo esto. La entrada de
 * Ajustes es la segunda puerta, para cuando el crash sí dejó volver a entrar.
 */
object CrashReporter {

    private const val DIR_NAME = "crash_reports"
    private const val FILE_PREFIX = "crash_"
    private const val FILE_SUFFIX = ".txt"

    /** Corta un informe del siguiente en el texto que se manda. */
    private const val SEPARATOR = "════════════════════"

    /**
     * Informes que se conservan; al escribir uno más se borra el más viejo.
     *
     * El criterio es **cuánto historial cabe en un mensaje sin volverlo ilegible**: los cinco se
     * mandan JUNTOS (ver [reportText]), y cada uno ronda un par de KB. Guardar más solo alargaría
     * el texto que el usuario tiene que pegar; guardar uno perdería el primer fallo de una cadena,
     * que suele ser el único que explica algo.
     */
    private const val MAX_REPORTS = 5

    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Volatile
    private var installed = false

    /**
     * Encadena el capturador al handler que hubiera (el del sistema, o el de otra librería).
     * Idempotente: instalarlo dos veces duplicaría el informe.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Nada de lo que pase aquí puede impedir que el crash se reporte como siempre: si esto
            // falla, se delega igual. Un capturador que se cae encima del fallo original dejaría al
            // usuario con MENOS información que sin él.
            val written = runCatching { writeReport(appContext, thread, error) }.isSuccess
            val shown = written &&
                runCatching { launchReportScreen(appContext) }.getOrDefault(false)

            if (shown) {
                // La pantalla ya va en camino en su propio proceso; matar éste evita que el sistema
                // encime su diálogo de "la app se ha detenido".
                //
                // Con la app en SEGUNDO PLANO (un worker que cae, el servicio) el sistema ignora el
                // lanzamiento sin avisar, así que aquí no se muestra nada y el fallo pasa callado.
                // Es lo correcto: un fallo de fondo no merece secuestrar la pantalla de quien está
                // en otra app, el informe queda escrito igual y Ajustes lo ofrece al volver.
                Process.killProcess(Process.myPid())
                exitProcess(10)
            } else {
                previous?.uncaughtException(thread, error) ?: Process.killProcess(Process.myPid())
            }
        }
    }

    /**
     * Todo lo capturado, del más reciente al más viejo, o cadena vacía si nunca hubo un fallo.
     *
     * Van TODOS y no solo el último, que es lo que hace que conservar [MAX_REPORTS] signifique
     * algo: en una cadena de fallos el informativo suele ser el PRIMERO —los de después son sus
     * secuelas—, así que quedarse con el último es quedarse justo con el que menos dice. Y un
     * informe guardado que nadie llega a leer no es diagnóstico, es un archivo de más.
     */
    fun reportText(context: Context): String =
        reports(context).joinToString("\n\n$SEPARATOR\n\n") { file ->
            runCatching { file.readText() }.getOrElse { "" }
        }

    /** ¿Hay algo que enseñar? Es lo que gatea la entrada de Ajustes. */
    fun hasReport(context: Context): Boolean = reports(context).isNotEmpty()

    private fun reports(context: Context): List<File> =
        reportsDir(context).listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /**
     * Manda el informe como TEXTO, sin adjunto.
     *
     * Adjuntarlo obligaba a abrir el FileProvider a este directorio —el mismo archivo que documenta
     * que la base de datos y los logs quedan fuera a propósito— y, con varios informes, a fabricar
     * un archivo combinado solo para poder adjuntar uno. El informe son un par de KB: en el cuerpo
     * del mensaje llega igual, se lee sin abrir nada y no expone ninguna ruta nueva.
     */
    fun shareReport(context: Context, chooserTitle: String, subject: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, reportText(context))
        }
        context.startActivity(
            Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * ¿Es éste el proceso de la app y no uno secundario? [CrashReportActivity] vive en `:crash` a
     * propósito, y sin esta guarda `Application.onCreate` levantaría ahí el puente de widgets, el
     * healing y el resto del arranque — o sea que la pantalla que informa del fallo podría
     * reproducirlo.
     */
    fun isMainProcess(context: Context): Boolean {
        val name = currentProcessName() ?: return true // sin dato, se asume el principal
        return name == context.packageName
    }

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        // API 26-27: sin API pública para esto. El cmdline del propio PID lo da sin reflexión, y
        // viene terminado en NUL, de ahí el recorte por caracteres de control.
        return runCatching { File("/proc/self/cmdline").readText().trim { it <= ' ' } }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    // ==================== Escritura ====================

    private fun writeReport(context: Context, thread: Thread, error: Throwable) {
        val dir = reportsDir(context).apply { mkdirs() }
        File(dir, "$FILE_PREFIX${fileNameFormat.format(Date())}$FILE_SUFFIX")
            .writeText(buildReport(thread, error))
        prune(dir)
    }

    /**
     * El informe lleva el CONTEXTO antes que el stack, y no al revés: lo primero que hay que
     * averiguar de un crash que le pasa a una sola persona es en qué dispositivo y con qué versión
     * de Android ocurre — que es justo lo que hay que pedir a mano cuando el informe no lo trae.
     * Nada de aquí identifica al usuario.
     */
    private fun buildReport(thread: Thread, error: Throwable): String = buildString {
        appendLine(
            "Siku Music ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}"
        )
        appendLine("Fecha: ${stampFormat.format(Date())}")
        appendLine()
        appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Build: ${Build.FINGERPRINT}")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Idioma: ${Locale.getDefault()}")
        appendLine("Hilo: ${thread.name}")
        appendLine()
        appendLine("--- Stack trace ---")
        append(stackTraceOf(error))
    }

    private fun stackTraceOf(error: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        return writer.toString()
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) } ?: return
        if (files.size <= MAX_REPORTS) return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_REPORTS)
            .forEach { runCatching { it.delete() } }
    }

    private fun reportsDir(context: Context): File = File(context.filesDir, DIR_NAME)

    // ==================== Pantalla ====================

    /**
     * Abre la pantalla del informe, y devuelve si de verdad se lanzó.
     *
     * **La guarda anti-bucle es el PROCESO, no un plazo.** El ciclo que hay que cortar es concreto:
     * que caiga el proceso `:crash` —el que abre esta misma pantalla— y su handler intente abrirla
     * otra vez, y otra. Eso no se distingue por CUÁNDO ocurre sino por DÓNDE, así que preguntarlo
     * es exacto donde un reloj solo acierta de casualidad. Aquí hubo una ventana de diez segundos y
     * no medía nada: además suprimía la pantalla en el caso legítimo de cerrarla, reabrir la app y
     * volver a caer enseguida.
     *
     * En `:crash` el informe se escribe igual y se delega en el handler del sistema.
     */
    private fun launchReportScreen(context: Context): Boolean {
        if (!isMainProcess(context)) return false

        val intent = Intent(context, CrashReportActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            putExtra(CrashReportActivity.EXTRA_FROM_CRASH, true)
        }
        context.startActivity(intent)
        return true
    }
}
