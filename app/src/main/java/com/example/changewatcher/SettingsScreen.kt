package com.example.changewatcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/*
 * SettingsScreen.kt
 *
 * Pantalla de configuración: monitoreo, departamentos a vigilar, intervalo de revisión, apariencia
 * y la sección «Acerca de», que muestra la versión instalada, busca versiones nuevas en los Releases
 * de GitHub (ver UpdateChecker.kt), las descarga e inicia su instalación, y tiene un botón para abrir
 * el repositorio del proyecto.
 * Los cambios de ajustes se guardan al instante (no hay botón Guardar).
 *
 * También contiene dos utilidades que usa esta pantalla y la principal:
 * [applySchedule] (programa o cancela las revisiones) y [rememberNotificationPermissionAsker].
 */

/** Intervalos de revisión que se pueden elegir, en minutos (el mínimo de WorkManager es 15). */
val INTERVAL_OPTIONS = listOf(15L, 30L, 45L, 60L)

/** Nombre único del trabajo periódico. Permite reemplazarlo o cancelarlo sin duplicarlo. */
private const val WORK_NAME = "url_watcher"

/**
 * Programa o cancela las revisiones según [cfg].
 * Si está activado:
 *  - programa la alarma exacta de [AlarmScheduler], que es la principal y la más puntual;
 *  - mantiene además un trabajo periódico de WorkManager como respaldo (solo corre con conexión).
 *    Si ambos coinciden no hay avisos repetidos, porque [CheckWorker] compara el hash del aviso.
 * Si está desactivado, cancela la alarma y el trabajo periódico.
 */
fun applySchedule(ctx: Context, cfg: WatchConfig) {
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

/**
 * Devuelve una función que pide el permiso de notificaciones solo si hace falta
 * (Android 13+ y aún no concedido). El worker revisa el permiso antes de notificar.
 */
@Composable
fun rememberNotificationPermissionAsker(): () -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(launcher) {
        {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/** Abre una dirección web en el navegador; si el teléfono no tiene ninguno, no hace nada. */
private fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** Fila con un botón de opción y su texto; se puede tocar toda la fila. */
@Composable
private fun OptionRow(selected: Boolean, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(label)
    }
}

/**
 * Pantalla de configuración.
 *
 * @param theme ajustes de apariencia actuales.
 * @param onThemeChange se llama con los ajustes nuevos cuando el usuario cambia la apariencia.
 * @param onBack vuelve a la pantalla principal.
 */
@Composable
fun SettingsScreen(theme: ThemeSettings, onThemeChange: (ThemeSettings) -> Unit, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val initial = remember { prefs.load() }

    var selected by remember { mutableStateOf(initial.departments) }          // ids de departamentos marcados
    var interval by remember {                                                // intervalo elegido, en minutos
        // Si lo guardado no es una de las opciones (versiones anteriores), se usa la más cercana
        mutableStateOf(INTERVAL_OPTIONS.minByOrNull { abs(it - initial.intervalMinutes) } ?: 15L)
    }
    var enabled by remember { mutableStateOf(initial.enabled) }               // interruptor de monitoreo
    var expanded by remember { mutableStateOf(false) }                        // menú de departamentos abierto o cerrado
    var message by remember { mutableStateOf<String?>(null) }                 // aviso informativo
    var error by remember { mutableStateOf<String?>(null) }                   // error de validación

    val askNotificationPermission = rememberNotificationPermissionAsker()

    // ---- Actualizaciones (ver UpdateChecker.kt) ----
    val scope = rememberCoroutineScope()
    val currentVersion = remember { Updater.currentVersionName(ctx) }         // versión instalada
    var checking by remember { mutableStateOf(false) }                        // true = consultando GitHub
    var update by remember { mutableStateOf<ReleaseInfo?>(null) }             // versión nueva encontrada
    var updateMessage by remember { mutableStateOf<String?>(null) }           // resultado o error de la actualización
    var downloading by remember { mutableStateOf(false) }                     // true = descargando el APK
    var progress by remember { mutableFloatStateOf(-1f) }                     // avance 0..1 (-1 = no se conoce)
    var downloadedFile by remember { mutableStateOf<File?>(null) }            // APK ya descargado, listo para instalar

    // Al abrir la pantalla se borran los APK de descargas anteriores que ya no sirven
    LaunchedEffect(Unit) { Updater.cleanObsolete(ctx) }

    /** Abre el instalador de Android; si falta el permiso de instalar apps, lleva a concederlo. */
    fun startInstall(file: File) {
        if (!Updater.canInstall(ctx)) {
            updateMessage = "Permite a Clima Paraguay instalar aplicaciones, vuelve aquí y toca «Instalar»."
            runCatching { Updater.openInstallPermissionSettings(ctx) }
            return
        }
        runCatching { Updater.installApk(ctx, file) }.onFailure {
            updateMessage = "No se pudo abrir el instalador: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    /** Consulta en GitHub si hay una versión más nueva que la instalada. */
    fun checkUpdates() {
        if (checking || downloading) return
        checking = true
        update = null
        downloadedFile = null
        updateMessage = null
        scope.launch {
            when (val result = Updater.checkForUpdate(ctx)) {
                is UpdateResult.Available -> update = result.release
                is UpdateResult.UpToDate ->
                    updateMessage = "Ya tienes la última versión (${result.release.versionName})."

                UpdateResult.NoReleases ->
                    updateMessage = "Todavía no hay versiones publicadas en GitHub."

                is UpdateResult.Failed ->
                    updateMessage = "No se pudo buscar actualizaciones: ${result.reason}."
            }
            checking = false
        }
    }

    /** Descarga el APK del release y, al terminar, abre el instalador (si la app sigue a la vista). */
    fun startDownload(release: ReleaseInfo) {
        if (checking || downloading) return
        downloading = true
        progress = -1f
        updateMessage = null
        scope.launch {
            try {
                val file = Updater.downloadApk(ctx, release) { progress = it }
                downloading = false
                downloadedFile = file
                if (Foreground.isActive) {
                    startInstall(file)
                } else {
                    updateMessage = "Descarga lista. Toca «Instalar» para continuar."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateMessage = "La descarga falló: ${Updater.friendlyError(e)}."
            } finally {
                downloading = false
            }
        }
    }

    /** Cambia los departamentos y guarda al instante. */
    fun setSelected(new: Set<String>) {
        selected = new
        error = null
        prefs.save(WatchConfig(new, interval, enabled))
    }

    /** Cambia el intervalo, lo guarda y, si el monitoreo está activo, reprograma las revisiones. */
    fun setInterval(minutes: Long) {
        interval = minutes
        val cfg = WatchConfig(selected, minutes, enabled)
        prefs.save(cfg)
        if (enabled) applySchedule(ctx, cfg)
    }

    // Texto que resume la selección mientras el menú está cerrado
    val summary = when {
        selected.isEmpty() -> "Ninguno seleccionado"
        selected.size == Departments.all.size -> "Todos (${Departments.all.size})"
        else -> Departments.byIds(selected).joinToString(", ") { it.label }
    }

    val dynamicSupported = Build.VERSION.SDK_INT >= 31
    val dynamicOn = theme.dynamic && dynamicSupported

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Volver") }
        Text("Configuración", style = MaterialTheme.typography.headlineMedium)

        // ---- Monitoreo ----
        Text("Monitoreo", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = enabled,
                onCheckedChange = { wantOn ->
                    if (wantOn) {
                        if (selected.isEmpty()) {
                            error = "Selecciona al menos un departamento"
                            return@Switch
                        }
                        askNotificationPermission()
                    }
                    error = null
                    enabled = wantOn
                    val cfg = WatchConfig(selected, interval, wantOn)
                    prefs.save(cfg)
                    // Al activar de nuevo, el aviso actual puede volver a guardarse y notificarse
                    prefs.lastAlertHash = ""
                    applySchedule(ctx, cfg)
                    // Android 12+: para alarmas exactas el usuario debe conceder el permiso en los ajustes.
                    // Mientras tanto se usa una alarma normal, que se vuelve exacta sola al concederlo.
                    message = null
                    if (wantOn && !AlarmScheduler.canScheduleExact(ctx)) {
                        message =
                            "Activa «Alarmas y recordatorios» para esta app y vuelve, para que las revisiones sean puntuales"
                        AlarmScheduler.openExactAlarmSettings(ctx)
                    }
                },
            )
            Text("  Monitoreo ${if (enabled) "ACTIVADO" else "DESACTIVADO"}")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        // ---- Departamentos: minimizado hasta que se toca ----
        Text("Departamentos", style = MaterialTheme.typography.titleMedium)
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
                TextButton(onClick = { setSelected(Departments.all.map { it.id }.toSet()) }) {
                    Text("Marcar todos")
                }
                TextButton(onClick = { setSelected(emptySet()) }) {
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
                            .clickable { setSelected(if (checked) selected - d.id else selected + d.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on -> setSelected(if (on) selected + d.id else selected - d.id) },
                        )
                        Text(d.label)
                    }
                }
            }
        }
        if (enabled && selected.isEmpty()) {
            Text(
                "Sin departamentos seleccionados no se hacen revisiones.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ---- Intervalo de revisión: tiempos predefinidos ----
        Text("Intervalo de revisión", style = MaterialTheme.typography.titleMedium)
        Column {
            Text("Revisar cada (minutos)", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                INTERVAL_OPTIONS.forEach { minutes ->
                    OptionRow(selected = interval == minutes, label = "$minutes") { setInterval(minutes) }
                }
            }
        }
        Text(
            "Android hace las revisiones periódicas aproximadamente en el intervalo indicado; " +
                    "la hora exacta depende de la batería y de la red.",
            style = MaterialTheme.typography.bodySmall,
        )

        // ---- Apariencia ----
        Text("Apariencia", style = MaterialTheme.typography.titleMedium)
        Column {
            Text("Colores", style = MaterialTheme.typography.labelMedium)
            OptionRow(
                selected = dynamicOn,
                label = if (dynamicSupported) "Dinámico (colores del teléfono)" else "Dinámico (requiere Android 12)",
                enabled = dynamicSupported,
            ) { onThemeChange(theme.copy(dynamic = true)) }
            OptionRow(selected = !dynamicOn, label = "Manual") { onThemeChange(theme.copy(dynamic = false)) }
        }

        // Color del tema manual: círculos de colores; el elegido lleva un borde
        if (!dynamicOn) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = ACCENTS.firstOrNull { it.id == theme.accent } ?: ACCENTS[0]
                Text("Color: ${current.label}", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ACCENTS.forEach { a ->
                        val isSelected = a.id == current.id
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(a.primaryLight)
                                .clickable { onThemeChange(theme.copy(accent = a.id)) }
                                .then(
                                    if (isSelected) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    }
                                ),
                        )
                    }
                }
            }
        }

        Column {
            Text("Modo", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemeMode.values().forEach { m ->
                    OptionRow(selected = theme.mode == m, label = m.label) { onThemeChange(theme.copy(mode = m)) }
                }
            }
        }

        // ---- Acerca de ----
        Text("Acerca de", style = MaterialTheme.typography.titleMedium)
        Text("Clima Paraguay", style = MaterialTheme.typography.bodyLarge)
        Text("Versión actual: $currentVersion", style = MaterialTheme.typography.bodyMedium)

        // Búsqueda de actualizaciones en los Releases de GitHub
        OutlinedButton(
            enabled = !checking && !downloading,
            onClick = { checkUpdates() },
        ) { Text(if (checking) "Buscando…" else "Buscar actualizaciones") }
        if (checking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        updateMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        // Versión nueva encontrada: notas, descarga con progreso e instalación
        update?.let { release ->
            val shape = RoundedCornerShape(12.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .border(1.dp, MaterialTheme.colorScheme.primary, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Nueva versión disponible: ${release.versionName}", style = MaterialTheme.typography.titleSmall)
                Text("Tienes la versión $currentVersion.", style = MaterialTheme.typography.bodySmall)
                if (release.notes.isNotBlank()) {
                    Text(
                        release.notes.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val ready = downloadedFile
                when {
                    downloading -> {
                        Text(
                            if (progress >= 0f) "Descargando… ${(progress * 100).toInt()} %" else "Descargando…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (progress >= 0f) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            "Mantén esta pantalla abierta hasta que termine.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    ready != null -> Button(onClick = { startInstall(ready) }) {
                        Text("Instalar versión ${release.versionName}")
                    }

                    release.apkUrl != null -> Button(onClick = { startDownload(release) }) {
                        val size = if (release.apkSize > 0) " (${Updater.formatSize(release.apkSize)})" else ""
                        Text("Descargar e instalar$size")
                    }

                    else -> Text(
                        "Este release no incluye un archivo APK; puedes verlo en GitHub.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                TextButton(onClick = { openUrl(ctx, release.pageUrl) }) { Text("Ver release en GitHub") }
            }
        }

        OutlinedButton(onClick = { openUrl(ctx, REPO_URL) }) { Text("Ver repositorio en GitHub") }
    }
}
