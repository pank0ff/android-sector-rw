package com.example.usb_sector_rw

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.app.PendingIntent
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * MainActivity provides a user interface for reading and writing raw sectors
 * to a USB Mass Storage device connected via Android's USB Host API.
 *
 * The activity allows users to:
 * - Scan and list available USB devices
 * - Request permission for USB access
 * - Read a sector and display its hexadecimal content
 * - Write user data to a specific sector
 * - Clear a sector by writing zero bytes
 *
 * USB sector access operations are delegated to the [UsbSectorAccess] class.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.usb_sector_rw.USB_PERMISSION"
    }

    private lateinit var sectorInput: EditText
    private lateinit var dataInput: EditText
    private lateinit var readBtn: Button
    private lateinit var writeBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var scanBtn: Button
    private lateinit var overwriteBtn: Button
    private lateinit var logText: TextView
    private lateinit var offsetInput: TextView

    /**
     * BroadcastReceiver that handles USB permission responses.
     *
     * Logs whether permission was granted or denied for the detected USB device.
     */
    private lateinit var usbReceiver: BroadcastReceiver

    /**
     * Initializes the user interface, sets up event handlers, registers USB permission receiver,
     * and defines logic for each button action: scan, read, write, and clear.
     *
     * @param savedInstanceState The previously saved state of the activity, if any.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sectorInput = findViewById(R.id.sectorInput)
        dataInput = findViewById(R.id.dataInput)
        readBtn = findViewById(R.id.readBtn)
        writeBtn = findViewById(R.id.writeBtn)
        clearBtn = findViewById(R.id.clearBtn)
        logText = findViewById(R.id.logText)
        scanBtn = findViewById(R.id.scanBtn)
        overwriteBtn = findViewById(R.id.overwriteBtn)
        offsetInput = findViewById(R.id.offsetInput)

        dataInput.addTextChangedListener(object : android.text.TextWatcher {
            private var previous = ""
            private var isFormatting = false
            private var deleting = false
            private var deleteIndex = 0

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                previous = s.toString()
                deleting = count > after
                deleteIndex = start
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: android.text.Editable?) {
                if (isFormatting || s == null) return

                val currentRaw = s.toString()
                val clean = currentRaw.replace(" ", "")
                    .uppercase()
                    .filter { it in "0123456789ABCDEF" }

                if (clean == previous.replace(" ", "").uppercase()) return

                isFormatting = true

                // Handle deletion: remove last hex char with its space if needed
                var adjustedClean = clean
                if (deleting && deleteIndex > 0 && previous.getOrNull(deleteIndex - 1) == ' ') {
                    val realIndex = deleteIndex - 2
                    if (realIndex >= 0 && realIndex < adjustedClean.length) {
                        adjustedClean = adjustedClean.removeRange(realIndex, realIndex + 1)
                    }
                }

                // Format as hex pairs with space
                val formatted = buildString {
                    for (i in adjustedClean.indices) {
                        append(adjustedClean[i])
                        if (i % 2 == 1 && i != adjustedClean.length - 1) append(' ')
                    }
                }

                // Set cursor position
                val newCursor = formatted.length.coerceAtMost(formatted.length)

                dataInput.setText(formatted)
                dataInput.setSelection(newCursor)

                isFormatting = false
            }
        })

        // Register USB permission receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device: UsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) ?: return
                if (intent.action == ACTION_USB_PERMISSION) {
                    synchronized(this) {
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            log("Permission granted for device ${device.deviceName}")
                        } else {
                            log("Permission denied for device ${device.deviceName}")
                        }
                    }
                }
            }
        }
        registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)

        // Read button click: attempts to read one sector from the USB device and display it in hex.
        readBtn.setOnClickListener {
            val usbAccess = UsbSectorAccess(this)

            if (!usbAccess.connect()) {
                log("Failed to connect to USB device")
                return@setOnClickListener
            }

            val sector = sectorInput.text.toString().toLongOrNull()
            if (sector == null) {
                log("Invalid sector number")
                usbAccess.close()
                return@setOnClickListener
            }

            if (!usbAccess.readCapacity()) {
                log("Failed to read device capacity")
                usbAccess.close()
                return@setOnClickListener
            }

            val data = usbAccess.readSectors(sector, 1)
            if (data != null) {
                val hex = data.joinToString(" ") { "%02X".format(it) }
                log("Read from sector $sector:\n$hex")
            } else {
                log("Read failed for sector $sector")

                val sense = usbAccess.requestSense()
                if (sense != null) {
                    val senseStr = sense.joinToString(" ") { "%02X".format(it) }
                    log("REQUEST SENSE: $senseStr")
                } else {
                    log("REQUEST SENSE failed")
                }
            }

            usbAccess.close()
        }

        // Write button click: writes user-entered UTF-8 text to the specified sector on the USB device.
        writeBtn.setOnClickListener {
            val usbAccess = UsbSectorAccess(this)

            if (!usbAccess.connect()) {
                log("Failed to connect to USB device")
                return@setOnClickListener
            }

            val sector = sectorInput.text.toString().toLongOrNull()
            val hexInput = dataInput.text.toString()

            if (sector == null || hexInput.isBlank()) {
                log("Invalid sector number or data")
                usbAccess.close()
                return@setOnClickListener
            }

            val inputBytes = parseHexInput(hexInput)
            if (inputBytes == null) {
                log("Invalid hex format. Use format: 00 1A FF ...")
                usbAccess.close()
                return@setOnClickListener
            }

            if (!usbAccess.readCapacity()) {
                log("Failed to read device capacity")
                usbAccess.close()
                return@setOnClickListener
            }

            val blockSize = usbAccess.blockSize
            val dataToWrite = ByteArray(blockSize) { 0 }
            System.arraycopy(inputBytes, 0, dataToWrite, 0, minOf(inputBytes.size, blockSize))

            val success = usbAccess.writeSectors(sector, dataToWrite)
            if (success) {
                log("Successfully wrote to sector $sector:\n$hexInput")
            } else {
                log("Failed to write to sector $sector")
                val sense = usbAccess.requestSense()
                if (sense != null) {
                    val senseStr = sense.joinToString(" ") { "%02X".format(it) }
                    log("REQUEST SENSE: $senseStr")
                } else {
                    log("REQUEST SENSE failed")
                }
            }

            usbAccess.close()
        }

        // Clear button click: overwrites the specified sector with zero bytes.
        clearBtn.setOnClickListener {
            val usbAccess = UsbSectorAccess(this)

            if (!usbAccess.connect()) {
                log("Failed to connect to USB device")
                return@setOnClickListener
            }

            val sector = sectorInput.text.toString().toLongOrNull()
            if (sector == null) {
                log("Invalid sector number")
                usbAccess.close()
                return@setOnClickListener
            }

            if (!usbAccess.readCapacity()) {
                log("Failed to read device capacity")
                usbAccess.close()
                return@setOnClickListener
            }

            val blockSize = usbAccess.blockSize
            val zeroData = ByteArray(blockSize) { 0 }

            val success = usbAccess.writeSectors(sector, zeroData)
            if (success) {
                log("Successfully cleared sector $sector")
            } else {
                log("Failed to clear sector $sector")
                val sense = usbAccess.requestSense()
                if (sense != null) {
                    val senseStr = sense.joinToString(" ") { "%02X".format(it) }
                    log("REQUEST SENSE: $senseStr")
                } else {
                    log("REQUEST SENSE failed")
                }
            }

            usbAccess.close()
        }

        // Scan button click: lists all connected USB devices and requests permission if needed.
        scanBtn.setOnClickListener {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val deviceList = usbManager.deviceList

            if (deviceList.isEmpty()) {
                log("No USB devices found.")
            } else {
                log("Detected ${deviceList.size} USB device(s).\n")

                for ((_, device) in deviceList) {
                    val hasPermission = usbManager.hasPermission(device)

                    if (!hasPermission) {
                        val permissionIntent = PendingIntent.getBroadcast(
                            this,
                            0,
                            Intent("android.hardware.usb.action.USB_PERMISSION"),
                            PendingIntent.FLAG_IMMUTABLE
                        )
                        usbManager.requestPermission(device, permissionIntent)
                        log("Requested permission for ${device.deviceName}")
                    }

                    val info = StringBuilder()
                    info.appendLine("Device: ${device.deviceName}")
                    info.appendLine("  Vendor ID      : ${device.vendorId}")
                    info.appendLine("  Product ID     : ${device.productId}")
                    info.appendLine("  Class          : ${device.deviceClass}")
                    info.appendLine("  Subclass       : ${device.deviceSubclass}")
                    info.appendLine("  Protocol       : ${device.deviceProtocol}")
                    info.appendLine("  Manufacturer   : ${device.manufacturerName ?: "Unknown"}")
                    info.appendLine("  Product Name   : ${device.productName ?: "Unknown"}")
                    info.appendLine("  Serial Number  : ${device.serialNumber ?: "Unknown"}")
                    info.appendLine("  Version        : ${device.version ?: "Unknown"}")
                    info.appendLine("  Configuration Count: ${device.configurationCount}")
                    for (i in 0 until device.configurationCount) {
                        val config = device.getConfiguration(i)
                        info.appendLine("    Configuration $i:")
                        for (j in 0 until config.interfaceCount) {
                            val intf = config.getInterface(j)
                            info.appendLine("      Interface $j: class=${intf.interfaceClass}, subclass=${intf.interfaceSubclass}, protocol=${intf.interfaceProtocol}")
                        }
                    }
                    log(info.toString())
                }
            }
        }

        overwriteBtn.setOnClickListener {
            val usbAccess = UsbSectorAccess(this)
            val lba = sectorInput.text.toString().toLongOrNull()
            val offset = offsetInput.text.toString().toIntOrNull()
            val dataHex = dataInput.text.toString().replace(" ", "")

            if (lba == null || offset == null) {
                logText.append("\nInvalid sector number or offset")
                return@setOnClickListener
            }

            val dataBytes = hexStringToByteArray(dataHex)
            if (dataBytes == null) {
                logText.append("\nInvalid hex data format")
                return@setOnClickListener
            }

            val success = usbAccess.overwriteSectorBytes(lba, offset, dataBytes)
            logText.append("\nOverwrite ${if (success) "successful" else "failed"} at LBA=$lba, offset=$offset")
        }
    }

    /**
     * Logs the provided message to the on-screen text view with a newline.
     *
     * @param message The message string to display in the log area.
     */
    private fun log(message: String) {
        logText.append("$message\n")
    }

    /**
     * Called when the activity is about to be destroyed.
     * Unregisters the USB permission receiver to prevent memory leaks.
     */
    override fun onDestroy() {
        super.onDestroy()
        // Unregister the USB permission receiver when the activity is destroyed
        unregisterReceiver(usbReceiver)
    }

    /**
     * Parses a hexadecimal input string into a ByteArray.
     *
     * The input string is expected to be a sequence of two-character hexadecimal values, separated by spaces.
     * The function:
     * - Trims the input to remove any leading or trailing whitespace.
     * - Replaces any consecutive whitespace with a single space.
     * - Splits the string into tokens based on spaces.
     * - Ensures each token consists of exactly two valid hexadecimal characters (0-9, A-F, a-f).
     * - Converts each token from hexadecimal to a byte.
     *
     * If the input contains invalid tokens (e.g., incorrect length or invalid characters),
     * the function returns null. If there is an exception during conversion, null is returned.
     *
     * @param input The input string containing space-separated hexadecimal values.
     * @return A ByteArray representing the parsed hexadecimal values, or null if the input is invalid.
     *
     * Example:
     *  - Input: "0A 1F FF"
     *  - Output: ByteArray with values [0x0A, 0x1F, 0xFF]
     */
    private fun parseHexInput(input: String): ByteArray? {
        val clean = input.trim().replace(Regex("\\s+"), " ")
        val tokens = clean.split(" ")
        if (tokens.any { it.length != 2 || !it.matches(Regex("[0-9A-Fa-f]{2}")) }) return null
        return try {
            tokens.map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Converts a hexadecimal string (e.g. "1A 2B 3C") into a ByteArray.
     *
     * @param hexString The hexadecimal string to convert.
     * @return The corresponding ByteArray, or null if the string is invalid.
     */
    private fun hexStringToByteArray(hexString: String): ByteArray? {
        val clean = hexString.trim().replace(" ", "").uppercase()
        val length = clean.length
        if (length % 2 != 0) {
            return null // Invalid string length (should be even)
        }

        val bytes = ByteArray(length / 2)
        for (i in 0 until length step 2) {
            val hexPair = clean.substring(i, i + 2)
            bytes[i / 2] = hexPair.toInt(16).toByte()
        }
        return bytes
    }
}