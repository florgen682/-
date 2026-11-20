package app.clipradar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import app.clipradar.core.Sources
import app.clipradar.ui.ClipRadarTheme
import app.clipradar.ui.MainViewModel
import app.clipradar.ui.MomentsScreen
import app.clipradar.ui.SettingsScreen
import app.clipradar.ui.StreamersScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var sharedLink by mutableStateOf<String?>(null)
    private var showBatteryDialog by mutableStateOf(false)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Toast.makeText(this, "Без уведомлений вы не узнаете о готовых клипах", Toast.LENGTH_LONG).show()
        }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        if (savedInstanceState == null) askNotifications()

        setContent {
            ClipRadarTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Clip Radar", fontWeight = FontWeight.Bold) },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        )
                    },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Home, null) }, label = { Text("Моменты") })
                            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.Person, null) }, label = { Text("Стримеры") })
                            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Настройки") })
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbar) },
                    containerColor = MaterialTheme.colorScheme.background,
                ) { padding ->
                    val m = Modifier.padding(padding)
                    androidx.compose.foundation.layout.Box(m) {
                        when (tab) {
                            0 -> MomentsScreen(vm, sharedLink, onNeedBattery = ::maybeAskBattery)
                            1 -> StreamersScreen(vm, onNeedBattery = ::maybeAskBattery)
                            else -> SettingsScreen(vm, onBattery = { openBatterySettings() }, onNotifications = { askNotifications(force = true) })
                        }
                    }
                }

                if (showBatteryDialog) {
                    AlertDialog(
                        onDismissRequest = { showBatteryDialog = false },
                        title = { Text("Работа в фоне") },
                        text = {
                            Text(
                                "Чтобы Clip Radar мог следить за эфиром, когда экран выключен, " +
                                    "разрешите ему работать без ограничений батареи. На следующем экране нажмите «Разрешить».",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showBatteryDialog = false; openBatterySettings() }) { Text("Хорошо") }
                        },
                        dismissButton = { TextButton(onClick = { showBatteryDialog = false }) { Text("Позже") } },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshStorage()
    }

    /** Ссылка, которой «поделились» из YouTube / Twitch. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            sharedLink = Sources.extractUrl(text) ?: text
        }
    }

    private fun askNotifications(force: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (force) {
            Toast.makeText(this, "Уведомления уже разрешены", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isIgnoringBattery(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    /** Перед наблюдением в фоне попросим исключение из экономии батареи (один раз). */
    private fun maybeAskBattery() {
        if (isIgnoringBattery()) return
        val s = app.clipradar.data.Settings.get(this)
        if (!s.batteryAsked) {
            s.batteryAsked = true
            showBatteryDialog = true
        }
    }

    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        if (isIgnoringBattery()) {
            Toast.makeText(this, "Уже разрешено ✅", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (e: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
