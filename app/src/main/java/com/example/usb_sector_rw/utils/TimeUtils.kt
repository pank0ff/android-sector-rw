import android.os.Build
import androidx.annotation.RequiresApi
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

object TimeUtils {
    @RequiresApi(Build.VERSION_CODES.O)
    private val EPOCH_1601 = Instant.parse("1970-01-01T00:00:00Z")

    @RequiresApi(Build.VERSION_CODES.O)
    fun lospUsTick(): ULong {
        val now = Instant.now()
        val duration = ChronoUnit.MICROS.between(EPOCH_1601, now)
        return duration.toULong()
    }

    fun tickEnd(interval: String): ULong {
        val seconds = interval.toDoubleOrNull()?.coerceAtLeast(0.01) ?: 0.01
        val microseconds = (1_000_000.0 * seconds).toULong()
        return lospUsTick() + microseconds
    }

    fun timeoutOk(stop: ULong): Boolean {
        return lospUsTick() < stop
    }
}