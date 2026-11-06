package com.synergic.cloakroom.data.print

import android.util.Log
import android_serialport_api.SerialPort
import com.synergic.cloakroom.domain.print.PrintResult
import hdx.HdxUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The built-in thermal printer of the GC039 POS, on an internal serial port, driven the same way
 * as the vendor's SDK demo: switch the shared port to the printer, power the printer on, check
 * paper, send, wait until it has finished, power it off.
 *
 * On any other phone the SDK's native parts are missing; [isPresent] is then false.
 */
@Singleton
class SerialPrinterPort @Inject constructor() : PrinterPort {

    /** The printer's serial device (e.g. /dev/ttyS3), or null when this is not a GC039. */
    private fun portPath(): String? = try {
        HdxUtil.GetPrinterPort()?.trim()?.takeIf { it.isNotEmpty() && File(it).exists() }
    } catch (t: Throwable) {
        // UnsatisfiedLinkError / NoClassDefFoundError: libhdxutil.so is not on this device.
        null
    }

    override fun isPresent(): Boolean = portPath() != null

    override suspend fun send(data: ByteArray): PrintResult = withContext(Dispatchers.IO) {
        val path = portPath() ?: return@withContext PrintResult.Failed("Built-in printer not found on this device.")
        var port: SerialPort? = null
        try {
            HdxUtil.SwitchSerialFunction(HdxUtil.SERIAL_FUNCTION_PRINTER)
            HdxUtil.SetPrinterPower(1)
            Thread.sleep(POWER_ON_WAIT_MS)
            port = SerialPort(File(path), BAUD_RATE, 0)
            val input = port.inputStream
            val output = port.outputStream

            // No answer is not an error: some firmware does not answer ESC v.
            val status = readStatus(input, output)
            problem(status)?.let { return@withContext PrintResult.Failed(it) }

            writeWithFlowControl(input, output, data)
                ?.let { return@withContext PrintResult.Failed(it) }
            waitUntilPrinted(input, output)
            PrintResult.Printed
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            PrintResult.Failed("The app may not open the built-in printer ($path).")
        } catch (t: Throwable) {
            Log.e(TAG, "Print failed", t)
            PrintResult.Failed("Built-in printer error: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { port?.close() }
            runCatching { HdxUtil.SetPrinterPower(0) }
        }
    }

    private fun problem(status: Int?): String? = when {
        status == null -> null
        status and ST_NO_PAPER != 0 -> "The printer is out of paper. Load a roll and try again."
        status and ST_TOO_HOT != 0 -> "The printer is too hot. Wait a minute and try again."
        else -> null
    }

    /** ESC v: the printer answers one status byte. Null when it does not answer in time. */
    private fun readStatus(input: InputStream, output: OutputStream): Int? {
        drain(input)
        output.write(byteArrayOf(0x1B, 0x76))
        output.flush()
        val deadline = System.currentTimeMillis() + STATUS_TIMEOUT_MS
        var last: Int? = null
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val buffer = ByteArray(input.available())
                val n = input.read(buffer)
                if (n > 0) last = buffer[n - 1].toInt() and 0xFF
                // Give a split answer a moment to finish, then use its last byte (as the SDK does).
                Thread.sleep(POLL_MS)
                if (input.available() == 0) return last
            } else {
                Thread.sleep(POLL_MS)
            }
        }
        return last
    }

    /**
     * Writes in small chunks. The printer sends XOFF (0x13) when its buffer is full and XON (0x11)
     * when it can take more. Returns an error message, or null when everything was sent.
     */
    private fun writeWithFlowControl(input: InputStream, output: OutputStream, data: ByteArray): String? {
        var paused = false
        var offset = 0
        while (offset < data.size) {
            paused = readFlow(input, paused)
            if (paused) {
                val deadline = System.currentTimeMillis() + XOFF_TIMEOUT_MS
                while (paused && System.currentTimeMillis() < deadline) {
                    Thread.sleep(POLL_MS)
                    paused = readFlow(input, paused)
                }
                if (paused) return "The printer stopped responding. Check the paper and try again."
            }
            val n = minOf(CHUNK_BYTES, data.size - offset)
            output.write(data, offset, n)
            output.flush()
            offset += n
        }
        return null
    }

    private fun readFlow(input: InputStream, pausedBefore: Boolean): Boolean {
        var paused = pausedBefore
        while (input.available() > 0) {
            when (input.read()) {
                XOFF -> paused = true
                XON -> paused = false
            }
        }
        return paused
    }

    /**
     * Powering off too early cuts the print. The status answer comes after all the data has been
     * taken in; wait until the "working" bit clears (or the printer stops answering).
     */
    private fun waitUntilPrinted(input: InputStream, output: OutputStream) {
        val deadline = System.currentTimeMillis() + PRINT_TIMEOUT_MS
        var noAnswer = 0
        while (System.currentTimeMillis() < deadline) {
            val status = readStatus(input, output)
            if (status == null) {
                if (++noAnswer >= MAX_NO_ANSWER) return
            } else if (status and ST_WORKING == 0) {
                Thread.sleep(FINISH_WAIT_MS)
                return
            }
            Thread.sleep(BUSY_POLL_MS)
        }
    }

    private fun drain(input: InputStream) {
        while (input.available() > 0) input.read(ByteArray(input.available()))
    }

    private companion object {
        const val TAG = "SerialPrinterPort"

        /** Values from the GC039 SDK demo (Application.java and ConsoleActivity.java). */
        const val BAUD_RATE = 115200
        const val POWER_ON_WAIT_MS = 600L
        const val ST_NO_PAPER = 1 shl 0
        const val ST_TOO_HOT = 1 shl 4
        const val ST_WORKING = 1 shl 5
        const val XON = 0x11
        const val XOFF = 0x13

        const val CHUNK_BYTES = 256
        const val POLL_MS = 20L
        const val STATUS_TIMEOUT_MS = 1500L
        const val XOFF_TIMEOUT_MS = 10_000L
        const val PRINT_TIMEOUT_MS = 30_000L
        const val BUSY_POLL_MS = 250L
        const val MAX_NO_ANSWER = 3
        const val FINISH_WAIT_MS = 300L
    }
}
