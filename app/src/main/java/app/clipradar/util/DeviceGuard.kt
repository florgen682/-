package app.clipradar.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.delay

/** Не даём телефону перегреться и разрядиться: нарезка ставится на паузу. */
object DeviceGuard {
    /** null — всё хорошо; иначе причина паузы. */
    fun problem(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                return "телефон перегрелся — продолжу, когда остынет"
            }
        }
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0 && scale > 0 && level * 100 / scale < 15 && !charging) {
                return "батарея меньше 15% — продолжу, когда поставите на зарядку"
            }
        }
        return null
    }

    /** Ждёт, пока условия не станут нормальными. [onPause] вызывается при каждой проверке во время паузы. */
    suspend fun waitUntilOk(context: Context, onPause: suspend (String) -> Unit) {
        var announced = false
        while (true) {
            val p = problem(context) ?: return
            if (!announced) {
                AppLog.w("DeviceGuard", "Пауза: $p")
                announced = true
            }
            onPause(p)
            delay(30_000)
        }
    }
}
