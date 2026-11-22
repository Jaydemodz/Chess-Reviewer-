package com.example.chessreviewer

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * The code that actually reads a FLAG_SECURE screen.
 *
 * This class does not run in the app's process. Shizuku starts it in a process of its own,
 * with the uid of whoever started Shizuku: 2000 (shell) when Shizuku was brought up over ADB
 * or wireless debugging, 0 (root) when it was started as root. That process holds
 * android.permission.READ_FRAME_BUFFER, and one more thing is needed:
 *
 *   ScreenCapture.CaptureArgs.Builder#setCaptureSecureLayers(true)
 *
 * Plain `screencap` (and MediaProjection, and AccessibilityService.takeScreenshot) leave
 * secure layers out, which is why they all return black. Asking the system for a display
 * capture with secure layers switched on returns the real pixels, and the shell uid is
 * allowed to ask. Which class answers that call depends on the release, so it lives in
 * [DisplayCapture]: `ScreenCapture` on Android 12 and newer (with the display id dropped from
 * the entry point on Android 14), `SurfaceControl` on Android 11 and as the Android 13
 * fallback, `screencap` last.
 *
 * The class is loaded by name from Shizuku, so keep the package and the no-argument
 * constructor as they are. It is a user service process, not an Android app process: hidden
 * APIs are reachable here, and Runtime.exec runs as shell.
 */
class SecureShotService : ISecureShot.Stub() {

    @Volatile private var pending: ByteArray? = null
    @Volatile private var error = ""

    override fun destroy() {
        // The API calls this on the transaction code 16777114; leaving is our job.
        try { System.exit(0) } catch (t: Throwable) { }
    }

    override fun capture(width: Int, height: Int): Int {
        pending = null
        error = ""
        val bitmap: Bitmap? = try {
            DisplayCapture.capture(width, height)
        } catch (t: Throwable) {
            error = describe(t)
            null
        }
        if (bitmap == null) {
            if (error.isBlank()) {
                error = DisplayCapture.lastError.ifBlank { "the system returned no frame" }
            }
            return -1
        }
        val bytes: ByteArray? = try {
            val out = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) out.toByteArray() else null
        } catch (t: Throwable) {
            error = "could not encode the frame: " + describe(t)
            null
        } finally {
            try { bitmap.recycle() } catch (t: Throwable) { }
        }
        if (bytes == null || bytes.isEmpty()) return -2
        pending = bytes
        return bytes.size
    }

    override fun chunk(offset: Int, length: Int): ByteArray {
        val all = pending ?: return ByteArray(0)
        if (offset < 0 || offset >= all.size || length <= 0) return ByteArray(0)
        val end = minOf(all.size, offset + length)
        return all.copyOfRange(offset, end)
    }

    override fun lastError(): String = error

    private fun describe(t: Throwable): String {
        var e: Throwable? = t
        var guard = 0
        while (e?.cause != null && e.cause !== e && guard < 6) {
            e = e.cause
            guard++
        }
        val real = e ?: t
        val name = real.javaClass.simpleName
        val message = real.message
        return if (message.isNullOrBlank()) name else name + ": " + message
    }
}
