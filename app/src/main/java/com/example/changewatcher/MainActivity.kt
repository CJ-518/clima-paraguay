package com.example.changewatcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/*
 * MainActivity.kt
 *
 * Actividad de entrada y pantalla principal, construidas con Jetpack Compose.
 *
 * La actividad elige los colores según los ajustes de apariencia y muestra una de dos pantallas:
 *  - [WatcherScreen]: el último aviso guardado (ya con formato), con botones para actualizar,
 *    copiar y abrir la página.
 *  - [SettingsScreen]: monitoreo, departamentos, intervalo y apariencia (ver SettingsScreen.kt).
 *
 * Cada vez que la app pasa a primer plano se lanza una revisión (refresh) y, al terminar,
 * se actualiza el último aviso mostrado. Con la app abierta no se envían notificaciones
 * (ver [Foreground] y [CheckWorker]).
 *
 * Esta pantalla no descarga nada por sí misma: lanza revisiones de [CheckWorker]
 * y lee el resultado desde [Prefs]. El formato del aviso lo aplica [AlertFormatter].
 */

/** Nombre único de la revisión al abrir la app o al pulsar Actualizar; si se lanza otra, reemplaza a la anterior. */
private const val REFRESH_WORK_NAME = "refresh_on_open"

/** Actividad de entrada (la que se abre desde el icono o desde la notificación). */
class MainActivity : ComponentActivity() {

    /** Sube cada vez que la app pasa a primer plano; la pantalla reacciona lanzando una revisión. */
    private var refreshTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val prefs = remember { Prefs(this) }
            // Ajustes de apariencia: al cambiarlos en Configuración, los colores se actualizan al instante
            var theme by remember { mutableStateOf(prefs.loadTheme()) }
            var showSettings by rememberSaveable { mutableStateOf(false) }

            MaterialTheme(colorScheme = appColorScheme(theme)) {
                Surface(Modifier.fillMaxSize()) {
                    // El botón Atrás del teléfono vuelve de Configuración a la pantalla principal
                    BackHandler(enabled = showSettings) { showSettings = false }
                    if (showSettings) {
                        SettingsScreen(
                            theme = theme,
                            onThemeChange = {
                                theme = it
                                prefs.saveTheme(it)
                            },
                            onBack = { showSettings = false },
                        )
                    } else {
                        WatcherScreen(refreshTick, onOpenSettings = { showSettings = true })
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshTick++
    }
}

/**
 * Pantalla principal: el último aviso guardado y sus botones.
 *
 * @param refreshTick contador que sube cada vez que la app vuelve a primer plano (0 = todavía no arrancó).
 * @param onOpenSettings abre la pantalla de configuración.
 */
@Composable
fun WatcherScreen(refreshTick: Int, onOpenSettings: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(ctx) }
    // Departamentos elegidos en Configuración; se usan para resaltarlos en el aviso
    val selected = remember { prefs.load().departments }

    var status by remember { mutableStateOf(prefs.lastStatus) }               // mensaje de estado
    var error by remember { mutableStateOf<String?>(null) }                   // error visible
    var checkId by remember { mutableStateOf<UUID?>(null) }                   // id de la revisión en curso
    var snapshotName by remember { mutableStateOf(prefs.lastSnapshot) }       // archivo del último aviso guardado
    var alertText by remember { mutableStateOf<String?>(null) }               // texto del último aviso (null = cargando)

    val askNotificationPermission = rememberNotificationPermissionAsker()

    /** Lanza una revisión (al abrir la app o con el botón Actualizar). Reemplaza a la anterior si sigue en curso. */
    fun startRefresh() {
        val request = OneTimeWorkRequestBuilder<CheckWorker>()
            .setInputData(workDataOf(CheckWorker.KEY_REFRESH to true))
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(REFRESH_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        // Se recuerda el id para que LaunchedEffect muestre el resultado al terminar
        checkId = request.id
        status = "Actualizando…"
    }

    // Carga el texto del último aviso guardado. Se repite cada vez que cambia el archivo.
    LaunchedEffect(snapshotName) {
        alertText = if (snapshotName.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                SnapshotStore.file(ctx, snapshotName)?.let { f ->
                    runCatching {
                        val content = f.readText()
                        // Lo guardado en versiones antiguas era HTML; lo nuevo ya es texto plano
                        if (snapshotName.endsWith(".html")) SnapshotStore.htmlToText(content) else content
                    }.getOrNull()
                } ?: ""
            }
        }
    }

    // Cuando termina una revisión, se muestra su resultado y el aviso más reciente.
    // Se observa el estado del trabajo en WorkManager; al terminar, se lee lo que dejó CheckWorker.
    LaunchedEffect(checkId) {
        val id = checkId ?: return@LaunchedEffect
        WorkManager.getInstance(ctx).getWorkInfoByIdFlow(id).collect { info ->
            if (info != null && info.state.isFinished) {
                status = prefs.lastStatus
                snapshotName = prefs.lastSnapshot
            }
        }
    }

    // Al entrar a la app (y cada vez que vuelve a primer plano) se hace un refresh con lo guardado en ajustes.
    LaunchedEffect(refreshTick) {
        if (refreshTick == 0) return@LaunchedEffect
        if (prefs.load().departments.isEmpty()) return@LaunchedEffect
        startRefresh()
    }

    // Colores del aviso tomados del tema: título, fenómeno esperado y departamentos elegidos
    val alertColors = AlertColors(
        title = MaterialTheme.colorScheme.primary.toArgb(),
        alert = MaterialTheme.colorScheme.error.toArgb(),
        selected = MaterialTheme.colorScheme.tertiary.toArgb(),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Último aviso guardado, con formato: siempre visible al entrar; se actualiza solo al terminar el refresh
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    snapshotName.isBlank() -> Text(
                        "Todavía no hay ningún aviso guardado. Se guarda cada aviso nuevo que publica la página.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    alertText == null -> Text("Cargando…", style = MaterialTheme.typography.bodyMedium)

                    alertText.isNullOrBlank() -> Text(
                        "No se pudo leer el aviso guardado.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    else -> {
                        val savedAt = SnapshotStore.savedAt(snapshotName)
                        val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("es-PY"))
                        Text(
                            "Guardado el " + (savedAt?.let { fmt.format(Date(it)) } ?: "fecha desconocida"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // El aviso con formato; los departamentos elegidos se resaltan con otro color de letra
                        val raw = alertText ?: ""
                        val formatted = remember(raw, selected, alertColors) {
                            AlertFormatter.format(raw, selected, alertColors)
                        }
                        // Color base del texto: el del contenido de la tarjeta, para que se lea en modo claro y oscuro
                        val textColor = LocalContentColor.current.toArgb()
                        AndroidView(
                            modifier = Modifier.fillMaxWidth(),
                            factory = { c ->
                                TextView(c).apply {
                                    textSize = 16f
                                    setLineSpacing(0f, 1.3f)
                                }
                            },
                            update = {
                                it.text = formatted
                                it.setTextColor(textColor)
                            },
                        )
                    }
                }

                // Botones: actualizar, copiar el aviso guardado y abrir la página en el navegador
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (prefs.load().departments.isEmpty()) {
                            error = "Selecciona al menos un departamento en Configuración"
                        } else {
                            error = null
                            askNotificationPermission()
                            startRefresh()
                        }
                    }) { Text("Actualizar") }

                    // Copia el aviso tal como se ve (sin colores ni tamaños) al portapapeles
                    OutlinedButton(
                        enabled = !alertText.isNullOrBlank(),
                        onClick = {
                            val text = AlertFormatter.format(alertText ?: "", selected, alertColors).toString()
                            ctx.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("Aviso meteorológico", text))
                            status = "Aviso copiado al portapapeles"
                        },
                    ) { Text("Copiar aviso") }
                }
                // Abrir la página y, al lado, el acceso a la configuración (icono de tuerca)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PAGE_URL)))
                    }) { Text("Abrir página") }

                    OutlinedButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Configuración")
                    }
                }
            }
        }

        // Resultado de la última acción o revisión
        Text(status, style = MaterialTheme.typography.bodyMedium)

        // Mensaje de error, si lo hay
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
