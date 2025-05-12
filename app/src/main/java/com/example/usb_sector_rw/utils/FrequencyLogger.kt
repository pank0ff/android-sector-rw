import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@SuppressLint("StaticFieldLeak")
object FrequencyLogger {
    private var handleOut: FileOutputStream? = null
    private var tickBegin: ULong = 0u
    private var cycle: UInt = 0u
    private lateinit var context: Context
    private var currentLogFile: File? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    @SuppressLint("DefaultLocale")
    @RequiresApi(Build.VERSION_CODES.O)
    fun frecToFile(
        frecCurrent: Float,
        frecAverage: Float,
        frecMin: Float,
        frecMax: Float,
        frecRms: Float,
        frecAcc: Float,
        periodAcc: Float,
        impCount: ULong,
        callLast: Boolean,
        answer: SectorAnswer
    ) {
        if (handleOut == null) {
            try {
                currentLogFile = generateLogFile()
                handleOut = FileOutputStream(currentLogFile, false)

                val header = """
Номер\tВремя, сек\tF_текущая, Гц\tF_среднее, Гц\tF_min, Гц\tF_max, Гц\tСКО, Гц\tdF, %\tdT, %\tИмпульсы\tVDDA, В\tT_ФЭУ, °C\tU_ФЭУ, В

# Пояснение к столбцам:
A - номер завершенной выборки, B - время, C - текущая частота, D - средняя частота, E - Fmin, F - Fmax, G - СКО, H - ошибка частоты, I - ошибка периода, J - импульсы, K - VDDA, L - температура, M - питание ФЭУ
""".trimIndent()

                handleOut?.write((header + "\n").toByteArray(Charset.forName("UTF-8")))
                tickBegin = TimeUtils.lospUsTick()
                return
            } catch (e: Exception) {
                errorHandler("Ошибка открытия файла: ${e.message}")
                handleOut = null
                return
            }
        }

        if (callLast) {
            try {
                handleOut?.close()
            } catch (_: Exception) {}
            handleOut = null
            return
        }

        val p = FmPhasesStruct.fromByteArrayToFmPhasesStruct(answer.dataOut)

        try {
            val tickNow = TimeUtils.lospUsTick()
            val timeSec = (tickNow - tickBegin).toDouble() / 1e6

            val line = String.format(
                "%d\t%.02f\t%.02f\t%.03f\t%.02f\t%.02f\t%.03f\t%.03f\t%.03f\t%d\t%.04f\t%.03f\t%.04f\n",
                ++cycle,
                timeSec,
                frecCurrent,
                frecAverage,
                frecMin,
                frecMax,
                frecRms,
                frecAcc,
                periodAcc,
                impCount.toLong(),
                p.powAverage,
                p.tempAverage,
                p.powerHV
            )

            handleOut?.write(line.toByteArray(Charset.forName("UTF-8")))
        } catch (e: Exception) {
            errorHandler("Ошибка записи: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun generateLogFile(): File {
        val now = LocalDateTime.now()
        val timestamp = now.format(DateTimeFormatter.ofPattern("yyyy_MM_dd__HH"))
        val fileName = "${timestamp}_frec_file.log"
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, fileName)
    }

    fun getCurrentLogFile(): File? = currentLogFile

    private fun errorHandler(message: String) {
        println("ERROR: $message")
    }
}

fun openLogFile(context: Context, file: File) {
    val uri: Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "text/plain")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    } else {
        Toast.makeText(context, "Нет приложения для открытия файла", Toast.LENGTH_LONG).show()
    }
}