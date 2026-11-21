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

/*
 * SnapshotActivity.kt
 *
 * Pantalla que muestra el texto del último aviso guardado. A diferencia de MainActivity,
 * se construye con vistas clásicas de Android (LinearLayout, TextView, etc.) en lugar de Compose.
 *
 * Estructura de la pantalla (de arriba a abajo):
 *  - encabezado con la fecha de guardado,
 *  - botón para abrir la página en vivo en el navegador,
 *  - cuerpo desplazable con el texto del aviso, con títulos con formato y departamentos resaltados.
 */

/**
 * Muestra el texto del último aviso guardado.
 * Se abre desde la notificación o desde el botón "Ver último aviso guardado".
 */
class SnapshotActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = Prefs(this)
        // Datos recibidos en el Intent: nombre del archivo del aviso y página de origen
        val requested = intent.getStringExtra(EXTRA_FILE) ?: ""
        val url = (intent.getStringExtra(EXTRA_URL) ?: "").ifBlank { PAGE_URL }

        // Solo se conserva el último aviso: si el de esta notificación fue reemplazado, se muestra el más reciente
        val exact = SnapshotStore.file(this, requested)
        val latestName = prefs.lastSnapshot
        val file = exact ?: SnapshotStore.file(this, latestName)
        val name = if (exact != null) requested else latestName
        val replaced = exact == null && file != null   // true si se está mostrando uno distinto al pedido
        val savedAt = SnapshotStore.savedAt(name)
        val cfg = prefs.load()

        // Función auxiliar para convertir dp a píxeles según la densidad de la pantalla
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // Contenedor vertical principal
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Encabezado: aquí se escribe la fecha de guardado o el mensaje de que no hay aviso
        val header = TextView(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(8))
            textSize = 14f
        }
        root.addView(header)

        // Botón que abre la página de avisos en el navegador
        val openLive = Button(this).apply {
            text = "Abrir página en vivo"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        root.addView(openLive)

        // Cuerpo con el texto del aviso (seleccionable) dentro de un ScrollView que ocupa el espacio restante
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
            // No existe ningún aviso guardado
            header.text = "Todavía no hay ningún aviso guardado."
        } else {
            // Fecha de guardado tomada del nombre del archivo, y aviso si el pedido fue reemplazado
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

                // SpannableString permite aplicar formato (color, negrita, fondo) a partes del texto
                val spannable = SpannableString(text)

                // Resalta los títulos y las etiquetas del aviso
                styleTitles(spannable, text)

                // Resalta los departamentos elegidos donde aparezcan
                for (r in Departments.matchRanges(text, cfg.departments)) {
                    spannable.setSpan(
                        BackgroundColorSpan(Color.YELLOW),
                        r.first,
                        r.last + 1, // el rango termina en r.last (inclusive); setSpan espera un final exclusivo
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                // Las vistas solo se pueden modificar desde el hilo principal
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
     * Da formato a los títulos del aviso, recorriendo el texto línea por línea:
     * - la primera línea (el título principal) va grande, en negrita y en azul;
     * - la línea "Fenómeno esperado: ..." va en negrita, un poco más grande y en azul;
     * - las etiquetas como "Fecha:", "Hora de emisión:", "Zona de cobertura:" o "Departamentos afectados:"
     *   van en negrita y azul (solo la etiqueta, hasta los dos puntos).
     */
    private fun styleTitles(spannable: SpannableString, text: String) {
        val titleColor = Color.parseColor("#0D47A1")

        /** Aplica negrita y color azul entre [start] y [end]; [size] es el tamaño relativo (1f = sin cambio). */
        fun style(start: Int, end: Int, size: Float) {
            if (end <= start) return
            spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(titleColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (size != 1f) {
                spannable.setSpan(RelativeSizeSpan(size), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // offset lleva la posición donde empieza cada línea dentro del texto completo
        var offset = 0
        var isFirstLine = true
        for (line in text.split("\n")) {
            val start = offset
            val end = start + line.length
            offset = end + 1 // +1 por el salto de línea
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
            // (hasta 40 caracteres y 4 palabras, para no tomar frases largas por etiquetas)
            val colon = line.indexOf(':')
            if (colon in 1..40 && line.substring(0, colon).trim().split(" ").size <= 4) {
                style(start, start + colon + 1, 1f)
            }
        }
    }

    companion object {
        /** Clave del Intent con el nombre del archivo del aviso a mostrar. */
        const val EXTRA_FILE = "snapshot_file"

        /** Clave del Intent con la dirección de la página de origen (para el botón "Abrir página en vivo"). */
        const val EXTRA_URL = "snapshot_url"
    }
}
