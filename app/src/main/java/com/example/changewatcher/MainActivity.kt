package com.example.changewatcher

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/*
 * MainActivity.kt
 *
 * Pantalla principal de la app, construida con Jetpack Compose.
 * Muestra el último aviso guardado (ya con formato) con un botón para actualizar y otro para abrir
 * la página, y debajo permite elegir los departamentos (en un menú desplegable que arranca cerrado),
 * el intervalo de revisión (15, 30, 45 o 60 minutos) y activar o desactivar el monitoreo.
 *
 * Cada vez que la app pasa a primer plano se lanza una revisión (refresh) y, al terminar,
 * se actualiza el último aviso mostrado. Con la app abierta no se envían notificaciones
 * (ver [Foreground] y [CheckWorker]).
 *
 * Esta pantalla no descarga nada por sí misma: programa las revisiones con [AlarmScheduler]
 * (alarmas exactas) y WorkManager (respaldo), lanza revisiones de [CheckWorker]
 * y lee el resultado desde [Prefs]. El formato del aviso lo aplica [AlertFormatter].
 */

/** Intervalos de revisión que se pueden elegir, en minutos (el mínimo de WorkManager es 15). */
private val INTERVAL_OPTIONS = listOf(15L, 30L, 45L, 60L)

/** Nombre único del trabajo periódico. Permite reemplazarlo o cancelarlo sin duplicarlo. */
private const val WORK_NAME = "url_watcher"

/** Nombre único de la revisión al abrir la app o al pulsar Actualizar; si se lanza otra, reemplaza a la anterior. */
private const val REFRESH_WORK_NAME = "refresh_on_open"

/** Actividad de entrada (la que se abre desde el icono o desde la notificación). Solo muestra [WatcherScreen]. */
class MainActivity : ComponentActivity() {

    /** Sube cada vez que la app pasa a primer plano; la pantalla reacciona lanzando una revisión. */
    private var refreshTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { WatcherScreen(refreshTick) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshTick++
    }
}

/**
 * Pantalla completa de configuración y control.
 *
 * Los valores editables (departamentos, intervalo, interruptor, estado) viven en variables de estado de Compose:
 * cuando cambian, la pantalla se redibuja sola. Al abrirse, se inicializan con lo guardado en [Prefs].
 *
 * @param refreshTick contador que sube cada vez que la app vuelve a primer plano (0 = todavía no arrancó).
 */
@Composable
fun WatcherScreen(refreshTick: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val initial = remember { prefs.load() }

    // Estado de la pantalla
    var selected by remember { mutableStateOf(initial.departments) }          // ids de departamentos marcados
    var interval by remember {                                                // intervalo elegido, en minutos
        // Si lo guardado no es una de las opciones (versiones anteriores), se usa la más cercana
        mutableStateOf(INTERVAL_OPTIONS.minByOrNull { abs(it - initial.intervalMinutes) } ?: 15L)
    }
    var enabled by remember { mutableStateOf(initial.enabled) }               // interruptor de monitoreo
    var status by remember { mutableStateOf(prefs.lastStatus) }               // mensaje de estado
    var error by remember { mutableStateOf<String?>(null) }                   // error de validación visible
    var checkId by remember { mutableStateOf<UUID?>(null) }                   // id de la revisión en curso
    var expanded by remember { mutableStateOf(false) }                        // menú de departamentos abierto o cerrado
    var snapshotName by remember { mutableStateOf(prefs.lastSnapshot) }       // archivo del último aviso guardado
    var alertText by remember { mutableStateOf<String?>(null) }               // texto del último aviso (null = cargando)

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

    // Lanzador para pedir el permiso de notificaciones (Android 13 o superior)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* nada que hacer; el worker revisa el permiso antes de notificar */ }

    /** Pide el permiso de notificaciones solo si hace falta (Android 13+ y aún no concedido). */
    fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Arma la configuración a partir de lo que hay en pantalla. */
    fun currentConfig(isEnabled: Boolean) = WatchConfig(
        departments = selected,
        intervalMinutes = interval,
        enabled = isEnabled,
    )

    /** Devuelve un mensaje de error si falta algo obligatorio, o null si todo está bien. */
    fun validate(): String? = when {
        selected.isEmpty() -> "Selecciona al menos un departamento"
        else -> null
    }

    /**
     * Programa o cancela las revisiones según [cfg].
     * Si está activado:
     *  - programa la alarma exacta de [AlarmScheduler], que es la principal y la más puntual;
     *  - mantiene además un trabajo periódico de WorkManager como respaldo (solo corre con conexión).
     *    Si ambos coinciden no hay avisos repetidos, porque [CheckWorker] compara el hash del aviso.
     * Si está desactivado, cancela la alarma y el trabajo periódico.
     */
    fun applySchedule(cfg: WatchConfig) {
        val wm = WorkManager.getInstance(ctx)
        if (cfg.enabled) {
            AlarmScheduler.schedule(ctx, cfg.intervalMinutes)
            val request = PeriodicWorkRequestBuilder<CheckWorker>(cfg.intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            AlarmScheduler.cancel(ctx)
            wm.cancelUniqueWork(WORK_NAME)
        }
    }

    // Texto que resume la selección mientras el menú está cerrado
    val summary = when {
        selected.isEmpty() -> "Ninguno seleccionado"
        selected.size == Departments.all.size -> "Todos (${Departments.all.size})"
        else -> Departments.byIds(selected).joinToString(", ") { it.label }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Clima Paraguay", style = MaterialTheme.typography.headlineMedium)

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
                        val formatted = remember(raw, selected) { AlertFormatter.format(raw, selected) }
                        AndroidView(
                            modifier = Modifier.fillMaxWidth(),
                            factory = { c ->
                                TextView(c).apply {
                                    textSize = 16f
                                    setLineSpacing(0f, 1.3f)
                                }
                            },
                            update = { it.text = formatted },
                        )
                    }
                }

                // Botones: actualizar, copiar el aviso guardado y abrir la página en el navegador
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val problem = validate()
                        if (problem != null) {
                            error = problem
                        } else {
                            error = null
                            askNotificationPermission()
                            // Guarda los ajustes tal como están y revisa; funciona aunque el monitoreo esté desactivado
                            prefs.save(currentConfig(enabled))
                            startRefresh()
                        }
                    }) { Text("Actualizar") }

                    // Copia el aviso tal como se ve (sin colores ni tamaños) al portapapeles
                    OutlinedButton(
                        enabled = !alertText.isNullOrBlank(),
                        onClick = {
                            val text = AlertFormatter.format(alertText ?: "", selected).toString()
                            ctx.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("Aviso meteorológico", text))
                            status = "Aviso copiado al portapapeles"
                        },
                    ) { Text("Copiar aviso") }
                }
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PAGE_URL)))
                }) { Text("Abrir página") }
            }
        }

        // Resultado de la última acción o revisión
        Text(status, style = MaterialTheme.typography.bodyMedium)

        // Mensaje de error de validación, si lo hay
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        // Selector de departamentos: minimizado hasta que se toca
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Departamentos a vigilar", style = MaterialTheme.typography.labelMedium)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(if (expanded) "▲" else "▼")
        }

        if (expanded) {
            // Atajos para marcar o desmarcar todos los departamentos
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selected = Departments.all.map { it.id }.toSet() }) {
                    Text("Marcar todos")
                }
                TextButton(onClick = { selected = emptySet() }) {
                    Text("Desmarcar todos")
                }
            }

            // Lista de departamentos con casilla; se puede tocar la fila entera o solo la casilla
            Column {
                Departments.all.forEach { d ->
                    val checked = d.id in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = if (checked) selected - d.id else selected + d.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on -> selected = if (on) selected + d.id else selected - d.id },
                        )
                        Text(d.label)
                    }
                }
            }
        }

        // Intervalo de revisión: se elige entre tiempos predefinidos (se aplica al pulsar Guardar)
        Column {
            Text("Revisar cada (minutos)", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                INTERVAL_OPTIONS.forEach { minutes ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { interval = minutes }
                            .padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = interval == minutes, onClick = { interval = minutes })
                        Text("$minutes")
                    }
                }
            }
        }

        // Interruptor de monitoreo: al activarlo se valida, se pide el permiso, se guarda y se programa el trabajo
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = enabled,
                onCheckedChange = { wantOn ->
                    if (wantOn) {
                        val problem = validate()
                        if (problem != null) {
                            error = problem
                            return@Switch
                        }
                        askNotificationPermission()
                    }
                    error = null
                    enabled = wantOn
                    val cfg = currentConfig(wantOn)
                    prefs.save(cfg)
                    // Al activar de nuevo, el aviso actual puede volver a guardarse y notificarse
                    prefs.lastAlertHash = ""
                    applySchedule(cfg)
                    // Android 12+: para alarmas exactas el usuario debe conceder el permiso en los ajustes.
                    // Mientras tanto se usa una alarma normal, que se vuelve exacta sola al concederlo.
                    if (wantOn && !AlarmScheduler.canScheduleExact(ctx)) {
                        status =
                            "Activa «Alarmas y recordatorios» para esta app y vuelve, para que las revisiones sean puntuales"
                        AlarmScheduler.openExactAlarmSettings(ctx)
                    }
                },
            )
            Text("  Monitoreo ${if (enabled) "ACTIVADO" else "DESACTIVADO"}")
        }

        // Guardar: guarda los ajustes y, si el monitoreo está activo, reprograma el trabajo con el nuevo intervalo
        Button(onClick = {
            val problem = validate()
            if (problem != null) {
                error = problem
            } else {
                error = null
                val cfg = currentConfig(enabled)
                prefs.save(cfg)
                if (enabled) applySchedule(cfg)
                status = "Ajustes guardados"
            }
        }) { Text("Guardar") }

        // Nota sobre la puntualidad de las revisiones
        Text(
            "Android hace las revisiones periódicas aproximadamente en el intervalo indicado; " +
                    "la hora exacta depende de la batería y de la red.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
