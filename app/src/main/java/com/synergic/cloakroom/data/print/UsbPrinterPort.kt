package com.synergic.cloakroom.data.print

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.synergic.cloakroom.domain.print.PrintResult
import dagger.hilt.android.qualifiers.ApplicationContext
import hdx.HdxUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * A USB thermal printer (USB printer class), such as the built-in printer of the GC039ES. Android
 * asks the user once to allow the app to use it.
 */
@Singleton
class UsbPrinterPort @Inject constructor(
    @ApplicationContext private val context: Context,
) : PrinterPort {

    private val usbManager: UsbManager? = context.getSystemService(UsbManager::class.java)

    private class Target(val device: UsbDevice, val usbInterface: UsbInterface, val endpoint: UsbEndpoint)

    /** The first attached device with a printer interface and a bulk OUT endpoint. */
    private fun findPrinter(): Target? = try {
        usbManager?.deviceList?.values?.firstNotNullOfOrNull { device ->
            (0 until device.interfaceCount).map { device.getInterface(it) }
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER }
                .firstNotNullOfOrNull { usbInterface ->
                    (0 until usbInterface.endpointCount).map { usbInterface.getEndpoint(it) }
                        .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
                        ?.let { Target(device, usbInterface, it) }
                }
        }
    } catch (e: Exception) {
        null
    }

    override fun isPresent(): Boolean = findPrinter() != null

    override suspend fun send(data: ByteArray): PrintResult = withContext(Dispatchers.IO) {
        val manager = usbManager ?: return@withContext PrintResult.Failed("This device has no USB host support.")
        val target = findPrinter() ?: powerOnAndFind()
            ?: return@withContext PrintResult.Failed("USB printer not found. Check that it is connected and switched on.")
        if (!manager.hasPermission(target.device) && !requestPermission(manager, target.device)) {
            return@withContext PrintResult.Failed("Permission to use the USB printer was not given.")
        }
        val connection = manager.openDevice(target.device)
            ?: return@withContext PrintResult.Failed("Could not open the USB printer.")
        try {
            if (!connection.claimInterface(target.usbInterface, true)) {
                return@withContext PrintResult.Failed("The USB printer is busy.")
            }
            var offset = 0
            while (offset < data.size) {
                val n = minOf(CHUNK_BYTES, data.size - offset)
                val sent = connection.bulkTransfer(target.endpoint, data, offset, n, TRANSFER_TIMEOUT_MS)
                if (sent <= 0) return@withContext PrintResult.Failed("The USB printer stopped responding.")
                offset += sent
            }
            PrintResult.Printed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Print failed", e)
            PrintResult.Failed("USB printer error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { connection.releaseInterface(target.usbInterface) }
            connection.close()
        }
    }

    /**
     * On the GC039ES the printer appears on USB only when powered (through the same SDK as the
     * GC039). It is left on, because every power cycle would ask for the USB permission again.
     */
    private fun powerOnAndFind(): Target? {
        val powered = runCatching { HdxUtil.SetPrinterPower(1) }.isSuccess
        if (!powered) return null
        repeat(USB_APPEAR_TRIES) {
            Thread.sleep(USB_APPEAR_WAIT_MS)
            findPrinter()?.let { return it }
        }
        return null
    }

    /** Shows Android's "Allow the app to access the USB device?" dialog and waits for the answer. */
    private suspend fun requestPermission(manager: UsbManager, device: UsbDevice): Boolean =
        withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        runCatching { context.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
                ContextCompat.registerReceiver(
                    context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                // Mutable so the system can add the answer; explicit (own package) as Android 14 requires.
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
                manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
            }
        } ?: false

    private companion object {
        const val TAG = "UsbPrinterPort"
        const val ACTION_USB_PERMISSION = "com.synergic.cloakroom.USB_PRINTER_PERMISSION"
        const val CHUNK_BYTES = 4096
        const val TRANSFER_TIMEOUT_MS = 5000
        const val USB_APPEAR_TRIES = 10
        const val USB_APPEAR_WAIT_MS = 300L
        const val PERMISSION_TIMEOUT_MS = 60_000L
    }
}
