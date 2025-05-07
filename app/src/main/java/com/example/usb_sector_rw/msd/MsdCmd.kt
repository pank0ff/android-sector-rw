/**
 *******************************************************************************
 * @author  Новик Михаил <novikm@yahoo.com>
 * @date    2023.01.13 - 2024.12.26
 * @version 2024.12.26
 * @file    MsdCmd.kt
 * @brief   Заголовочный файл формата команд управления устройств класса MSD.
 *******************************************************************************
 **/

/**
 * @file
 * Модуль используется в проектах реализации самого ведомого устройства класса
 * MSD на шине USB и приложений управления устройством со стороны хоста USB.
 * В модуле описан протокол взаимодействия хоста USB, который выполняет роль
 * ведущего во взаимодействии по интерфейсу USB, и подчиненного ведомого
 * устройства ЛОСП класса MSD.
 * Определены номера и структура данных сектора команд, записываемая хостом.
 * Также приведены номера и структура данных сектора ответа, возвращаемая хосту.
 * Устройства ЛОСП класса MSD взаимодействует с хостом USB и обеспечивает
 * обработку его команд.
 **/

import android.util.Log
import com.example.usb_sector_rw.losp.*
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val SECTOR_SIZE:u32 = 0x200u
const val SECTOR_LAST: u32 = 0x2Fu
const val SECTOR_CMD: u32 = 0x10u
const val SECTOR_ANSWER: u32 = 0x20u
const val MSD_DATA_MAX_SIZE: u16 = 0x1F0u
const val MAGIC_BOOT_KEY: u32 = 0xDC42ACCAu
const val BOARD_PRAM_ID: u32 = 0x727000u

/**
 * Команды для взаимодействия с PRAM
 */
enum class CmdToPram(val value: u32) {
    /** Тест обмена данными */
    PRAM_TEST_ECHO(0x10001u),

    /** Тест протокола обмена данными на USB-шине */
    PRAM_NOT_OP(0x10002u),

    /** Вернуть серийный номер микроконтроллера PRAM */
    PRAM_GET_CPU_UID(0x10003u),

    /** Вернуть регистр состояния и ошибок */
    PRAM_GET_STATUS_AND_ERROR(0x10004u),

    /** Очистить регистр состояния и ошибок */
    PRAM_CLEAR_STATUS_AND_ERROR(0x10005u),

    /** Установить текущее время, дату */
    PRAM_SET_TIME(0x10006u),

    /** Считать текущее время, дату */
    PRAM_GET_TIME(0x10007u),

    /** Получить версию ПО PRAM (4 байта) */
    PRAM_GET_VERSION(0x10008u),

    /** Получить результаты измерений температуры V1.0 */
    PRAM_GET_TEMP_V1(0x10009u),

    /** Получить серийный номер устройства */
    PRAM_GET_SERIAL_NUMBER(0x1000Au),

    /** Получить версию ПО обновления */
    PRAM_GET_UPDATE_VERSION(0x1000Bu),

    /** Получить статус процесса обновления */
    PRAM_GET_UPDATE_STATUS(0x1000Cu),

    /** Получить зафиксированные фазы таймеров V1.0 */
    PRAM_GET_FW_PHASE_V1(0x10200u),

    /** Получить измеренную частоту V1.0 */
    PRAM_GET_FW_FREC_V1(0x10201u),

    /** Получить зафиксированные фазы таймеров */
    PRAM_GET_FW_PHASES(0x10202u),

    /** Получить измеренную частоту */
    PRAM_GET_FW_FREC(0x10203u),

    /** Получить результаты измерений аналогового сигнала ФЭУ */
    PRAM_GET_AM_DATE(0x10204u),

    /** Получить результаты измерений температуры МК */
    PRAM_GET_TEMP(0x10205u),

    /** Получить измерения уровня ультрафиолета pram-23 */
    PRAM_GET_UV(0x10206u),

    /** Получить результаты измерений питания МК */
    PRAM_GET_POWER(0x10207u),

    /** Получить результаты измерений температуры ФЭУ */
    PRAM_GET_TEMP_SENS(0x10208u),

    /** Установить уровень ЦАП-а (текуший или в EEPROM) */
    PRAM_SET_DAC_LEVEL(0x10209u),

    /** Получить уровень ЦАП-а (текуший или в EEPROM) */
    PRAM_GET_DAC_LEVEL(0x1020Au),

    /** Задать значение заданного параметра работы PRAM */
    PRAM_SET_CURRENT_PARAM(0x1020Bu),

    /** Восстановить заданный параметр из ROM МК */
    PRAM_RESTORE_PARAM(0x1020Cu),

    /** Записать в ROM МК заданный параметр работы PRAM */
    PRAM_SAVE_PARAM_ROM(0x1020Du),

    /** Получить текущего заданного параметра работы PRAM */
    PRAM_GET_CURRENT_PARAM(0x1020Eu),

    /** Получить значение заданного параметра из ROM МК */
    PRAM_GET_ROM_PARAM(0x1020Fu),

    /** Получить заданный заводской параметр от производителя */
    PRAM_GET_FACTORY_PARAM(0x10210u),

    /** Вернуть случайные данные */
    PRAM_GET_RND(0x102FFu),

    /** Загрузить USB-бутлоадер */
    PRAM_JMP_BOOTLOADER(0x10300u),

    /** Защитить от чтения прошивку PRAM */
    PRAM_MC_CODE_LOCK(0x10301u),

    /** Выполнить обновление прошивки PRAM */
    PRAM_JMP_UPDATE(0x10302u),

    /** Установить ключ аутентификации PRAM (нет) */
    PRAM_SET_KEY(0x10500u),

    /** Выполнить первую фазу аутентификации (нет) */
    PRAM_RUN_AUTENT1(0x10501u),

    /** Выполнить вторую фазу аутентификации (нет) */
    PRAM_RUN_AUTENT2(0x10502u);

    companion object {
        fun fromValue(value: u32): CmdToPram? = CmdToPram.entries.find { it.value == value }
    }
}

/**
 * Коды возврата от PRAM
 */
enum class RetFromPram(val value: u32) {
    /** Команда выполнена успешно */
    PRAM_MSD_OK(0x10000u),

    /** Ответ на необслуживаемую команду */
    PRAM_MSD_INVALID_OP(0x10001u),

    /** Команда еще не выполнена */
    PRAM_MSD_BUSY(0x10001u),

    /** Ошибка исполнения команды */
    PRAM_MSD_ERROR(0x30001u),

    /** Нет запрошенных данных */
    PRAM_MSD_NOT_DATA(0x30002u),

    /** Ошибочные параметры обмена */
    PRAM_MSD_BAD_PARAMETER(0x18001u),

    /** Запрет выполнения команды */
    PRAM_MSD_LOCKED(0x18002u);

    companion object {
        fun fromValue(value: u32): RetFromPram? = RetFromPram.entries.find { it.value == value }
    }
}

/**
 * Класс для сектора команды, записываемого хостом.
 */
class SectorCmd {//: Union(SECTOR_SIZE) {

    /** Код команды */
    var code: UInt = 0u //by uIntAt(0)

    /** Смещение данных */
    var offset: UInt = 0u //by uIntAt(4)

    /** Длина входных данных в байтах */
    var sizeIn: UShort = 0u //by uShortAt(8)

    /** Длина выходных данных в байтах */
    var sizeOut: UShort = 0u //by uShortAt(10)

    /** Резервные байты для выравнивания */
    var reserv: ByteArray = ByteArray(4)//by byteArrayAt(12, 4)

    /** Входные данные для устройства (PRAM) */
    @OptIn(ExperimentalUnsignedTypes::class)
    var dataIn: UByteArray = UByteArray(508)//by byteArrayAt(16, 508)

    /**
     * Установить "сырые" данные в структуру.
     * @param rawData массив длиной SECTOR_SIZE байт
     */
//    fun setRawData(rawData: ByteArray) {
//        require(rawData.size.toUInt() == SECTOR_SIZE) { "Invalid rawData size, expected $SECTOR_SIZE bytes" }
//        loadFrom(rawData)
//    }

    /**
     * Получить всю структуру как массив байт.
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    fun getRawData(): ByteArray {
        val SECTOR_SIZE = 524
        val buffer = ByteBuffer.allocate(SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        // println("Заполнение буфера...")

        try {
            // Код команды
            // println("code: ${code.toInt()}")
            buffer.putInt(code.toInt())         // 0–3

            // Смещение данных
            // println("offset: ${offset.toInt()}")
            buffer.putInt(offset.toInt())       // 4–7

            // Длина входных данных
            // println("sizeIn: ${sizeIn.toShort()}")
            buffer.putShort(sizeIn.toShort())   // 8–9

            // Длина выходных данных
            // println("sizeOut: ${sizeOut.toShort()}")
            buffer.putShort(sizeOut.toShort())  // 10–11

            // Резервные байты
            // println("reserv: ${reserv.joinToString()}")
            val reservPadded = ByteArray(4)
            reserv.copyInto(reservPadded, endIndex = minOf(reserv.size, 4))
            buffer.put(reservPadded)            // 12–15

            // Входные данные
            // println("dataIn size: ${dataIn.size}")
            val dataInPadded = dataIn.copyOf(508).asByteArray()
            buffer.put(dataInPadded)            // 16–523

            // Проверка размера буфера перед возвратом данных
            val bufferSize = buffer.position()
            // println("Записано в буфер байт: $bufferSize")

            if (bufferSize != SECTOR_SIZE) {
                // println("Предупреждение: размер буфера не соответствует SECTOR_SIZE!")
            }

        } catch (e: BufferOverflowException) {
            // println("Ошибка: Превышен размер буфера! Внимательно проверь данные и их размер.")
            throw e
        }

        // Вернуть массив байт
        return buffer.array()               // 520 байт
    }
}

class SectorAnswer {// : Union(SECTOR_SIZE) {

    /** Код выполненной команды */
    var cmd: UInt = 0u //by uIntAt(0)

    /** Код возврата */
    var ret: UInt = 0u //by uIntAt(4)

    /** Длина выходных данных в байтах */
    var sizeOut: UShort = 0u //by uShortAt(8)

    /** Резервные байты для выравнивания */
    var reserv: ByteArray = ByteArray(6)//by byteArrayAt(10, 6)

    /** Выходные данные устройства (PRAM) */
    @OptIn(ExperimentalUnsignedTypes::class)
    var dataOut: UByteArray = UByteArray(508)//by byteArrayAt(16, 508)

    /**
     * Получить всю структуру как массив байт.
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    fun getRawData(): ByteArray {
        val TAG = "SectorCmdDebug"

        val buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        // Log.d(TAG, "Initial buffer capacity: ${buffer.capacity()}")

        // 1. cmd
        buffer.putInt(cmd.toInt())
        // Log.d(TAG, "After cmd: position=${buffer.position()}, remaining=${buffer.remaining()}")

        // 2. ret
        buffer.putInt(ret.toInt())
        // Log.d(TAG, "After ret: position=${buffer.position()}, remaining=${buffer.remaining()}")

        // 3. sizeOut
        buffer.putShort(sizeOut.toShort())
        // Log.d(TAG, "After sizeOut: position=${buffer.position()}, remaining=${buffer.remaining()}")

        // 4. reserv (6 байт)
        val reservPadded = ByteArray(6)
        reserv.copyInto(reservPadded, endIndex = minOf(reserv.size, 6))
        buffer.put(reservPadded)
        // Log.d(TAG, "After reserv: position=${buffer.position()}, remaining=${buffer.remaining()}")

        // 5. dataIn (508 байт)
        val dataInPadded = dataOut.copyOf(508).asByteArray()
        buffer.put(dataInPadded)
        // Log.d(TAG, "After dataIn (508 bytes): position=${buffer.position()}, remaining=${buffer.remaining()}")

        return buffer.array()
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun loadFromRawData(input: ByteArray) {
//        require(input.size == 512) { "Expected 512 bytes, got ${input.size}" }

        val buffer = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)

        // 1. cmd (4 байта)
        cmd = buffer.int.toUInt()

        // 2. ret (4 байта)
        ret = buffer.int.toUInt()

        // 3. sizeOut (2 байта)
        sizeOut = buffer.short.toUShort()

        // 4. reserv (6 байт)
        reserv = ByteArray(6)
        buffer.get(reserv)

        // 5. dataOut (508 байт)
        val rawData = ByteArray(496)//ByteArray(508)
        buffer.get(rawData)
        dataOut = rawData.asUByteArray()
    }
}

enum class StructLospDataType(val code: u16) {
    STRUCT_LOSP_BOARD_ID(0x1A2Eu),
    STRUCT_PRAM_BOARD_ID(0x1A2Fu),

    STRUCT_FM_FREC_DATA_V1(0x4C70u),
    STRUCT_FM_PHASE_DATA_V1(0x4C71u),
    STRUCT_TEMP_DATA_V1(0x4C72u),

    STRUCT_FM_FREC_DATA(0x4C73u),
    STRUCT_FM_PHASES_DATA(0x4C74u),
    STRUCT_TEMP_DATA(0x4C75u),
    STRUCT_UV_DATA(0x4C76u),
    STRUCT_POW_DATA(0x4C77u),
    STRUCT_AHT21_DATA(0x4C78u),
    STRUCT_DAC_OUT_DATA(0x4C79u),

    STRUCT_FACTORY_PARAM(0x4C90u),
    STRUCT_ROM_PARAM(0x4C91u),
    STRUCT_CURRENT_PARAM(0x4C92u),
    STRUCT_DEFAULT_PARAM(0x4C93u);

    companion object {
        fun fromValue(value: u32): RetFromPram? = RetFromPram.entries.find { it.value == value }
    }
}

data class LospBoardStruct(
    var structType: UShort = 0u,
    var structSize: UShort = 0u,
    var boardID: UInt = 0u,
    var fixLospID: UInt = 0u,
    var systemClock: UInt = 0u,
    var systemPhase: Int = 0,
    var powAverage: Float = 0f,
    var tempAverage: Float = 0f,
    var reserv: ByteArray = ByteArray(11),
    var crc32: UInt = 0u
) {
    companion object {
        const val SIZE = 40

        fun fromByteArray(data: ByteArray): LospBoardStruct {
            require(data.size >= SIZE) { "Data size must be at least $SIZE bytes" }
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            return LospBoardStruct(
                structType = buffer.short.toUShort(),
                structSize = buffer.short.toUShort(),
                boardID = buffer.int.toUInt(),
                fixLospID = buffer.int.toUInt(),
                systemClock = buffer.int.toUInt(),
                systemPhase = buffer.int,
                powAverage = buffer.float,
                tempAverage = buffer.float,
                reserv = ByteArray(11).apply { buffer.get(this) },
                crc32 = buffer.int.toUInt()
            )
        }
    }

    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putShort(structType.toShort())
        buffer.putShort(structSize.toShort())
        buffer.putInt(boardID.toInt())
        buffer.putInt(fixLospID.toInt())
        buffer.putInt(systemClock.toInt())
        buffer.putInt(systemPhase)
        buffer.putFloat(powAverage)
        buffer.putFloat(tempAverage)
        buffer.put(reserv.copyOf(11))
        buffer.putInt(crc32.toInt())

        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LospBoardStruct

        if (systemPhase != other.systemPhase) return false
        if (powAverage != other.powAverage) return false
        if (tempAverage != other.tempAverage) return false
        if (structType != other.structType) return false
        if (structSize != other.structSize) return false
        if (boardID != other.boardID) return false
        if (fixLospID != other.fixLospID) return false
        if (systemClock != other.systemClock) return false
        if (!reserv.contentEquals(other.reserv)) return false
        if (crc32 != other.crc32) return false

        return true
    }

    override fun hashCode(): Int {
        var result = systemPhase
        result = 31 * result + powAverage.hashCode()
        result = 31 * result + tempAverage.hashCode()
        result = 31 * result + structType.hashCode()
        result = 31 * result + structSize.hashCode()
        result = 31 * result + boardID.hashCode()
        result = 31 * result + fixLospID.hashCode()
        result = 31 * result + systemClock.hashCode()
        result = 31 * result + reserv.contentHashCode()
        result = 31 * result + crc32.hashCode()
        return result
    }
}

data class PramBoardStruct(
    var structType: UShort = 0u,
    var structSize: UShort = 0u,
    var reserv: ByteArray = ByteArray(11),
    var crc32: UInt = 0u
) {
    companion object {
        const val SIZE = 2 + 2 + 11 + 4 // 19 bytes

        fun fromByteArray(data: ByteArray): PramBoardStruct {
            require(data.size >= SIZE) { "Data size must be at least $SIZE bytes" }
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            return PramBoardStruct(
                structType = buffer.short.toUShort(),
                structSize = buffer.short.toUShort(),
                reserv = ByteArray(11).apply { buffer.get(this) },
                crc32 = buffer.int.toUInt()
            )
        }
    }

    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putShort(structType.toShort())
        buffer.putShort(structSize.toShort())
        buffer.put(reserv.copyOf(11))
        buffer.putInt(crc32.toInt())

        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PramBoardStruct

        if (structType != other.structType) return false
        if (structSize != other.structSize) return false
        if (!reserv.contentEquals(other.reserv)) return false
        if (crc32 != other.crc32) return false

        return true
    }

    override fun hashCode(): Int {
        var result = structType.hashCode()
        result = 31 * result + structSize.hashCode()
        result = 31 * result + reserv.contentHashCode()
        result = 31 * result + crc32.hashCode()
        return result
    }
}

enum class PramModeBit(val value: UInt) {
    MODE_EN(0x00000001u),             //!< Разрешение ...
    MODE_SOUND_ALL_DIS(0x00010000u),  //!< Полный запрет звуковой сигнализации
    MODE_SOUND_LVL_DIS(0x00020000u),  //!< Запрет сигнализации превышения порогов
    MODE_LED_ALL_DIS(0x00040000u),    //!< Полный запрет световой сигнализации
    MODE_LED_LVL_DIS(0x00040000u),    //!< Полный запрет световой сигнализации превышения порогов
    MODE_DAC0_POW_DIS(0x00100000u),   //!< Запрет компенсации питания ФЭУ по питанию ЦАП-а
    MODE_DAC0_TEMP_DIS(0x00200000u),  //!< Запрет компенсации питания ФЭУ по температуре
    MODE_DAC1_POW_DIS(0x00400000u),   //!< Запрет компенсации порога компаратора по питанию ЦАП-а
    MODE_POW_UC_POL_DIS(0x01000000u), //!< Запрет коррекции питания МК полиномом
    MODE_T0_UC_POL_DIS(0x02000000u),  //!< Запрет коррекции температуры МК по даташиту
    MODE_T1_UC_POL_DIS(0x04000000u);  //!< Запрет коррекции температуры МК по STM32

    companion object {
        fun fromMask(mask: UInt): Set<PramModeBit> {
            return entries.filter { (it.value and mask) != 0u }.toSet()
        }

        fun toMask(modes: Set<PramModeBit>): UInt {
            return modes.fold(0u) { acc, bit -> acc or bit.value }
        }
    }
}

enum class PramStateBit(val value: UInt) {
    STATE_ADMIN(0x00010000u),       //!< Полномочия администратора
    STATE_USER(0x00020000u),        //!< Полномочия оператора
    STATE_BLOCKING(0x00040000u),    //!< Режим временной блокировки
    STATE_NO_AUTENT(0x00080000u),   //!< Не прошла аутентификация PRAM
    STATE_DAC0_FAIL(0x00100000u),   //!< Ошибка обмена с ЦАП-ом питания ФЭУ
    STATE_DAC1_FAIL(0x00200000u),   //!< Ошибка обмена с ЦАП-ом порога компаратора
    STATE_AUTENT2(0x02000000u),     //!< Подтверждены полномочия хоста
    STATE_CRITICAL(0xFF000000u),    //!< Маска критических ошибок в регистре состояния
    STATE_INIT_BAD(0x10000000u),    //!< Ошибка на старте устройства
    STATE_ADMIN_LOCK(0x20000000u),  //!< Блокировка работы администратора
    STATE_SOFT_ERROR(0x40000000u),  //!< Ошибка исполнения программы
    STATE_FAULTY(0x80000000u);      //!< Отказ работоспособности

    companion object {
        fun fromMask(mask: UInt): Set<PramStateBit> {
            return entries.filter { (it.value and mask) != 0u }.toSet()
        }

        fun toMask(states: Set<PramStateBit>): UInt {
            return states.fold(0u) { acc, state -> acc or state.value }
        }
    }
}

data class PramDacStruct(
    var structType: UShort,    //!< Тип структуры - STRUCT_DAC_OUT_DATA
    var structSize: UShort,    //!< Размер структуры в байтах
    var chan: UShort,          //!< Номер микросхемы ЦАП-а (0, 1, 2, 3)
    var code: UShort,          //!< Код выходного напряжения (12 бит) и режим (2 бита)
    var voltage: Float         //!< Уровень выходного напряжения в Вольтах
)

data class CalibrDacStruct(
    var outMin: Float,   ///< Калибровочный уровень для кода 0 в Вольтах
    var outMax: Float,   ///< Калибровочный уровень для кода 0xFFF в Вольтах
    var vddRef: Float    ///< Опорное напряжения ЦАП (питание Vdd) при калибровке, Вольт
)

enum class ParamAdjId(val id: Int) {
    // Секция параметров работы, которые не относятся к заводским параметрам
    PARAM_INTEGER_ID_MIN(0x6000), // Начальный идентификатор целочисленных параметров
    PARAM_CLEARED_ID(0x6000), // Идентификатор удаленного параметра работы
    PARAM_DEVICE_MODE_ID(0x6001), // Параметр режима работы устройства, u32
    PARAM_DEVICE_STATUS_ID(0x6002), // Параметр регистра состояния устройства, u32
    PARAM_DEVICE_ERROR_ID(0x6003), // Параметр регистра ошибок выполнения устройства, u32
    PARAM_DEVICE_ERROR_OLD_ID(0x6004), // Параметр предыдущих ошибок выполнения устройства, u32
    PARAM_DEVICE_ERROR_COUNT_ID(0x6005), // Параметр количества ошибок PRAM в текущем сеансе, u32

    // Условие для BOARD_IS_PRAM23 или BOARD_IS_PRAM43
    PARAM_UV_RANGE_ID(0x6080), // Диапазон измерений ультрафиолета (см. AM_RANGE_T), u32

    PARAM_INTEGER_ID_MAX(0x6100), // Максимальный идентификатор целочисленных параметров
    PARAM_FLOAT_ID_MIN(0x6100), // Начальный идентификатор вещественных параметров
    PARAM_DAC0_OUT_CURRENT_ID(0x6101), // Идентификатор уровня текущего питания ФЭУ, flt
    PARAM_DAC1_OUT_CURRENT_ID(0x6102), // Идентификатор уровня текущего порога компаратора, flt
    PARAM_DAC0_CODE_CURRENT_ID(0x6103), // Идентификатор кода текущего питания ФЭУ, u32
    PARAM_DAC1_CODE_CURRENT_ID(0x61004), // Идентификатор кода уровня текущего порога компаратора, u32

    PARAM_FLOAT_ID_MAX(0x6300), // Конечный идентификатор вещественных параметров УФ-измерителя

    PARAM_READ_ONLY_ID(0x6300), // Начальный идентификатор параметров только для чтения, u32
    PARAM_STATUS_ID(0x6301), // Параметр регистра состояния устройства, u32
    PARAM_ERROR_ID(0x6302), // Параметр ошибок выполнения устройства, u32
    PARAM_ERROR_OLD_ID(0x6303), // Параметр прежних ошибок устройства, u32

    // Секция заводских параметров
    PARAMV_VENDOR_ID_MIN(0x6400), // Начальный идентификатор заводских параметров
    PARAMV_INTEGER_ID_MIN(0x6400), // Начальный идентификатор целочисленных заводских параметров
    PARAMV_DEVICE_MODE_ID(0x6401), // Параметр режима работы устройства, u32

    PARAMV_INTEGER_ID_MAX(0x6500), // Максимальный идентификатор целочисленных заводских параметров
    PARAMV_FLOAT_ID_MIN(0x6500), // Начальный идентификатор вещественных заводских параметров
    PARAMV_LED_THRESHOLD1_ID(0x6501), // Идентификатор порога №1 световой тревоги, flt
    PARAMV_LED_THRESHOLD2_ID(0x6502), // Параметр порога №2 световой тревоги, flt
    PARAMV_SOUND_THRESHOLD1_ID(0x6503), // Параметр порогов №1 звуковой тревоги, flt
    PARAMV_SOUND_THRESHOLD2_ID(0x6504), // Параметр порогов №2 звуковой тревоги, flt
    PARAMV_DOZE_KOEF_ID(0x6505), // Коэффицент перевода частоты в дозу (Р/Г), flt
    PARAMV_UC_POWA_CALIBR_ID(0x6506), // Параметры калибровки опорного напряжения МК, flt[2]
    PARAMV_UC_TEMP_CALIBR_ID(0x6507), // Параметры калибровки температурного датчика МК, flt[2]
    PARAMV_DAC0_CALIBR_ID(0x6508), // Параметры калибровки (см. CalibrDacStruct) питания ФЭУ, flt[3]
    PARAMV_DAC1_CALIBR_ID(0x6509), // Параметры калибровки ЦАП-а порога компаратора, flt[3]
    PARAMV_DAC0_OUT_DEFAULT_ID(0x650A), // Идентификатор питания ФЭУ по умолчанию, flt
    PARAMV_DAC1_OUT_DEFAULT_ID(0x650B), // Идентификатор порога компаратора по умолчанию, flt

    PARAMV_POWA_UC_POLY_ID(0x6550), // Интерполяционный полином аналогового питания МК, flt[4]
    PARAMV_TEMP0_UC_POLY_ID(0x6551), // Полином температуры МК по формуле из даташита, flt[3]
    PARAMV_TEMP1_UC_POLY_ID(0x6552), // Полином температуры МК по коэффициентам STM32, flt[4]
    PARAMV_TEMP_CODE_POLY_ID(0x6553), // Полином кода АЦП температуры МК по температуре, flt[4]

    // Условие для BOARD_IS_PRAM23 или BOARD_IS_PRAM43
    PARAMV_FLOAT_UV_ID_MIN(0x6560), // Начальный идентификатор вещественных параметров УФ-измерителя
    PARAMV_LED_UV_LEVEL1_ID(0x6561), // Идентификатор порога №1 световой тревоги для УФ, flt
    PARAMV_LED_UV_LEVEL2_ID(0x6562), // Идентификатор порога №2 световой тревоги для УФ, flt
    PARAMV_SOUND_UV_LEVEL1_ID(0x6563), // Идентификатор порога №1 звуковой тревоги для УФ, flt
    PARAMV_SOUND_UV_LEVEL2_ID(0x6564), // Идентификатор порога №2 звуковой тревоги для УФ, flt
    PARAMV_UV_KOEF_COMP_ID(0x6565), // Идентификатор коэффициента термокомпенсации питания ФЭУ, flt

    PARAMV_UV375_POW_ID0(0x6580), // Идентификатор питания ФЭУ УФ-0 для 375 нм, flt
    PARAMV_UV375_POW_ID1(0x6581), // Идентификатор питания ФЭУ диапазона УФ-1, flt
    PARAMV_UV375_POW_ID2(0x6582), // Идентификатор питания ФЭУ диапазона УФ-2, flt
    PARAMV_UV375_POW_ID3(0x6583), // Идентификатор питания ФЭУ диапазона УФ-3, flt
    PARAMV_UV375_POW_ID4(0x6584), // Идентификатор питания ФЭУ диапазона УФ-4, flt
    PARAMV_UV375_POW_ID5(0x6585), // Идентификатор питания ФЭУ диапазона УФ-5, flt
    PARAMV_UV375_POW_ID6(0x6586), // Идентификатор питания ФЭУ диапазона УФ-6, flt
    PARAMV_UV375_POW_ID7(0x6587), // Идентификатор питания ФЭУ диапазона УФ-7, flt
    PARAMV_UV375_TEMP_ID0(0x6588), // Идентификатор температуры ФЭУ при калибровке УФ-0, flt
    PARAMV_UV375_TEMP_ID1(0x6589), // Идентификатор температуры ФЭУ при калибровке УФ-1, flt
    PARAMV_UV375_TEMP_ID2(0x658A), // Идентификатор температуры ФЭУ при калибровке УФ-2, flt
    PARAMV_UV375_TEMP_ID3(0x658B), // Идентификатор температуры ФЭУ при калибровке УФ-3, flt
    PARAMV_UV375_TEMP_ID4(0x658C), // Идентификатор температуры ФЭУ при калибровке УФ-4, flt
    PARAMV_UV375_TEMP_ID5(0x658D), // Идентификатор температуры ФЭУ при калибровке УФ-5, flt
    PARAMV_UV375_TEMP_ID6(0x658E), // Идентификатор температуры ФЭУ при калибровке УФ-6, flt
    PARAMV_UV375_TEMP_ID7(0x658F), // Идентификатор температуры ФЭУ при калибровке УФ-7, flt
    PARAMV_UV375_POLY_ID0(0x6590), // Идентификатор полинома интерполяции УФ-0, flt[4]
    PARAMV_UV375_POLY_ID1(0x6591), // Идентификатор полинома интерполяции УФ-уровня диапазона 1, flt[4]
    PARAMV_UV375_POLY_ID2(0x6592), // Идентификатор полинома интерполяции УФ-уровня диапазона 2, flt[4]
    PARAMV_UV375_POLY_ID3(0x6593), // Идентификатор полинома интерполяции УФ-уровня диапазона 3, flt[4]
    PARAMV_UV375_POLY_ID4(0x6594), // Идентификатор полинома интерполяции УФ-уровня диапазона 4, flt[4]
    PARAMV_UV375_POLY_ID5(0x6595), // Идентификатор полинома интерполяции УФ-уровня диапазона 5, flt[4]
    PARAMV_UV375_POLY_ID6(0x6596), // Идентификатор полинома интерполяции УФ-уровня

    UV270_POW_ID0(0x65A0),  // Идентификатор питания ФЭУ УФ-0 для 270 нм (см. AM_RANGE_T), flt
    UV270_POW_ID1(0x65A1),  // Идентификатор питания ФЭУ диапазона УФ-1, flt
    UV270_POW_ID2(0x65A2),  // Идентификатор питания ФЭУ диапазона УФ-2, flt
    UV270_POW_ID3(0x65A3),  // Идентификатор питания ФЭУ диапазона УФ-3, flt
    UV270_POW_ID4(0x65A4),  // Идентификатор питания ФЭУ диапазона УФ-4, flt
    UV270_POW_ID5(0x65A5),  // Идентификатор питания ФЭУ диапазона УФ-5, flt
    UV270_POW_ID6(0x65A6),  // Идентификатор питания ФЭУ диапазона УФ-6, flt
    UV270_POW_ID7(0x65A7),  // Идентификатор питания ФЭУ диапазона УФ-7, flt

    // Идентификаторы температуры ФЭУ при калибровке
    UV270_TEMP_ID0(0x65A8),  // Идентификатор температуры ФЭУ при калибровке УФ-0, flt
    UV270_TEMP_ID1(0x65A9),  // Идентификатор температуры ФЭУ при калибровке УФ-1, flt
    UV270_TEMP_ID2(0x65AA),  // Идентификатор температуры ФЭУ при калибровке УФ-2, flt
    UV270_TEMP_ID3(0x65AB),  // Идентификатор температуры ФЭУ при калибровке УФ-3, flt
    UV270_TEMP_ID4(0x65AC),  // Идентификатор температуры ФЭУ при калибровке УФ-4, flt
    UV270_TEMP_ID5(0x65AD),  // Идентификатор температуры ФЭУ при калибровке УФ-5, flt
    UV270_TEMP_ID6(0x65AE),  // Идентификатор температуры ФЭУ при калибровке УФ-6, flt
    UV270_TEMP_ID7(0x65AF),  // Идентификатор температуры ФЭУ при калибровке УФ-7, flt

    // Идентификаторы полиномов интерполяции
    UV270_POLY_ID0(0x65B0),  // Идентификатор полинома интерполяции УФ-0 (см. AM_RANGE_T), flt[4]
    UV270_POLY_ID1(0x65B1),  // Идентификатор полинома интерполяции УФ-уровня диапазона 1, flt[4]
    UV270_POLY_ID2(0x65B2),  // Идентификатор полинома интерполяции УФ-уровня диапазона 2, flt[4]
    UV270_POLY_ID3(0x65B3),  // Идентификатор полинома интерполяции УФ-уровня диапазона 3, flt[4]
    UV270_POLY_ID4(0x65B4),  // Идентификатор полинома интерполяции УФ-уровня диапазона 4, flt[4]
    UV270_POLY_ID5(0x65B5),  // Идентификатор полинома интерполяции УФ-уровня диапазона 5, flt[4]
    UV270_POLY_ID6(0x65B6),  // Идентификатор полинома интерполяции УФ-уровня диапазона 6, flt[4]
    UV270_POLY_ID7(0x65B7),  // Идентификатор полинома интерполяции УФ-уровня диапазона 7, flt[4]

    // Конечный идентификатор вещественных заводских параметров
    FLOAT_ID_MAX(0x6600),   // Конечный идентификатор вещественных заводских параметров

    // Секция заводских параметров, которые предназначены только для чтения
    READ_ONLY_ID(0x6600),   // Начальный идентификатор параметров только для чтения
    INTEGERO_ID_MIN(0x6600),// Начальный идентификатор целочисленных параметров только для чтения
    USB_VENDOR_ID(0x6601),  // Идентификатор производителя на шине USB, u16
    USB_DEVICE_ID(0x6602),  // Идентификатор устройства на шине USB, u16
    SERIAL_NUMBER16_ID(0x6603), // Серийный номер устройства PRAM (16 байт), u8[16]
    SERIAL_NUMBER_U32_ID(0x6604), // Серийный номер устройства PRAM (4 байта), u32
    MOTO_RESOURCE_ID(0x6605), // Количество секунд, наработанных устройством, u32

    UV_RANGE_MIN_ID1(0x66E0), // Минимальный номер рабочего диапазона УФ-измерителя, u8
    UV_RANGE_MIN_ID(0x66E1),  // Минимальный номер рабочего диапазона УФ-измерителя, u8
    UV_RANGE_MAX_ID(0x66E2),  // Максимальный номер рабочего диапазона УФ-измерителя, u8

    INTEGERO_ID_MAX(0x6700),  // Максимальный идентификатор целочисленных параметров только для чтения
    FLOATO_ID_MIN(0x6700),    // Начальный идентификатор вещественных параметров только для чтения
    DATE_RELEASE_ID(0x6701),  // Дата производства устройства в формате UNIX (секунды от 1970), float

    FLOATO_ID_MAX(0x6800),    // Конечный идентификатор вещественных параметров только для чтения
    PARAM_ID_MAX(0x6801);      // Максимальное значение идентификатора

    companion object {
        fun getParamIdForRange(minId: Int, maxId: Int): List<ParamAdjId> {
            return ParamAdjId.entries.filter { it.id in minId..maxId }
        }
    }
}

// Количество коэффициентов интерполяционных полиномов третей степени
const val POLYNOM3_KOEF_SIZE: UShort = 4u // тип коэффициентов - Double !!

// Максимальный размер в байтах обобщенного буфера параметров работы PRAM
const val PARAM_BUF_SIZE: UShort = 64u // Необходимо корректировать ПО хоста

data class ParamCrcStruct(
    var Crc32: UInt,       // Контрольная сумма последующих действительных полей
    var ParamType: UShort, // Тип параметра работы (см. PARAM_ADJ_ID_T)
    var ParamSize: UShort, // Реальный размер данной структуры параметра в байтах
    var NameID: UInt,      // Идентификатор названия настраиваемого параметра
    var DateEdit: UInt,    // Дата редактирования в формате UNIX (секунды от 1970)
    var data: ByteArray = ByteArray(PARAM_BUF_SIZE.toInt()),    // Обобщенный буфер параметров работы переменной длины
) {

    companion object{
        const val PARAM_BUF_SIZE = 64
        const val SIZE_NO_BUF = 4 + 2 + 2 + 4 + 4 // = 16
        const val SIZE_FULL = SIZE_NO_BUF + PARAM_BUF_SIZE // = 80
    }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ParamCrcStruct

        if (Crc32 != other.Crc32) return false
        if (ParamType != other.ParamType) return false
        if (ParamSize != other.ParamSize) return false
        if (NameID != other.NameID) return false
        if (DateEdit != other.DateEdit) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = Crc32.hashCode()
        result = 31 * result + ParamType.hashCode()
        result = 31 * result + ParamSize.hashCode()
        result = 31 * result + NameID.hashCode()
        result = 31 * result + DateEdit.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

// Размер целостностной структуры параметров работы без обобщенного буфера, байт
val PARAMCRC_SIZE_NO_BUF = ParamCrcStruct.SIZE_FULL - PARAM_BUF_SIZE.toInt()
// Максимальный размер в байтах обменной структуры параметров работы PRAM
val PARAM_USB_STRUCT_MAX_SIZE = MSD_DATA_MAX_SIZE - PARAM_BUF_SIZE.toUShort()  // Старое значение

class ParamUsbStruct {

    var structType: UShort = 0u
    var structSize: UShort = 0u
    var paramCrc: UInt = 0u

    /** Внутренний union: либо ParamCrcStruct, либо необработанный массив байт */
    var union = UnionPart()

    /**
     * Внутренний класс, представляющий union внутри ParamUsbStruct.
     */
    class UnionPart {
        private var buffer = ByteBuffer
            .allocate(PARAM_USB_STRUCT_MAX_SIZE.toInt())
            .order(ByteOrder.LITTLE_ENDIAN)

        var st = ParamCrcStruct( // TODO:SR
            Crc32 = TODO(),
            ParamType = TODO(),
            ParamSize = TODO(),
            NameID = TODO(),
            DateEdit = TODO(),
            data = TODO()
        )
        var data: ByteArray
            get() = buffer.array()
            set(value) {
                require(value.size.toUInt() == PARAM_USB_STRUCT_MAX_SIZE) {
                    "Expected $PARAM_USB_STRUCT_MAX_SIZE bytes, got ${value.size}"
                }
                buffer.clear()
                buffer.put(value)
                buffer.flip()
            }

        fun toByteArray(): ByteArray = buffer.array()
        fun loadFrom(bytes: ByteArray) {
            require(bytes.size.toUInt() == PARAM_USB_STRUCT_MAX_SIZE)
            buffer.clear()
            buffer.put(bytes)
            buffer.flip()
        }
    }
}

// Максимальный размер кольцевого буфера аналогового сигнала ФЭУ в байтах
val TEMP_BUF_SIZE: UShort = (MSD_DATA_MAX_SIZE - 64u).toUShort()

data class PramPowStruct(
    var structType: UShort,
    var structSize: UShort,
    var maxSize: UShort,
    var validSize: UShort,
    var bufDim: UByte,
    var powFixCount: UInt,
    var systemPhase: Int,
    var systemClock: UInt,
    var refCode: UInt,
    var powCurrent: Float,
    var powAverage: Float,
    var buf: ByteArray = ByteArray(TEMP_BUF_SIZE.toInt())
) {
    init {
        require(buf.size.toUShort() == TEMP_BUF_SIZE) {
            "buf must be exactly $TEMP_BUF_SIZE bytes"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PramPowStruct

        if (systemPhase != other.systemPhase) return false
        if (powCurrent != other.powCurrent) return false
        if (powAverage != other.powAverage) return false
        if (structType != other.structType) return false
        if (structSize != other.structSize) return false
        if (maxSize != other.maxSize) return false
        if (validSize != other.validSize) return false
        if (bufDim != other.bufDim) return false
        if (powFixCount != other.powFixCount) return false
        if (systemClock != other.systemClock) return false
        if (refCode != other.refCode) return false
        if (!buf.contentEquals(other.buf)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = systemPhase
        result = 31 * result + powCurrent.hashCode()
        result = 31 * result + powAverage.hashCode()
        result = 31 * result + structType.hashCode()
        result = 31 * result + structSize.hashCode()
        result = 31 * result + maxSize.hashCode()
        result = 31 * result + validSize.hashCode()
        result = 31 * result + bufDim.hashCode()
        result = 31 * result + powFixCount.hashCode()
        result = 31 * result + systemClock.hashCode()
        result = 31 * result + refCode.hashCode()
        result = 31 * result + buf.contentHashCode()
        return result
    }
}

data class PramTempStruct(
    var structType: UShort,
    var structSize: UShort,
    var maxSize: UShort,
    var validSize: UShort,
    var bufDim: UByte,
    var tempFixCount: UInt,
    var systemPhase: Int,
    var systemClock: UInt,
    var tempCode3: UShort,
    var tempCurrent: Float,
    var powAverage: Float,
    var tempAverage: Float,
    var buf: ByteArray = ByteArray(TEMP_BUF_SIZE.toInt())
) {
    init {
        require(buf.size.toUShort() == TEMP_BUF_SIZE) {
            "buf must be exactly $TEMP_BUF_SIZE bytes"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PramTempStruct

        if (systemPhase != other.systemPhase) return false
        if (tempCurrent != other.tempCurrent) return false
        if (powAverage != other.powAverage) return false
        if (tempAverage != other.tempAverage) return false
        if (structType != other.structType) return false
        if (structSize != other.structSize) return false
        if (maxSize != other.maxSize) return false
        if (validSize != other.validSize) return false
        if (bufDim != other.bufDim) return false
        if (tempFixCount != other.tempFixCount) return false
        if (systemClock != other.systemClock) return false
        if (tempCode3 != other.tempCode3) return false
        if (!buf.contentEquals(other.buf)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = systemPhase
        result = 31 * result + tempCurrent.hashCode()
        result = 31 * result + powAverage.hashCode()
        result = 31 * result + tempAverage.hashCode()
        result = 31 * result + structType.hashCode()
        result = 31 * result + structSize.hashCode()
        result = 31 * result + maxSize.hashCode()
        result = 31 * result + validSize.hashCode()
        result = 31 * result + bufDim.hashCode()
        result = 31 * result + tempFixCount.hashCode()
        result = 31 * result + systemClock.hashCode()
        result = 31 * result + tempCode3.hashCode()
        result = 31 * result + buf.contentHashCode()
        return result
    }
}

// Максимальный размер кольцевого буфера аналогового сигнала ФЭУ в байтах.
val TEMP_AHT21_BUF_SIZE: UShort = (MSD_DATA_MAX_SIZE - 64u).toUShort()

data class PramAht21Struct(
    /** Тип структуры - STRUCT_AHT21_DATA */
    var structType: UShort = 0u,

    /** Размер структуры в байтах */
    var structSize: UShort = 0u,

    /** Максимальное число элементов буфера buf */
    var maxSize: UShort = 0u,

    /** Количество действительных данных в буфере buf */
    var validSize: UShort = 0u,

    /** Размерность элементов буфера buf в байтах */
    var bufDim: UByte = 0u,

    /** Счётчик числа проведенных измерений температуры */
    var tempFixCount: UInt = 0u,

    /** Текущая измеренная температура ФЭУ в градусах Цельсия */
    var tempCurrent: Float = 0f,

    /** Текущая измеренная влажность в процентах */
    var humidity: Float = 0f,

    /** Текущая усредненная температура ФЭУ в градусах Цельсия */
    var tempAverage: Float = 0f,

    /** Буфер измеренных значений температуры ФЭУ */
    var buf: ByteArray = ByteArray(TEMP_AHT21_BUF_SIZE.toInt())
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PramAht21Struct

        if (tempCurrent != other.tempCurrent) return false
        if (humidity != other.humidity) return false
        if (tempAverage != other.tempAverage) return false
        if (structType != other.structType) return false
        if (structSize != other.structSize) return false
        if (maxSize != other.maxSize) return false
        if (validSize != other.validSize) return false
        if (bufDim != other.bufDim) return false
        if (tempFixCount != other.tempFixCount) return false
        if (!buf.contentEquals(other.buf)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = tempCurrent.hashCode()
        result = 31 * result + humidity.hashCode()
        result = 31 * result + tempAverage.hashCode()
        result = 31 * result + structType.hashCode()
        result = 31 * result + structSize.hashCode()
        result = 31 * result + maxSize.hashCode()
        result = 31 * result + validSize.hashCode()
        result = 31 * result + bufDim.hashCode()
        result = 31 * result + tempFixCount.hashCode()
        result = 31 * result + buf.contentHashCode()
        return result
    }
}

enum class AMRange(val value: UShort) {
    AM_RANGE_0(0u),
    AM_RANGE_1(1u),
    AM_RANGE_2(2u),
    AM_RANGE_3(3u),
    AM_RANGE_4(4u),
    AM_RANGE_5(5u),
    AM_RANGE_6(6u),
    AM_RANGE_7(7u),

    AM_RANGE__NUM_MASK(0x000Fu),
    AM_RANGE_VD(0x0020u),
    AM_RANGE_TD(0x0040u),
    AM_RANGE_AE(0x0080u),
    AM_RANGE_MODE_MASK(0x00F0u),

    AM_RANGE_UV375(0x0000u),
    AM_RANGE_UV270(0x0200u),
    AM_RANGE_UV_ALL(0x0F00u),
    AM_RANGE_UV_MASK(0x0F00u);

    companion object {
        fun fromValue(value: UShort): AMRange? =
            entries.firstOrNull { it.value == value }
    }
}

val MIN: UByte = AMRange.AM_RANGE_2.value.toUByte()
val MAX: UByte = AMRange.AM_RANGE_7.value.toUByte()
val AMOUNT: UByte = (AMRange.AM_RANGE_7.value - AMRange.AM_RANGE_0.value + 1u).toUByte()

val AM_BUF_SIZE: UShort = (MSD_DATA_MAX_SIZE - 128u).toUShort()

data class PramUvStruct(
    var structType: UShort,          // Тип структуры - STRUCT_UV_DATA
    var structSize: UShort,          // Размер структуры в байтах
    var validSize: UShort,           // Количество действительных данных в буфере buf
    var maxSize: UByte,              // Максимальное число элементов буфера buf
    var bufDim: UByte,               // Размерность элементов буфера buf в байтах
    var uvFixCount: UInt,            // Счётчик измерений уровня УФ
    var systemPhase: Int,            // Фаза последнего измерения относительно системного такта
    var systemClock: UInt,           // Частота системного такта в Гц
    var uvCode: UShort,              // Одиночный код АЦП для уровня УФ
    var uvCode1: Float,              // Усреднённый код АЦП за одно измерение
    var uvRange: UByte,              // Диапазон измерений (см. enum class AmRange)
    var uvOffset: Float,             // Смещение усилителя, Вольт
    var uvLevel: Float,              // Уровень сигнала ФЭУ без смещения, В
    var uvValue: Float,              // Нормированный уровень УФ, Вт/см^2
    var uvAverage: Float,            // Усреднённый УФ уровень, Вт/см^2
    var uvCalibr: Float,             // Коэффициент перевода Вольт → мкВт/см^2
    var tempAverage: Float,          // Температура МК, °C
    var powAverage: Float,           // Аналоговое питание МК, В
    var powerRange: Float,           // Питание ФЭУ для диапазона, В
    var powerCurrent: Float,         // Текущее питание ФЭУ после термокомпенсации, В
    var buf: ByteArray = ByteArray(AM_BUF_SIZE.toInt())              // Буфер нормированных значений сигнала ФЭУ
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PramUvStruct

        if (systemPhase != other.systemPhase) return false
        if (uvCode1 != other.uvCode1) return false
        if (uvOffset != other.uvOffset) return false
        if (uvLevel != other.uvLevel) return false
        if (uvValue != other.uvValue) return false
        if (uvAverage != other.uvAverage) return false
        if (uvCalibr != other.uvCalibr) return false
        if (tempAverage != other.tempAverage) return false
        if (powAverage != other.powAverage) return false
        if (powerRange != other.powerRange) return false
        if (powerCurrent != other.powerCurrent) return false
        if (structType != other.structType) return false
        if (structSize != other.structSize) return false
        if (validSize != other.validSize) return false
        if (maxSize != other.maxSize) return false
        if (bufDim != other.bufDim) return false
        if (uvFixCount != other.uvFixCount) return false
        if (systemClock != other.systemClock) return false
        if (uvCode != other.uvCode) return false
        if (uvRange != other.uvRange) return false
        if (!buf.contentEquals(other.buf)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = systemPhase
        result = 31 * result + uvCode1.hashCode()
        result = 31 * result + uvOffset.hashCode()
        result = 31 * result + uvLevel.hashCode()
        result = 31 * result + uvValue.hashCode()
        result = 31 * result + uvAverage.hashCode()
        result = 31 * result + uvCalibr.hashCode()
        result = 31 * result + tempAverage.hashCode()
        result = 31 * result + powAverage.hashCode()
        result = 31 * result + powerRange.hashCode()
        result = 31 * result + powerCurrent.hashCode()
        result = 31 * result + structType.hashCode()
        result = 31 * result + structSize.hashCode()
        result = 31 * result + validSize.hashCode()
        result = 31 * result + maxSize.hashCode()
        result = 31 * result + bufDim.hashCode()
        result = 31 * result + uvFixCount.hashCode()
        result = 31 * result + systemClock.hashCode()
        result = 31 * result + uvCode.hashCode()
        result = 31 * result + uvRange.hashCode()
        result = 31 * result + buf.contentHashCode()
        return result
    }
}

data class FmFrecStruct(
    var structType: UShort,     // Тип структуры - STRUCT_FM_FREC_DATA
    var structSize: UShort,     // Размер структуры в байтах
    var tempCurrent: Float,     // Текущая измеренная температура в °C
    var tempAverage: Float,     // Усреднённая температура МК в °C
    var dozeKoef: Float,        // Коэффициент перевода частоты в дозу (Рентген/Гц)
    var frecCurrent: Float,     // Текущая измеренная частота в Гц
    var frecAverage: Float      // Усреднённая частота МК в Гц
){
    companion object {
        const val SIZE_BYTES: Int =
            2 + // structType: UShort
                    2 + // structSize: UShort
                    4 + // tempCurrent: Float
                    4 + // tempAverage: Float
                    4 + // dozeKoef: Float
                    4 + // frecCurrent: Float
                    4   // frecAverage: Float

        @OptIn(ExperimentalUnsignedTypes::class)
        fun fromByteArray(data: UByteArray): FmFrecStruct {
            if(data.size < SIZE_BYTES)
            {
                // Log.d("TEST","Недостаточно данных: требуется $SIZE_BYTES байт, получено ${data.size}")
                return FmFrecStruct(
                    structType = 0u,
                    structSize = 0u,
                    tempCurrent = -2f,
                    tempAverage = -2f,
                    dozeKoef = -2f,
                    frecCurrent = -2f,
                    frecAverage = -2f
                )
            }

            val byteArray = data.asByteArray()
            val buffer = ByteBuffer.wrap(byteArray, 0, SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)

            return FmFrecStruct(
                structType = buffer.short.toUShort(),
                structSize = buffer.short.toUShort(),
                tempCurrent = buffer.float,
                tempAverage = buffer.float,
                dozeKoef = buffer.float,
                frecCurrent = buffer.float,
                frecAverage = buffer.float
            )
        }
    }
}

val PHASES_BUF_SIZE: UShort = (MSD_DATA_MAX_SIZE - 64u).toUShort()

data class FmPhasesStruct(
    var structType: UShort,     // Тип структуры - STRUCT_FM_PHASES_DATA
    var structSize: UShort,     // Размер структуры в байтах
    var maxSize: UShort,        // Максимальное число элементов буфера Phases
    var validSize: UShort,      // Количество действительных данных в буфере Phases
    var fixCount: UInt,         // Счётчик количества зафиксированных фаз таймеров
    var systemPhase: Int,       // Фаза последнего измерения относительно системного такта
    var phasesDim: UByte,       // Размерность элементов буфера Phases в байтах
    var systemClock: UInt,      // Частота системного такта МК в Гц
    var powAverage: Float,      // Текущее усредненное аналоговое питание МК, Вольт
    var tempAverage: Float,     // Усредненная МК температура ФЭУ в градусах Цельсия
    var powerHV: Float,         // Заданное питание ФЭУ при 25 °C, Вольт
    var powerHVComp: Float,     // Текущее питание ФЭУ после термокомпенсации, Вольт
    var dozeKoef: Float,        // Коэффициент перевода частоты в дозу (Рентген/Герц)
    val phases: ByteArray = ByteArray(PHASES_BUF_SIZE.toInt())      // Буфер фаз, размер PHASES_BUF_SIZE
) {
    companion object {
        var SIZE_BYTES: Int =
            2 +  // structType: UShort
            2 +  // structSize: UShort
            2 +  // maxSize: UShort
            2 +  // validSize: UShort
            4 +  // fixCount: UInt
            4 +  // systemPhase: Int
            1 +  // phasesDim: UByte
            3 +  // padding (для выравнивания после UByte -> чтобы systemClock был кратен 4)
            4 +  // systemClock: UInt
            4 +  // powAverage: Float
            4 +  // tempAverage: Float
            4 +  // powerHV: Float
            4 +  // powerHVComp: Float
            4 +  // dozeKoef: Float
            PHASES_BUF_SIZE.toInt() // phases: ByteArray

        @OptIn(ExperimentalUnsignedTypes::class)
        fun fromByteArrayToFmPhasesStruct(data: UByteArray): FmPhasesStruct {
            if (data.size < SIZE_BYTES) {
                return FmPhasesStruct(
                    structType = 0u,
                    structSize = 0u,
                    maxSize = 0u,
                    validSize = 0u,
                    fixCount = 0u,
                    systemPhase = 0,
                    phasesDim = 0u,
                    systemClock = 0u,
                    powAverage = -1f,
                    tempAverage = -1f,
                    powerHV = -1f,
                    powerHVComp = -1f,
                    dozeKoef = -1f,
                    phases = ByteArray(PHASES_BUF_SIZE.toInt())
                )
            }

            val byteArray = data.asByteArray()
            val buffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)

            val structType = buffer.short.toUShort()
            val structSize = buffer.short.toUShort()
            val maxSize = buffer.short.toUShort()
            val validSize = buffer.short.toUShort()
            val fixCount = buffer.int.toUInt()
            val systemPhase = buffer.int
            val phasesDim = buffer.get().toUByte()

            buffer.position(buffer.position() + 3) // padding after UByte for 4-byte alignment

            val systemClock = buffer.int.toUInt()
            val powAverage = buffer.float
            val tempAverage = buffer.float
            val powerHV = buffer.float
            val powerHVComp = buffer.float
            val dozeKoef = buffer.float

            val phases = ByteArray(PHASES_BUF_SIZE.toInt())
            buffer.get(phases, 0, phases.size.coerceAtMost(buffer.remaining()))

            return FmPhasesStruct(
                structType = structType,
                structSize = structSize,
                maxSize = maxSize,
                validSize = validSize,
                fixCount = fixCount,
                systemPhase = systemPhase,
                phasesDim = phasesDim,
                systemClock = systemClock,
                powAverage = powAverage,
                tempAverage = tempAverage,
                powerHV = powerHV,
                powerHVComp = powerHVComp,
                dozeKoef = dozeKoef,
                phases = phases
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FmPhasesStruct

        if (systemPhase != other.systemPhase) return false
        if (powAverage != other.powAverage) return false
        if (tempAverage != other.tempAverage) return false
        if (powerHV != other.powerHV) return false
        if (powerHVComp != other.powerHVComp) return false
        if (dozeKoef != other.dozeKoef) return false
        if (structType != other.structType) return false
        if (structSize != other.structSize) return false
        if (maxSize != other.maxSize) return false
        if (validSize != other.validSize) return false
        if (fixCount != other.fixCount) return false
        if (phasesDim != other.phasesDim) return false
        if (systemClock != other.systemClock) return false
        if (!phases.contentEquals(other.phases)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = systemPhase
        result = 31 * result + powAverage.hashCode()
        result = 31 * result + tempAverage.hashCode()
        result = 31 * result + powerHV.hashCode()
        result = 31 * result + powerHVComp.hashCode()
        result = 31 * result + dozeKoef.hashCode()
        result = 31 * result + structType.hashCode()
        result = 31 * result + structSize.hashCode()
        result = 31 * result + maxSize.hashCode()
        result = 31 * result + validSize.hashCode()
        result = 31 * result + fixCount.hashCode()
        result = 31 * result + phasesDim.hashCode()
        result = 31 * result + systemClock.hashCode()
        result = 31 * result + phases.contentHashCode()
        return result
    }
}

enum class UpdateEvent(val value: UInt) {
    OK(0x00000000u),
    INITIALIZED_OK(0x00000001u),
    UPDATE_CRC_OK(0x00000002u),
    FS_IS_MOUNTED(0x00000004u),
    SIG_OK(0x00000008u),
    BANK_ERRASE_OK(0x00000010u),
    PROGRAM_IS_WRITTER(0x00000020u),
    FIRMWARE_CRC_OK(0x00000040u),
    RTC_NOT_INIT(0x00000080u),
    IWDG_ERROR(0x00000100u),
    IWDG_ERROR2(0x00000200u),
    INIT_ERROR(0x00000400u),
    FUNC_PARAMS_ERROR(0x00000800u),
    CRC32_ERROR(0x00001000u),
    SIGN_TEST_ERROR(0x00002000u),
    UPDATE_CRC_ERROR(0x00004000u),
    FIRMWARE_CRC_ERROR(0x00008000u),
    NOT_CARD(0x00010000u),
    FS_UNMOUNTED(0x00020000u),
    PARAMS_COPY_ERROR(0x00040000u),
    PARAMS_VERIF_ERROR(0x00080000u),
    ID_COPY_ERROR(0x00100000u),
    PUB_KEY_VERIF_ERROR(0x00200000u),
    HASH_ERROR(0x00400000u),
    SIGN_FILE_OPEN_ERROR(0x00800000u),
    SIGN_FILE_READ_ERROR(0x01000000u),
    SIGN_ERROR(0x02000000u),
    SOFT_FILE_OPEN_ERROR(0x04000000u),
    SOFT_FILE_READ_ERROR(0x08000000u),
    FLASH_CLEAR_ERROR(0x10000000u),
    FIRMWARE_WRITE_ERROR(0x20000000u),
    FIRMWARE_NEW_ERROR(0x40000000u);

    companion object {
        /** Возвращает список всех флагов, установленных в [bitfield] */
        fun fromBitfield(bitfield: UInt): Set<UpdateEvent> {
            return UpdateEvent.entries.filter { bitfield and it.value != 0u }.toSet()
        }

        /** Объединяет несколько флагов в одно битовое значение */
        fun toBitfield(events: Collection<UpdateEvent>): UInt {
            return events.fold(0u) { acc, event -> acc or event.value }
        }
    }
}

/**
 * Максимальный размер данных в байтах, используемых для обмена с хостом V1.0.
 */
const val MSD_DATA_MAX_SIZE_V1: UShort = 0x1C0u // 0x180 0x1C0

/**
 * Максимальный размер кольцевого буфера фиксируемых фаз таймеров в байтах V1.0.
 */
val PHASES_BUF_SIZE_V1: UShort = (MSD_DATA_MAX_SIZE_V1 - 16u).toUShort()

data class FmPhaseStructV1(
    val structType: UShort,  //!< Тип структуры - STRUCT_FM_PHASE_DATA_V1
    val structSize: UShort,  //!< Размер структуры в байтах
    val maxSize: UShort,     //!< Максимальное число элементов буфера Phase
    val validSize: UShort,   //!< Количество действительных данных в буфере Phase
    val fixCount: UInt,      //!< Счётчик количества зафиксированных фаз таймеров
    val phaseDim: UByte,     //!< Размерность элементов буфера Phase в байтах
    val reserv: ByteArray = ByteArray(3),  //!< Поле выравнивания границы буфера Phase до 8 байт
    val phase: ByteArray = ByteArray(PHASES_BUF_SIZE_V1.toInt())    //!< Линейный буфер зафиксированных фаз таймеров
) {
    // Конструктор для создания с массивом данных
    constructor() : this(
        structType = 0u,
        structSize = 0u,
        maxSize = PHASES_BUF_SIZE_V1.toUShort(),
        validSize = 0u,
        fixCount = 0u,
        phaseDim = 0u,
        reserv = ByteArray(3),
        phase = ByteArray(PHASES_BUF_SIZE_V1.toInt())
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FmPhaseStructV1

        if (structType != other.structType) return false
        if (structSize != other.structSize) return false
        if (maxSize != other.maxSize) return false
        if (validSize != other.validSize) return false
        if (fixCount != other.fixCount) return false
        if (phaseDim != other.phaseDim) return false
        if (!reserv.contentEquals(other.reserv)) return false
        if (!phase.contentEquals(other.phase)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = structType.hashCode()
        result = 31 * result + structSize.hashCode()
        result = 31 * result + maxSize.hashCode()
        result = 31 * result + validSize.hashCode()
        result = 31 * result + fixCount.hashCode()
        result = 31 * result + phaseDim.hashCode()
        result = 31 * result + reserv.contentHashCode()
        result = 31 * result + phase.contentHashCode()
        return result
    }
}

/**
 * Структура данных для измерений температуры (версия 1.0)
 */
data class PramTempStructV1(
    val structType: UShort,    //!< Тип структуры - STRUCT_TEMP_DATA_V1
    val structSize: UShort,    //!< Размер структуры в байтах
    val tempFixCount: UInt,    //!< Счётчик количества измерений температуры
    val tempCode: UInt,        //!< Текущий код АЦП температурного датчика
    val reserv: ByteArray = ByteArray(4),    //!< Поле выравнивания границы Temp до 8 байт
    val temp: Float,           //!< Текущая измеренная температура в градусах Цельсия
    val tempAv: Float,         //!< Текущая усредненная МК температура в градусах Цельсия
    val refAverage: Float      //!< Текущее усредненное МК текущее опорное напряжение в Вольтах
) {
    // Конструктор для создания с массивом данных
    constructor() : this(
        structType = 0u,
        structSize = 0u,
        tempFixCount = 0u,
        tempCode = 0u,
        reserv = ByteArray(4),
        temp = 0f,
        tempAv = 0f,
        refAverage = 0f
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PramTempStructV1

        if (temp != other.temp) return false
        if (tempAv != other.tempAv) return false
        if (refAverage != other.refAverage) return false
        if (structType != other.structType) return false
        if (structSize != other.structSize) return false
        if (tempFixCount != other.tempFixCount) return false
        if (tempCode != other.tempCode) return false
        if (!reserv.contentEquals(other.reserv)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = temp.hashCode()
        result = 31 * result + tempAv.hashCode()
        result = 31 * result + refAverage.hashCode()
        result = 31 * result + structType.hashCode()
        result = 31 * result + structSize.hashCode()
        result = 31 * result + tempFixCount.hashCode()
        result = 31 * result + tempCode.hashCode()
        result = 31 * result + reserv.contentHashCode()
        return result
    }
}

data class FmFrecStructV1(
    val structType: UShort,  //!< Тип структуры - STRUCT_FM_FREC_DATA_V1
    val structSize: UShort,  //!< Размер структуры в байтах
    val frec: Float          //!< Текущая измеренная частота в Герцах
) {
    constructor() : this(
        structType = 0u,
        structSize = 0u,
        frec = 0f
    )
}

lateinit var lospId: LospBoardStruct