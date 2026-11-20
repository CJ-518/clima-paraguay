package com.example.changewatcher

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shows the saved page text. Opened from the notification or from the "View last saved page" button. */
class SnapshotActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val name = intent.getStringExtra(EXTRA_FILE) ?: ""
        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        val file = SnapshotStore.file(this, name)
        val savedAt = SnapshotStore.savedAt(name)
        val cfg = Prefs(this).load()
        val retentionDays = cfg.retentionDays.coerceAtLeast(1)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = TextView(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(8))
            textSize = 14f
        }
        root.addView(header)

        if (url.isNotBlank()) {
            val openLive = Button(this).apply {
                text = "Open live page"
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
            root.addView(openLive)
        }

        val body = TextView(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(16))
            textSize = 15f
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
            header.text = "This saved text is no longer available. " +
                "Saved pages are deleted after the retention period."
        } else {
            val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
            val savedText = savedAt?.let { fmt.format(Date(it)) } ?: "unknown time"
            val untilText = savedAt?.let {
                fmt.format(Date(it + retentionDays * 24L * 60 * 60 * 1000))
            } ?: "?"
            val baseHeader = "Saved on $savedText (kept until $untilText)"
            header.text = baseHeader
            body.text = "Loading…"

            // Reading and converting can take a moment, so do it off the main thread
            Thread {
                val text = try {
                    val raw = file.readText()
                    // Older saves were stored as raw HTML; newer ones are already plain text
                    if (name.endsWith(".html")) SnapshotStore.htmlToText(raw) else raw
                } catch (e: Exception) {
                    "Could not read the saved text: ${e.message ?: e.javaClass.simpleName}"
                }

                // Highlight the watched text wherever it appears
                val spannable = SpannableString(text)
                var matches = 0
                if (cfg.text.isNotBlank()) {
                    var idx = text.indexOf(cfg.text, 0, ignoreCase = cfg.ignoreCase)
                    while (idx >= 0 && matches < 500) {
                        spannable.setSpan(
                            BackgroundColorSpan(Color.YELLOW),
                            idx,
                            idx + cfg.text.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        matches++
                        idx = text.indexOf(cfg.text, idx + cfg.text.length, ignoreCase = cfg.ignoreCase)
                    }
                }

                runOnUiThread {
                    body.text = if (text.isBlank()) {
                        "(Nothing was saved. The page may be built with JavaScript.)"
                    } else {
                        spannable
                    }
                }
            }.start()
        }

        setContentView(root)
    }

    companion object {
        const val EXTRA_FILE = "snapshot_file"
        const val EXTRA_URL = "snapshot_url"
    }
}
