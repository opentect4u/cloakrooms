package com.synergic.cloakroom.data.print

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.synergic.cloakroom.domain.print.PrintResult
import com.synergic.cloakroom.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A paired Bluetooth thermal printer (Serial Port Profile). The BT_PRINTER setting names it (name
 * or address); when empty, the app picks the paired device that looks like a printer.
 */
@Singleton
class BluetoothPrinterPort @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : PrinterPort {

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Android 12+ asks the user for "Nearby devices" (BLUETOOTH_CONNECT); older versions do not. */
    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** A paired device, for the Printer setup screen. */
    data class PairedDevice(val name: String, val address: String, val looksLikePrinter: Boolean)

    enum class State { NO_BLUETOOTH, NO_PERMISSION, OFF, READY }

    fun state(): State = when {
        adapter == null -> State.NO_BLUETOOTH
        !hasPermission() -> State.NO_PERMISSION
        !isEnabled() -> State.OFF
        else -> State.READY
    }

    private fun isEnabled(): Boolean = try { adapter?.isEnabled == true } catch (e: SecurityException) { false }

    // Callers check hasPermission() first; a SecurityException still gives an empty list.
    @SuppressLint("MissingPermission")
    private fun bonded(): List<BluetoothDevice> = try {
        adapter?.takeIf { it.isEnabled }?.bondedDevices.orEmpty().toList()
    } catch (e: SecurityException) {
        emptyList()
    }

    @SuppressLint("MissingPermission")
    private fun looksLikePrinter(d: BluetoothDevice): Boolean = try {
        d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.IMAGING || d.name?.contains(PRINTER_NAME) == true
    } catch (e: SecurityException) {
        false
    }

    /** The phone's paired devices, printers first. Empty without permission or with Bluetooth off. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PairedDevice> {
        if (!hasPermission()) return emptyList()
        return bonded().map { d ->
            PairedDevice(name = runCatching { d.name }.getOrNull() ?: d.address, address = d.address, looksLikePrinter = looksLikePrinter(d))
        }.sortedWith(compareByDescending<PairedDevice> { it.looksLikePrinter }.thenBy { it.name.lowercase() })
    }

    /**
     * The printer to use. With BT_PRINTER set: the paired device with that address or name, or
     * (for an address typed by hand) that address even if it is not paired yet. Without it: the
     * paired device that looks like a printer, else the only paired device if it is not a phone,
     * headset, computer or watch.
     */
    @SuppressLint("MissingPermission")
    private fun findPrinter(): BluetoothDevice? = try {
        val paired = bonded()
        val wanted = settingsRepository.currentSettings.value?.btPrinter.orEmpty()
        if (wanted.isNotEmpty()) {
            paired.firstOrNull { it.address.equals(wanted, ignoreCase = true) || it.name.equals(wanted, ignoreCase = true) }
                ?: paired.firstOrNull { it.name?.contains(wanted, ignoreCase = true) == true }
                ?: deviceAt(wanted)
        } else {
            paired.firstOrNull { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.IMAGING }
                ?: paired.firstOrNull { it.name?.contains(PRINTER_NAME) == true }
                ?: paired.singleOrNull()?.takeIf { it.bluetoothClass?.majorDeviceClass !in NOT_PRINTERS }
        }
    } catch (e: SecurityException) {
        null
    }

    private fun deviceAt(address: String): BluetoothDevice? {
        val mac = address.trim().uppercase()
        if (!isValidAddress(mac) || !isEnabled()) return null
        return runCatching { adapter?.getRemoteDevice(mac) }.getOrNull()
    }

    override fun isPresent(): Boolean = hasPermission() && findPrinter() != null

    override fun unavailableReason(): String? = when {
        adapter == null -> null
        !hasPermission() -> "To print on the Bluetooth printer, allow Cloak Room to use \"Nearby devices\" " +
            "(Android Settings → Apps → Cloak Room → Permissions)."
        else -> null
    }

    override suspend fun send(data: ByteArray): PrintResult = sendTo(null, data)

    /**
     * Prints on the device at [address] (Printer setup → Test print), or on the printer chosen by
     * the settings when [address] is null.
     */
    @SuppressLint("MissingPermission")
    suspend fun sendTo(address: String?, data: ByteArray): PrintResult = withContext(Dispatchers.IO) {
        val adapter = adapter ?: return@withContext PrintResult.Failed("This device has no Bluetooth.")
        unavailableReason()?.let { return@withContext PrintResult.Failed(it) }
        if (!isEnabled()) return@withContext PrintResult.Failed("Bluetooth is off. Turn it on and try again.")
        val device = (if (address != null) deviceAt(address) else findPrinter()) ?: return@withContext PrintResult.Failed(
            if (address != null) "\"$address\" is not a Bluetooth address like 00:11:22:AA:BB:CC."
            else "No Bluetooth printer chosen. Open Printer setup, pick your printer and press Test print."
        )
        // Scanning slows a connection down; stopping it needs BLUETOOTH_SCAN on Android 12+, so it may be refused.
        runCatching { adapter.cancelDiscovery() }
        var socket: BluetoothSocket? = null
        try {
            socket = connect(device)
            val out = socket.outputStream
            var offset = 0
            while (offset < data.size) {
                val n = minOf(CHUNK_BYTES, data.size - offset)
                out.write(data, offset, n)
                out.flush()
                offset += n
                // Small pauses keep cheap printers' buffers from overflowing.
                Thread.sleep(CHUNK_PAUSE_MS)
            }
            // Closing at once can cut off the last part of the print.
            Thread.sleep(minOf(MAX_FINISH_WAIT_MS, FINISH_WAIT_MS + data.size / BYTES_PER_MS))
            PrintResult.Printed
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            PrintResult.Failed("Bluetooth permission is missing.")
        } catch (e: Exception) {
            Log.e(TAG, "Print failed", e)
            PrintResult.Failed("Could not reach the printer \"${device.name ?: device.address}\". Check that it is on and near, then try again.")
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** Secure SPP first, then insecure SPP, then channel 1: different printers accept different ones. */
    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice): BluetoothSocket {
        val attempts: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            { device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket },
        )
        var last: Exception? = null
        for (create in attempts) {
            val socket = try { create() } catch (e: Exception) { last = e; continue }
            try {
                socket.connect()
                return socket
            } catch (e: IOException) {
                last = e
                runCatching { socket.close() }
            }
        }
        throw last ?: IOException("Could not connect")
    }

    companion object {
        /** "00:11:22:AA:BB:CC", capital letters. */
        fun isValidAddress(address: String): Boolean = BluetoothAdapter.checkBluetoothAddress(address)

        private const val TAG = "BluetoothPrinterPort"

        /** Serial Port Profile, used by Bluetooth thermal printers. */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Names that common thermal printers advertise. */
        private val PRINTER_NAME = Regex(
            "print|pos|thermal|receipt|mtp|rpp|mpt|bt-?58|bt-?80|58mm|80mm|pt-?\\d|xp-|zj-|gp-|goojprt|rongta|hoin|tvs|bixolon|epson|sunmi",
            RegexOption.IGNORE_CASE,
        )
        private val NOT_PRINTERS = setOf(
            BluetoothClass.Device.Major.PHONE,
            BluetoothClass.Device.Major.AUDIO_VIDEO,
            BluetoothClass.Device.Major.COMPUTER,
            BluetoothClass.Device.Major.WEARABLE,
        )

        private const val CHUNK_BYTES = 512
        private const val CHUNK_PAUSE_MS = 15L
        private const val FINISH_WAIT_MS = 800L
        private const val MAX_FINISH_WAIT_MS = 8000L

        /** Rough print speed of a 58 mm printer, to wait for the end of the print. */
        private const val BYTES_PER_MS = 20
    }
}
