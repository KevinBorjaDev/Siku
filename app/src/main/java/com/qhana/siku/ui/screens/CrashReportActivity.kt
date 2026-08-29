package com.qhana.siku.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.qhana.siku.MainActivity
import com.qhana.siku.R
import com.qhana.siku.data.util.CrashReporter

/**
 * Pantalla del informe de fallo: la lanza [CrashReporter] desde el handler, con el proceso ya
 * muriendo.
 *
 * **Deliberadamente primitiva: Views a mano, sin Compose, sin Hilt y sin el tema de la app.** Es la
 * pantalla que tiene que funcionar cuando lo demás no funcionó, así que no puede depender de lo
 * mismo que acaba de fallar — un crash en la resolución de fuentes, en el tema o en el grafo de
 * dependencias también se llevaría a la pantalla que iba a informar de él. Por el mismo motivo vive
 * en el proceso `:crash` (ver el manifest) y `MusicPlayerApp.onCreate` se aparta ahí.
 *
 * No ofrece "reiniciar la app" como acción principal a propósito: si el fallo es del arranque,
 * reintentar solo devuelve al usuario al mismo sitio. Lo que sí ofrece es la única acción que
 * cambia algo — mandar el informe.
 */
class CrashReportActivity : Activity() {

    companion object {
        /**
         * Marca que la pantalla la abrió el handler y no Ajustes. Decide qué significa "atrás": tras
         * un crash la tarea se lanzó con `CLEAR_TASK` y no hay nada detrás, así que se vuelve a la
         * app; abierta desde Ajustes, la app sigue viva detrás y volver es sencillamente salir.
         */
        const val EXTRA_FROM_CRASH = "from_crash"

        private const val PADDING_DP = 24
        private const val GAP_DP = 16
        private const val REPORT_TEXT_SP = 11f
    }

    private var fromCrash = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fromCrash = intent?.getBooleanExtra(EXTRA_FROM_CRASH, false) == true
        setContentView(buildLayout(CrashReporter.reportText(this)))
    }

    private fun buildLayout(report: String): View {
        val pad = dp(PADDING_DP)
        val gap = dp(GAP_DP)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.crash_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.crash_message)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, gap, 0, gap)
        })

        // El informe se ENSEÑA, y es seleccionable: el usuario ve exactamente qué manda, y puede
        // copiarlo a mano si ningún destino del chooser le sirve.
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            addView(TextView(this@CrashReportActivity).apply {
                text = report
                setTextIsSelectable(true)
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, REPORT_TEXT_SP)
            })
        })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, gap, 0, 0)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

            addView(Button(this@CrashReportActivity).apply {
                text = getString(R.string.crash_close)
                setOnClickListener { close() }
            })
            addView(Button(this@CrashReportActivity).apply {
                text = getString(R.string.crash_share)
                isEnabled = report.isNotBlank()
                setOnClickListener { share() }
            })
        })

        return root
    }

    private fun share() {
        runCatching {
            CrashReporter.shareReport(
                context = this,
                chooserTitle = getString(R.string.crash_share),
                subject = getString(R.string.crash_share_subject)
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = close()

    /**
     * Salir de aquí tras un crash devuelve a la app: la tarea se lanzó con `CLEAR_TASK` y detrás no
     * queda nada, así que un `finish()` a secas dejaría al usuario en el escritorio sin haber
     * pedido salir. Abierta desde Ajustes no hace falta: la app sigue viva detrás.
     */
    private fun close() {
        if (fromCrash) {
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            }
        }
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
