package com.example.usb_sector_rw

import android.content.Context
import android.hardware.usb.*
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbSectorAccess(
    private val context: Context,
    private val targetLun: Byte = 0
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null
    private var cbwTag = 1

    companion object {
        private const val TAG = "UsbSectorAccess"
        private const val BLOCK_SIZE_DEFAULT = 512

        private const val CBW_SIGNATURE = 0x43425355
        private const val CSW_SIGNATURE = 0x53425355

        private const val DIR_IN = 0x80
        private const val DIR_OUT = 0x00
    }

    var blockSize = BLOCK_SIZE_DEFAULT
        private set
    var maxLBA: Long = 0
        private set

    /**
     * Connects to the first available USB Mass Storage device, claims its interface,
     * sets up bulk endpoints, and performs initial SCSI INQUIRY and READ CAPACITY commands.
     *
     * @return true if the connection and initialization succeed, false otherwise.
     */
    fun connect(): Boolean {
        for (device in usbManager.deviceList.values) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)

                val isMassStorage = (
                        intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                                intf.interfaceSubclass == 6 &&
                                intf.interfaceProtocol == 80
                        )

                if (!isMassStorage) continue

                if (!usbManager.hasPermission(device)) {
                    Log.w(TAG, "No permission for USB device: ${device.deviceName}")
                    return false
                }

                val conn = usbManager.openDevice(device)
                conn?.claimInterface(intf, true) ?: return false
                connection = conn

                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep
                        else bulkOut = ep
                    }
                }

                if (bulkIn == null || bulkOut == null) {
                    Log.e(TAG, "Bulk endpoints not found")
                    return false
                }

                val inquiry = scsiInquiry()
                Log.d(TAG, "INQUIRY: ${inquiry?.decodeToString()?.trim()}")
                readCapacity()
                return true
            }
        }

        Log.w(TAG, "No USB Mass Storage device found")
        return false
    }

    /**
     * Reads the total size of the USB mass storage device in sectors.
     * This is derived from the maximum LBA and the block size.
     *
     * @return the total number of sectors on the device.
     */
    fun getTotalSectors(): Long {
        return maxLBA + 1 // LBA is zero-based, so the total number of sectors is maxLBA + 1
    }

    /**
     * Closes the current USB device connection, if any.
     * Releases all associated resources.
     */
    fun close() {
        connection?.close()
        connection = null
    }

    /**
     * Sends a SCSI INQUIRY (0x12) command to retrieve basic device information such as vendor, product, and revision.
     *
     * @return a byte array of INQUIRY data (typically 36 bytes), or null if the command fails.
     */
    fun scsiInquiry(): ByteArray? {
        val cmd = ByteArray(6)
        cmd[0] = 0x12 // INQUIRY
        cmd[4] = 36   // Allocation length

        val cbw = createCBW(
            dataLength = 36,
            direction = DIR_IN,
            lun = targetLun,
            command = cmd
        )

        val buffer = ByteArray(36)
        if (!sendCommand(cbw, buffer, DIR_IN)) return null
        return buffer
    }

    /**
     * Sends a SCSI READ CAPACITY (10) (0x25) command to determine the maximum Logical Block Address (LBA)
     * and block size of the connected USB mass storage device.
     *
     * @return true if capacity was read successfully, false on error.
     */
    fun readCapacity(): Boolean {
        val cmd = ByteArray(10)
        cmd[0] = 0x25.toByte() // READ CAPACITY (10)

        val cbw = createCBW(
            dataLength = 8,
            direction = DIR_IN,
            lun = targetLun,
            command = cmd
        )

        val buffer = ByteArray(8)
        if (!sendCommand(cbw, buffer, DIR_IN)) return false

        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
        maxLBA = (bb.int.toLong() and 0xFFFFFFFFL)
        blockSize = bb.int
        Log.d(TAG, "Capacity: maxLBA=$maxLBA, blockSize=$blockSize")
        return true
    }

    /**
     * Sends a SCSI REQUEST SENSE (0x03) command to retrieve detailed error or status information
     * following a failed SCSI command.
     *
     * @return a byte array containing the sense data (typically 18 bytes), or null if the command fails.
     */
    fun requestSense(): ByteArray? {
        val cmd = ByteArray(6)
        cmd[0] = 0x03 // REQUEST SENSE
        cmd[4] = 18   // Allocation length

        val cbw = createCBW(
            dataLength = 18,
            direction = DIR_IN,
            lun = targetLun,
            command = cmd
        )

        val buffer = ByteArray(18)
        if (!sendCommand(cbw, buffer, DIR_IN)) return null
        Log.w(TAG, "SENSE DATA: ${buffer.joinToString(" ") { "%02X".format(it) }}")
        return buffer
    }

    /**
     * Creates a Command Block Wrapper (CBW) for a SCSI command to be sent over USB Bulk-Only Transport.
     *
     * @param dataLength the expected number of bytes to transfer (read or write).
     * @param direction the direction of data transfer (DIR_IN or DIR_OUT).
     * @param lun the Logical Unit Number (LUN) to address on the device.
     * @param command the SCSI command block to send (usually 6 or 10 bytes).
     * @return a 31-byte CBW ready to be sent over USB.
     */
    private fun createCBW(
        dataLength: Int,
        direction: Int,
        lun: Byte,
        command: ByteArray
    ): ByteArray {
        val cbw = ByteArray(31)
        val bb = ByteBuffer.wrap(cbw).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(CBW_SIGNATURE)
        bb.putInt(cbwTag++)
        bb.putInt(dataLength)
        bb.put(if (direction == DIR_IN) 0x80.toByte() else 0x00)
        bb.put(lun)
        bb.put(command.size.toByte())
        bb.put(command)
        return cbw
    }

    /**
     * Sends a SCSI command to the device using the Bulk-Only Transport protocol,
     * including CBW transmission, optional data phase, and CSW reception.
     *
     * @param cbw the Command Block Wrapper.
     * @param data the data buffer for data phase (read or write), or null if no data phase.
     * @param direction the direction of data transfer (DIR_IN for read, DIR_OUT for write).
     * @return true if the entire command sequence succeeds, false otherwise.
     */
    private fun sendCommand(cbw: ByteArray, data: ByteArray?, direction: Int): Boolean {
        val out = bulkOut ?: return false
        val `in` = bulkIn ?: return false
        val conn = connection ?: return false

        // Send CBW
        if (conn.bulkTransfer(out, cbw, cbw.size, 2000) < 0) {
            Log.e(TAG, "CBW send failed")
            return false
        }

        // Send or receive data if needed
        if (data != null && data.isNotEmpty()) {
            val len = if (direction == DIR_IN)
                conn.bulkTransfer(`in`, data, data.size, 5000)
            else
                conn.bulkTransfer(out, data, data.size, 5000)

            if (len < 0) {
                Log.e(TAG, "Data transfer failed, calling REQUEST SENSE")
                requestSense()
                return false
            }
        }

        // Read CSW
        val csw = ByteArray(13)
        val cswLen = conn.bulkTransfer(`in`, csw, csw.size, 2000)
        if (cswLen != 13 || ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN).int != CSW_SIGNATURE) {
            Log.e(TAG, "Invalid CSW")
            return false
        }

        val status = csw[12].toInt()
        if (status != 0) {
            Log.w(TAG, "Command failed, status=$status")
            requestSense()
            return false
        }
        return true
    }

    /**
     * Reads one or more 512-byte sectors from the USB mass storage device using SCSI READ(10).
     *
     * @param lba the starting Logical Block Address to read from.
     * @param count the number of sectors to read (1–255).
     * @return a byte array containing the read data, or null if the read fails.
     */
    fun readSectors(lba: Long, count: Int): ByteArray? {
        if (count <= 0 || count > 255) return null // ограничение SCSI READ(10)

        val totalSize = count * blockSize
        val buffer = ByteArray(totalSize)

        val cmd = ByteArray(10)
        cmd[0] = 0x28.toByte() // READ(10)
        cmd[2] = (lba shr 24).toByte()
        cmd[3] = (lba shr 16).toByte()
        cmd[4] = (lba shr 8).toByte()
        cmd[5] = lba.toByte()
        cmd[7] = (count shr 8).toByte()
        cmd[8] = count.toByte()

        val cbw = createCBW(
            dataLength = totalSize,
            direction = DIR_IN,
            lun = targetLun,
            command = cmd
        )

        return if (sendCommand(cbw, buffer, DIR_IN)) buffer else null
    }

    /**
     * Writes one or more 512-byte sectors to the USB mass storage device using SCSI WRITE(10).
     *
     * @param lba the starting Logical Block Address to write to.
     * @param data the data to write, whose length must be a multiple of the block size.
     * @return true if the write operation succeeds, false otherwise.
     */
    fun writeSectors(lba: Long, data: ByteArray): Boolean {
        val count = data.size / blockSize
        if (data.size % blockSize != 0 || count == 0 || count > 255) return false

        val cmd = ByteArray(10)
        cmd[0] = 0x2A.toByte() // WRITE(10)
        cmd[2] = (lba shr 24).toByte()
        cmd[3] = (lba shr 16).toByte()
        cmd[4] = (lba shr 8).toByte()
        cmd[5] = lba.toByte()
        cmd[7] = (count shr 8).toByte()
        cmd[8] = count.toByte()

        val cbw = createCBW(
            dataLength = data.size,
            direction = DIR_OUT,
            lun = targetLun,
            command = cmd
        )

        return sendCommand(cbw, data, DIR_OUT)
    }

    /**
     * Partially overwrites one or more sectors starting at the specified LBA, beginning at a given byte offset.
     * If the data exceeds the remaining bytes in the first sector, it continues into subsequent sectors.
     *
     * @return Result.success("OK") если успешно, иначе Result.failure(Exception("сообщение"))
     */
    fun overwriteSectorBytes(lba: Long, offset: Int, newData: ByteArray): Result<String> {
        if (offset < 0 || newData.isEmpty()) {
            val msg = "Invalid offset=$offset or data is empty"
            Log.e(TAG, msg)
            return Result.failure(IllegalArgumentException(msg))
        }

        val totalBytes = offset + newData.size
        val sectorsNeeded = (totalBytes + blockSize - 1) / blockSize
        var dataOffset = 0

        Log.d(TAG, "overwriteSectorBytes: offset=$offset, newData=${newData.size}, sectors=$sectorsNeeded")

        for (i in 0 until sectorsNeeded) {
            val sectorLba = lba + i
            val sectorData = readSectors(sectorLba, 1)

            if (sectorData == null || sectorData.size != blockSize) {
                val msg = "Failed to read sector $sectorLba"
                Log.e(TAG, msg)
                return Result.failure(RuntimeException(msg))
            }

            val sectorOffset = if (i == 0) offset else 0
            val bytesAvailable = blockSize - sectorOffset
            val bytesToCopy = minOf(newData.size - dataOffset, bytesAvailable)

            if (bytesToCopy <= 0) break

            try {
                System.arraycopy(newData, dataOffset, sectorData, sectorOffset, bytesToCopy)
            } catch (e: Exception) {
                val msg = "Array copy failed at sector $sectorLba: ${e.message}"
                Log.e(TAG, msg)
                return Result.failure(e)
            }

            val success = writeSectors(sectorLba, sectorData)
            if (!success) {
                val msg = "Failed to write sector $sectorLba"
                Log.e(TAG, msg)
                return Result.failure(RuntimeException(msg))
            }

            Log.d(TAG, "Sector $sectorLba written: copied $bytesToCopy bytes from newData[$dataOffset]")
            dataOffset += bytesToCopy
        }

        return Result.success("Write successful")
    }

    /**
     * Reads a specific number of bytes from the given LBA and byte offset.
     * The data may span multiple sectors if necessary.
     *
     * @param lba the starting Logical Block Address (sector) to read from.
     * @param offset the byte offset within the first sector.
     * @param length the number of bytes to read.
     * @return a ByteArray containing the requested data, or null on error.
     */
    fun readSectorBytes(lba: Long, offset: Int, length: Int): ByteArray? {
        if (offset < 0 || length <= 0) {
            Log.e(TAG, "Invalid offset or length")
            return null
        }

        val endOffset = offset + length
        val sectorsNeeded = (endOffset + blockSize - 1) / blockSize

        val rawData = readSectors(lba, sectorsNeeded)
        if (rawData == null || rawData.size < endOffset) {
            Log.e(TAG, "Failed to read $sectorsNeeded sectors from LBA=$lba or insufficient data")
            return null
        }

        return rawData.copyOfRange(offset, offset + length)
    }
}