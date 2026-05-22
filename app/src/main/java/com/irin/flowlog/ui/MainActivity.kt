package com.irin.flowlog.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.irin.flowlog.DevConfig
import com.irin.flowlog.ServiceStats
import com.irin.flowlog.data.AppDatabase
import com.irin.flowlog.export.JsonExporter
import com.irin.flowlog.report.EsmTrigger
import com.irin.flowlog.dev.TestAction
import com.irin.flowlog.dev.TestBus
import com.irin.flowlog.dev.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneOffset

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db       = AppDatabase.getInstance(applicationContext)
        val exporter = JsonExporter(db)

        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                StatusScreen(
                    db = db,
                    onShare = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { exporter.export(applicationContext) }
                            startActivity(Intent.createChooser(exporter.shareIntent(applicationContext, file), "Share export"))
                        }
                    },
                    onView = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { exporter.export(applicationContext) }
                            startActivity(exporter.viewIntent(applicationContext, file))
                        }
                    },
                    onDeleteAll = {
                        scope.launch(Dispatchers.IO) {
                            db.eventDao().deleteAll()
                            db.sessionDao().deleteAll()
                        }
                    },
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onDisableBatteryOptimization = {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                        )
                    },
                )
            }
        }
    }
}

@Composable
fun StatusScreen(
    db: AppDatabase,
    onShare: () -> Unit,
    onView: () -> Unit,
    onDeleteAll: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onDisableBatteryOptimization: () -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ── Live stats ────────────────────────────────────────────────────────────
    val totalEvents by db.eventDao().observeTotalEventCount()
        .collectAsStateWithLifecycle(initialValue = 0)
    val currentSessionEvents by db.eventDao().observeCurrentSessionEventCount()
        .collectAsStateWithLifecycle(initialValue = 0)
    val totalSessions by db.sessionDao().observeTotalSessionCount()
        .collectAsStateWithLifecycle(initialValue = 0)
    val todayStart = remember { "${LocalDate.now(ZoneOffset.UTC)}T00:00:00Z" }
    val sessionsToday by db.sessionDao().observeTodaySessionCount(todayStart)
        .collectAsStateWithLifecycle(initialValue = 0)

    val isRunning    by ServiceStats.isRunning.collectAsStateWithLifecycle()
    val lastEventMs  by ServiceStats.lastEventAtMs.collectAsStateWithLifecycle()

    val pm = context.getSystemService(PowerManager::class.java)
    val needsBatteryWarning = remember { !pm.isIgnoringBatteryOptimizations(context.packageName) }

    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text("flowlog", style = MaterialTheme.typography.headlineLarge)

        // ── Service status card ───────────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Service", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (label, color) = if (isRunning)
                        "Running" to MaterialTheme.colorScheme.primary
                    else
                        "Stopped" to MaterialTheme.colorScheme.error
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
                }
                val lastEventLabel = when {
                    lastEventMs == 0L -> "No events yet this session"
                    else -> {
                        val secs = (System.currentTimeMillis() - lastEventMs) / 1_000
                        val elapsed = when {
                            secs < 60   -> "${secs}s ago"
                            secs < 3600 -> "${secs / 60}m ago"
                            else        -> "${secs / 3600}h ago"
                        }
                        "Last event: $elapsed"
                    }
                }
                if (isRunning) {
                    Text(lastEventLabel, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ── Stats card ────────────────────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Stats", style = MaterialTheme.typography.titleSmall)
                StatRow("Events recorded (all time)", totalEvents)
                StatRow("Events in current session",  currentSessionEvents)
                StatRow("Sessions today",              sessionsToday)
                StatRow("Sessions total",              totalSessions)
            }
        }

        // ── Battery warning ───────────────────────────────────────────────────
        if (needsBatteryWarning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Battery optimization is on. The OS may stop FlowLog during extended recording.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = onDisableBatteryOptimization) {
                        Text("Disable battery optimization")
                    }
                }
            }
        }

        HorizontalDivider()

        // Plain-language privacy summary — required by spec §6.
        Text(
            "What this app records:\n" +
            "• Taps, scrolls, and pauses inside Instagram\n" +
            "• How long you spend on each piece of content\n" +
            "• When your screen turns on or off\n" +
            "• Which apps send you notifications (package name only — no content)\n\n" +
            "What it never records:\n" +
            "• Any text, captions, comments, or messages\n" +
            "• Images, video, or audio\n" +
            "• Notification titles or bodies\n" +
            "• Your location or identity\n\n" +
            "All data stays on this device until you export it manually.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()

        Button(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
            Text("Open Accessibility Settings")
        }
        Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
            Text("Share JSON")
        }
        OutlinedButton(onClick = onView, modifier = Modifier.fillMaxWidth()) {
            Text("View JSON")
        }
        OutlinedButton(
            onClick  = { showDeleteConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text("Delete all data")
        }

        if (DevConfig.enabled) {
            HorizontalDivider()
            Text("Dev", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
            Button(
                onClick  = { scope.launch(Dispatchers.IO) { EsmTrigger(context, AppDatabase.getInstance(context)).fireForTesting() } },
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
            ) {
                Text("Trigger ESM now")
            }

            // ── Test runner ───────────────────────────────────────────────────
            var selectedAction   by remember { mutableStateOf(TestAction.all.first()) }
            var dropdownExpanded by remember { mutableStateOf(false) }
            var testUiState      by remember { mutableStateOf<TestUiState>(TestUiState.Idle) }

            Row(
                modifier            = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment   = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick  = { if (testUiState is TestUiState.Idle) dropdownExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.tertiary),
                    ) { Text(selectedAction.label) }
                    DropdownMenu(
                        expanded        = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                    ) {
                        TestAction.all.forEach { action ->
                            DropdownMenuItem(
                                text    = { Text(action.label) },
                                onClick = { selectedAction = action; dropdownExpanded = false },
                            )
                        }
                    }
                }
                Button(
                    onClick = {
                        scope.launch {
                            for (i in 10 downTo 1) {
                                testUiState = TestUiState.Countdown(i)
                                delay(1_000L)
                            }
                            TestBus.testResult.value = null
                            testUiState = TestUiState.Executing
                            TestBus.triggerAction.emit(selectedAction)
                            val result = withTimeoutOrNull(5_000L) {
                                TestBus.testResult.first { it != null }
                            }
                            testUiState = when (result) {
                                is TestResult.Pass -> TestUiState.Result(passed = true,  detail = result.detected)
                                else               -> TestUiState.Result(passed = false, detail = "none")
                            }
                            delay(4_000L)
                            testUiState = TestUiState.Idle
                        }
                    },
                    enabled = testUiState is TestUiState.Idle,
                    colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                ) {
                    Text(
                        when (val s = testUiState) {
                            is TestUiState.Idle      -> "Run"
                            is TestUiState.Countdown -> "${s.secondsLeft}s"
                            is TestUiState.Executing -> "…"
                            is TestUiState.Result    -> if (s.passed) "PASS" else "FAIL"
                        }
                    )
                }
            }

            val resultState = testUiState
            if (resultState is TestUiState.Result) {
                Text(
                    if (resultState.passed) "detected: ${resultState.detail}" else "nothing detected",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (resultState.passed) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete all data?") },
            text  = { Text("This permanently removes all recorded events and sessions from this device. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteAll()
                    showDeleteConfirm = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

private sealed class TestUiState {
    object Idle : TestUiState()
    data class Countdown(val secondsLeft: Int) : TestUiState()
    object Executing : TestUiState()
    data class Result(val passed: Boolean, val detail: String) : TestUiState()
}

@Composable
private fun StatRow(label: String, value: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text("$value", style = MaterialTheme.typography.bodyMedium)
    }
}
