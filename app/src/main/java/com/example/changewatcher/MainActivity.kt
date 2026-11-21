package com.example.changewatcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit

/*
 * MainActivity.kt
 *
 * Pantalla principal de la app, construida con Jetpack Compose.
 * Permite elegir los departamentos, el intervalo de revisión, activar o desactivar el monitoreo,
 * lanzar una revisión manual y abrir el último aviso guardado.
 *
 * Esta pantalla no descarga nada por sí misma: programa las revisiones con [AlarmScheduler]
 * (alarmas exactas) y WorkManager (respaldo), lanza revisiones manuales de [CheckWorker]
 * y lee el resultado desde [Prefs].
 */

/** Nombre único del trabajo periódico. Permite reemplazarlo o cancelarlo sin duplicarlo. */
private const val WORK_NAME = "url_watcher"

/** Actividad de entrada (la que se abre desde el icono). Solo muestra [WatcherScreen]. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { WatcherScreen() }
            }
        }
    }
}

/**
 * Pantalla completa de configuración y control.
 *
 * Los valores editables (departamentos, intervalo, interruptor, estado) viven en variables de estado de Compose:
 * cuando cambian, la pantalla se redibuja sola. Al abrirse, se inicializan con lo guardado en [Prefs].
 */
@Composable
fun WatcherScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val initial = remember { prefs.load() }

    // Estado de la pantalla
    var selected by remember { mutableStateOf(initial.departments) }          // ids de departamentos marcados
    var interval by remember { mutableStateOf(initial.intervalMinutes.toString()) } // texto del campo de minutos
    var enabled by remember { mutableStateOf(initial.enabled) }               // interruptor de monitoreo
    var status by remember { mutableStateOf(prefs.lastStatus) }               // mensaje inferior
    var error by remember { mutableStateOf<String?>(null) }                   // error de validación visible
    var checkId by remember { mutableStateOf<UUID?>(null) }                   // id de la revisión manual en curso

    // Cuando termina una revisión manual, se muestra su resultado.
    // Se observa el estado del trabajo en WorkManager; al terminar, se lee el mensaje que dejó CheckWorker.
    LaunchedEffect(checkId) {
        val id = checkId ?: return@LaunchedEffect
        WorkManager.getInstance(ctx).getWorkInfoByIdFlow(id).collect { info ->
            if (info != null && info.state.isFinished) {
                status = prefs.lastStatus
            }
        }
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
        // El mínimo de WorkManager para tareas periódicas es 15 minutos
        intervalMinutes = (interval.toLongOrNull() ?: 15L).coerceAtLeast(15L),
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Encabezado y explicación
        Text("Clima Paraguay", style = MaterialTheme.typography.headlineMedium)

        Text(
            "Revisa $PAGE_URL y te avisa cuando el aviso incluye alguno de los departamentos marcados.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text("Departamentos a vigilar:", style = MaterialTheme.typography.titleMedium)

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

        // Intervalo de revisión: solo acepta dígitos; el mínimo de 15 se aplica en currentConfig()
        OutlinedTextField(
            value = interval,
            onValueChange = { interval = it.filter(Char::isDigit) },
            label = { Text("Revisar cada (minutos, mínimo 15)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

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
                    // Al activar de nuevo, el aviso actual puede volver a notificarse
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

        // Mensaje de error de validación, si lo hay
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

            // Revisar ahora: lanza una única revisión inmediata, marcada como manual
            Button(onClick = {
                val problem = validate()
                if (problem != null) {
                    error = problem
                } else {
                    error = null
                    askNotificationPermission()
                    // Guarda los ajustes tal como están; la revisión manual ignora el interruptor de monitoreo
                    prefs.save(currentConfig(enabled))
                    val request = OneTimeWorkRequestBuilder<CheckWorker>()
                        .setInputData(workDataOf(CheckWorker.KEY_MANUAL to true))
                        .build()
                    WorkManager.getInstance(ctx).enqueue(request)
                    // Se recuerda el id para que LaunchedEffect muestre el resultado al terminar
                    checkId = request.id
                    status = "Revisando…"
                }
            }) { Text("Revisar ahora") }
        }

        // Abre SnapshotActivity con el último aviso guardado; si no hay ninguno, lo indica en el estado
        OutlinedButton(onClick = {
            val name = prefs.lastSnapshot
            if (name.isBlank()) {
                status = "Todavía no hay ningún aviso guardado. Se guarda uno cada vez que se envía una notificación."
            } else {
                ctx.startActivity(
                    Intent(ctx, SnapshotActivity::class.java)
                        .putExtra(SnapshotActivity.EXTRA_FILE, name)
                        .putExtra(SnapshotActivity.EXTRA_URL, prefs.lastSnapshotUrl)
                )
            }
        }) { Text("Ver último aviso guardado") }

        // Resultado de la última acción o revisión, y nota sobre la puntualidad de las revisiones
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Nota: Android hace las revisiones periódicas aproximadamente en el intervalo indicado; " +
                    "la hora exacta depende de la batería y de la red.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
