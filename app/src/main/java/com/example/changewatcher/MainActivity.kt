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

private const val WORK_NAME = "url_watcher"

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

@Composable
fun WatcherScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val initial = remember { prefs.load() }

    var selected by remember { mutableStateOf(initial.departments) }
    var interval by remember { mutableStateOf(initial.intervalMinutes.toString()) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var status by remember { mutableStateOf(prefs.lastStatus) }
    var error by remember { mutableStateOf<String?>(null) }
    var checkId by remember { mutableStateOf<UUID?>(null) }

    // Cuando termina una revisión manual, se muestra su resultado
    LaunchedEffect(checkId) {
        val id = checkId ?: return@LaunchedEffect
        WorkManager.getInstance(ctx).getWorkInfoByIdFlow(id).collect { info ->
            if (info != null && info.state.isFinished) {
                status = prefs.lastStatus
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* nada que hacer; el worker revisa el permiso antes de notificar */ }

    fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun currentConfig(isEnabled: Boolean) = WatchConfig(
        departments = selected,
        // El mínimo de WorkManager para tareas periódicas es 15 minutos
        intervalMinutes = (interval.toLongOrNull() ?: 15L).coerceAtLeast(15L),
        enabled = isEnabled,
    )

    fun validate(): String? = when {
        selected.isEmpty() -> "Selecciona al menos un departamento"
        else -> null
    }

    fun applySchedule(cfg: WatchConfig) {
        val wm = WorkManager.getInstance(ctx)
        if (cfg.enabled) {
            val request = PeriodicWorkRequestBuilder<CheckWorker>(cfg.intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
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
        Text("Clima Paraguay", style = MaterialTheme.typography.headlineMedium)

        Text(
            "Revisa $PAGE_URL y te avisa cuando el aviso incluye alguno de los departamentos marcados.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text("Departamentos a vigilar:", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { selected = Departments.all.map { it.id }.toSet() }) {
                Text("Marcar todos")
            }
            TextButton(onClick = { selected = emptySet() }) {
                Text("Desmarcar todos")
            }
        }

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

        OutlinedTextField(
            value = interval,
            onValueChange = { interval = it.filter(Char::isDigit) },
            label = { Text("Revisar cada (minutos, mínimo 15)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

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
                },
            )
            Text("  Monitoreo ${if (enabled) "ACTIVADO" else "DESACTIVADO"}")
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    checkId = request.id
                    status = "Revisando…"
                }
            }) { Text("Revisar ahora") }
        }

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

        Text(status, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Nota: Android hace las revisiones periódicas aproximadamente en el intervalo indicado; " +
                "la hora exacta depende de la batería y de la red.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
