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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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

    var url by remember { mutableStateOf(initial.url) }
    var text by remember { mutableStateOf(initial.text) }
    var mode by remember { mutableStateOf(initial.mode) }
    var interval by remember { mutableStateOf(initial.intervalMinutes.toString()) }
    var retention by remember { mutableStateOf(initial.retentionDays.toString()) }
    var saveFrom by remember { mutableStateOf(initial.saveFrom) }
    var saveUntil by remember { mutableStateOf(initial.saveUntil) }
    var ignoreCase by remember { mutableStateOf(initial.ignoreCase) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var status by remember { mutableStateOf(prefs.lastStatus) }
    var error by remember { mutableStateOf<String?>(null) }
    var checkId by remember { mutableStateOf<UUID?>(null) }

    // When a manual check finishes, show its result in the status line
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
    ) { /* nothing to do; the worker checks the permission before notifying */ }

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
        url = url.trim(),
        text = text,
        mode = mode,
        // WorkManager's minimum periodic interval is 15 minutes
        intervalMinutes = (interval.toLongOrNull() ?: 15L).coerceAtLeast(15L),
        retentionDays = (retention.toIntOrNull() ?: 7).coerceIn(1, 365),
        saveFrom = saveFrom.trim(),
        saveUntil = saveUntil.trim(),
        ignoreCase = ignoreCase,
        enabled = isEnabled,
    )

    fun validate(): String? = when {
        !(url.trim().startsWith("https://") || url.trim().startsWith("http://")) ->
            "URL must start with http:// or https://"
        text.isBlank() -> "Enter the text to watch for"
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
        Text("URL Watcher", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("URL to check") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Text to look for") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Notify me when the text:")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == Mode.APPEARS,
                onClick = { mode = Mode.APPEARS },
                label = { Text("Appears") },
            )
            FilterChip(
                selected = mode == Mode.DISAPPEARS,
                onClick = { mode = Mode.DISAPPEARS },
                label = { Text("Disappears") },
            )
        }

        OutlinedTextField(
            value = interval,
            onValueChange = { interval = it.filter(Char::isDigit) },
            label = { Text("Check every (minutes, min 15)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = retention,
            onValueChange = { retention = it.filter(Char::isDigit) },
            label = { Text("Keep saved pages for (days, 1-365)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("What to save from the page (leave both empty to save all the text):")

        OutlinedTextField(
            value = saveFrom,
            onValueChange = { saveFrom = it },
            label = { Text("Save starting at this text") },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = saveUntil,
            onValueChange = { saveUntil = it },
            label = { Text("Stop at the end of the line containing") },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = ignoreCase, onCheckedChange = { ignoreCase = it })
            Text("  Ignore upper/lower case")
        }

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
                    // A fresh start should be able to notify again
                    prefs.lastConditionMet = false
                    applySchedule(cfg)
                },
            )
            Text("  Monitoring ${if (enabled) "ON" else "OFF"}")
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
                    status = "Settings saved"
                }
            }) { Text("Save") }

            Button(onClick = {
                val problem = validate()
                if (problem != null) {
                    error = problem
                } else {
                    error = null
                    askNotificationPermission()
                    // Save the current settings as they are; the manual check ignores the Monitoring switch
                    prefs.save(currentConfig(enabled))
                    val request = OneTimeWorkRequestBuilder<CheckWorker>()
                        .setInputData(workDataOf(CheckWorker.KEY_MANUAL to true))
                        .build()
                    WorkManager.getInstance(ctx).enqueue(request)
                    checkId = request.id
                    status = "Checking…"
                }
            }) { Text("Check now") }
        }

        OutlinedButton(onClick = {
            val name = prefs.lastSnapshot
            if (name.isBlank()) {
                status = "No saved page yet. One is saved each time a notification is triggered."
            } else {
                ctx.startActivity(
                    Intent(ctx, SnapshotActivity::class.java)
                        .putExtra(SnapshotActivity.EXTRA_FILE, name)
                        .putExtra(SnapshotActivity.EXTRA_URL, prefs.lastSnapshotUrl)
                )
            }
        }) { Text("View last saved page") }

        Text(status, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Note: Android runs periodic checks at roughly the interval you set; " +
                "exact timing depends on battery and network conditions.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
