package com.example.usb_sector_rw.msd

import SECTOR_ANSWER
import SECTOR_CMD
import SectorAnswer
import SectorCmd
import android.content.Context
import com.example.usb_sector_rw.losp.*
import com.example.usb_sector_rw.*

/**
 * Класс для взаимодействия с устройствами ЛОСП класса MSD (Mass Storage Device).
 * Реализует функции чтения, записи и управления устройствами хранения данных.
 * @author Sergey Rundygin
 */
class LospDev(private val context: Context) {

    /**
     * Чтение данных из сектора MSD.
     * @param sector Номер сектора (0-255).
     * @param buf Массив, в который будут записаны данные (512 байт).
     * @return true, если операция прошла успешно, иначе false.
     */
    fun sectorRead(sector: u32, buf: ByteArray, log: (String) -> Unit): Boolean {
        val usbAccess = UsbSectorAccess(context)

        if (!usbAccess.connect()) {
//            log("Failed to connect to USB device")
            return false
        }

        if (!usbAccess.readCapacity()) {
//            log("Failed to read device capacity")
            usbAccess.close()
            return false
        }

        val result = usbAccess.readSectorBytes(sector.toLong(), 0, 512)
        return if (result != null && result.size == 512) {
            System.arraycopy(result, 0, buf, 0, 512)
//            log("Successfully read from sector $sector");
//            log("Successfully read from sector $sector:\n${buf.joinToString(" ") { "%02X".format(it) }}")
            usbAccess.close()
            true
        } else {
//            log("Read failed")
            val sense = usbAccess.requestSense()
//            if (sense != null) {
//                log("REQUEST SENSE: ${sense.joinToString(" ") { "%02X".format(it) }}")
//            }
            usbAccess.close()
            false
        }
    }

    /**
     * Запись данных в сектор MSD.
     * @param sector Номер сектора (0-255).
     * @param buf Массив данных длиной 512 байт.
     */
    fun sectorWrite(sector: u32, buf: ByteArray, log: (String) -> Unit): Boolean {
        val usbAccess = UsbSectorAccess(context)

        if (!usbAccess.connect()) {
//            log("Failed to connect to USB device")
            return false;
        }

        if (!usbAccess.readCapacity()) {
//            log("Failed to read device capacity")
            usbAccess.close()
            return false;
        }

        val blockSize = usbAccess.blockSize
        val dataToWrite = ByteArray(blockSize) { 0 }
        System.arraycopy(buf, 0, dataToWrite, 0, minOf(buf.size, blockSize))

        val success = usbAccess.writeSectors(sector.toLong(), dataToWrite)
        if (success) {
//            log("Successfully wrote to sector $sector");
//            log("Successfully wrote to sector $sector:\n${buf.joinToString(" ") { "%02X".format(it) }}")
        } else {
//            log("Failed to write to sector $sector")
            val sense = usbAccess.requestSense()
//            if (sense != null) {
//                val senseStr = sense.joinToString(" ") { "%02X".format(it) }
//                log("REQUEST SENSE: $senseStr")
//            } else {
//                log("REQUEST SENSE failed")
//            }
        }

        usbAccess.close()

        return true
    }

    /**
     * Передача команды МК устройства ЛОСП.
     * @param cmd Команда для выполнения.
     */
    fun lospExecCmd(cmd: SectorCmd, log: (String) -> Unit) : Boolean {
        return sectorWrite( SECTOR_CMD, cmd.getRawData(), log);
    }

    /**
     * Получение ответа на команду от МК.
     * @param cmd Код ранее переданной команды.
     * @param answer Объект для записи ответа от устройства.
     */
    fun getLospAnswer(cmd: UInt, answer: SectorAnswer, log: (String) -> Unit) : Boolean {
        var attempts = 0
        val readBuffer = ByteArray(512)
        while (attempts < 222 && (RetFromPram.fromValue(answer.ret) == RetFromPram.PRAM_MSD_BUSY || attempts == 0)) {
            attempts++
            if (!sectorRead(SECTOR_ANSWER, readBuffer, log)) {
//                log("Error reading sector $cmd")
                return false;
            }
        }

//        log("received buff:\n${readBuffer.joinToString(" ") { "%02X".format(it) }} ")

        answer.loadFromRawData(readBuffer);

        if (RetFromPram.fromValue(answer.ret) == RetFromPram.PRAM_MSD_NOT_DATA) {
//            log("No data received, exit");
            return false;
        }

        if (RetFromPram.fromValue(answer.ret) == RetFromPram.PRAM_MSD_BUSY) {
//            log("Device is still busy with command $cmd.")
            return false;
        }

        if (answer.cmd != cmd) {
//            log("Received command ${answer.cmd} doesn't match expected command $cmd.")
            return false;
        }

        if (RetFromPram.fromValue(answer.ret) != RetFromPram.PRAM_MSD_OK) {
//            log("Error ${answer.ret} received for command $cmd.")
            return false;
        }

        return true;
    }

    /**
     * Получение количества доступных MSD-устройств.
     * @return Число устройств.
     */
    fun msdBoardCount(): UByte {
        // TODO: Реализовать подсчёт устройств
        return 0u
    }

    /**
     * Открытие MSD-устройства по номеру.
     * @param devNum Порядковый номер устройства.
     * @return true, если устройство успешно открыто.
     */
    fun msdBoardOpen(devNum: UByte): Boolean {
        // TODO: Реализовать открытие устройства
        return false
    }
}