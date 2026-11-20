package com.example.changewatcher

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Muestra el texto del último aviso guardado. Se abre desde la notificación o desde "Ver último aviso guardado". */
class SnapshotActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = Prefs(this)
        val requested = intent.getStringExtra(EXTRA_FILE) ?: ""
        val url = (intent.getStringExtra(EXTRA_URL) ?: "").ifBlank { PAGE_URL }

        // Solo se conserva el último aviso: si el de esta notificación fue reemplazado, se muestra el más reciente
        val exact = SnapshotStore.file(this, requested)
        val latestName = prefs.lastSnapshot
        val file = exact ?: SnapshotStore.file(this, latestName)
        val name = if (exact != null) requested else latestName
        val replaced = exact == null && file != null
        val savedAt = SnapshotStore.savedAt(name)
        val cfg = prefs.load()

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = TextView(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(8))
            textSize = 14f
        }
        root.addView(header)

        val openLive = Button(this).apply {
            text = "Abrir página en vivo"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        root.addView(openLive)

        val body = TextView(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(16))
            textSize = 15f
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(body)
        }
        root.addView(scroll)

        if (file == null) {
            header.text = "Todavía no hay ningún aviso guardado."
        } else {
            val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("es-PY"))
            val savedText = savedAt?.let { fmt.format(Date(it)) } ?: "fecha desconocida"
            header.text = "Guardado el $savedText" +
                if (replaced) "\nEl aviso de esta notificación fue reemplazado por uno más reciente." else ""
            body.text = "Cargando…"

            // Leer y convertir el texto puede tardar un momento, así que se hace fuera del hilo principal
            Thread {
                val text = try {
                    val raw = file.readText()
                    // Lo guardado en versiones antiguas era HTML; lo nuevo ya es texto plano
                    if (name.endsWith(".html")) SnapshotStore.htmlToText(raw) else raw
                } catch (e: Exception) {
                    "No se pudo leer el aviso guardado: ${e.message ?: e.javaClass.simpleName}"
                }

                val spannable = SpannableString(text)

                // Resalta los títulos y las etiquetas del aviso
                styleTitles(spannable, text)

                // Resalta los departamentos elegidos donde aparezcan
                for (r in Departments.matchRanges(text, cfg.departments)) {
                    spannable.setSpan(
                        BackgroundColorSpan(Color.YELLOW),
                        r.first,
                        r.last + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                runOnUiThread {
                    body.text = if (text.isBlank()) {
                        "(No se guardó nada de texto.)"
                    } else {
                        spannable
                    }
                }
            }.start()
        }

        setContentView(root)
    }

    /**
     * Da formato a los títulos del aviso:
     * - la primera línea (el título principal) va grande, en negrita y en azul;
     * - la línea "Fenómeno esperado: ..." va en negrita, un poco más grande y en azul;
     * - las etiquetas como "Fecha:", "Hora de emisión:", "Zona de cobertura:" o "Departamentos afectados:" van en negrita y azul.
     */
    private fun styleTitles(spannable: SpannableString, text: String) {
        val titleColor = Color.parseColor("#0D47A1")

        fun style(start: Int, end: Int, size: Float) {
            if (end <= start) return
            spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(titleColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (size != 1f) {
                spannable.setSpan(RelativeSizeSpan(size), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        var offset = 0
        var isFirstLine = true
        for (line in text.split("\n")) {
            val start = offset
            val end = start + line.length
            offset = end + 1
            if (line.isBlank()) continue

            if (isFirstLine) {
                isFirstLine = false
                style(start, end, 1.4f)
                continue
            }

            if (SnapshotStore.fold(line).startsWith("fenomeno esperado")) {
                style(start, end, 1.15f)
                continue
            }

            // Etiqueta corta antes de los dos puntos, por ejemplo "Fecha:" o "Hora de emisión:"
            val colon = line.indexOf(':')
            if (colon in 1..40 && line.substring(0, colon).trim().split(" ").size <= 4) {
                style(start, start + colon + 1, 1f)
            }
        }
    }

    companion object {
        const val EXTRA_FILE = "snapshot_file"
        const val EXTRA_URL = "snapshot_url"
    }
}
