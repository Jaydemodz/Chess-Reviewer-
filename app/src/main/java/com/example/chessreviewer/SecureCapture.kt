package com.example.chessreviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader

/**
 * Reads screens that MediaProjection hands back as solid black.
 *
 * A black frame is what a window with FLAG_SECURE looks like to every ordinary capture path:
 * MediaProjection, AccessibilityService.takeScreenshot() and plain `screencap` all leave
 * secure layers out. Two routes get past that, and this class tries both:
 *
 *  * root, by running the stock `screencap` as uid 0;
 *  * Shizuku, by asking WindowManagerService for a display capture with secure layers in it
 *    from Shizuku's privileged process (see [SecureShotService]). On a non-rooted phone that
 *    is the whole point: no root, no Xposed, and it works from Android 11 up.
 *
 * Everything here blocks, so call [grab] from a background thread.
 */
class SecureCapture(context: Context, cacheDir: File) {

    enum class Mode { UNKNOWN, ROOT, SHIZUKU, NONE }

    /** Which route last worked. UNKNOWN until the first successful read. */
    @Volatile var mode = Mode.UNKNOWN
        private set

    @Volatile var lastError = ""
        private set

    private val root = RootCapture(cacheDir)
    private val shizuku = ShizukuCapture(context.applicationContext)

    /** Do not hammer a phone that has neither root nor Shizuku for every frame. */
    @Volatile private var retryAfter = 0L

    /** The screen as a Bitmap, or null when this phone cannot do the bypass. */
    fun grab(width: Int = 0, height: Int = 0): Bitmap? {
        val now = SystemClock.uptimeMillis()
        if (now < retryAfter) return null

        if (mode != Mode.SHIZUKU) {
            val fromRoot = root.grab()
            if (fromRoot != null) {
                mode = Mode.ROOT
                lastError = ""
                retryAfter = 0L
                return fromRoot
            }
        }

        val fromShizuku = shizuku.grab(width, height, if (mode == Mode.UNKNOWN) 8_000L else 4_000L)
        if (fromShizuku != null) {
            mode = Mode.SHIZUKU
            lastError = ""
            retryAfter = 0L
            return fromShizuku
        }

        mode = Mode.NONE
        lastError = shizuku.lastError.ifBlank { root.lastError }
        // Shizuku may be started, or the user may grant access, while the overlay keeps running.
        retryAfter = now + RETRY_MS
        return null
    }

    /** One line naming what the app can and cannot do, for the panel and the activity. */
    fun describe(): String = when {
        mode == Mode.ROOT -> "Reading protected screens through root."
        mode == Mode.SHIZUKU -> "Reading protected screens through Shizuku."
        shizuku.ready() -> "Shizuku is ready; the first protected screen will be read through it."
        shizuku.isRunning() -> "Shizuku is running but access is not granted."
        else -> "No root and no Shizuku: protected screens stay black."
    }

    companion object {
        private const val RETRY_MS = 5_000L
    }
}

/**
 * The root route: the stock `screencap` binary run as uid 0. SurfaceFlinger lets root read
 * secure layers, so this returns real pixels where everything else returns black.
 */
class RootCapture(private val cacheDir: File) {

    enum class Mode { UNKNOWN, ROOT, NONE }

    /** UNKNOWN until the first attempt, ROOT once a uid 0 shell answered, NONE when there is none. */
    @Volatile var mode = Mode.UNKNOWN
        private set

    @Volatile var lastError = ""
        private set

    private var attempts = 0
    @Volatile private var current: Process? = null

    /** The screen as a Bitmap, or null when there is no working root shell. */
    fun grab(): Bitmap? {
        if (mode == Mode.NONE) return null
        val first = attempts == 0
        attempts++
        // Magisk and friends show their grant dialog on the first call and can hold the shell.
        val timeoutMs = if (first) 15_000L else 4_000L

        val holder = arrayOfNulls<ByteArray>(1)
        val worker = Thread { holder[0] = shootOnce(timeoutMs) }
        worker.isDaemon = true
        worker.start()
        try {
            worker.join(timeoutMs + 2_000L)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (worker.isAlive) {
            lastError = "screencap did not finish in time"
            try { current?.destroy() } catch (e: Exception) { }
            return null
        }
        val bytes = holder[0] ?: return null
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
        if (bitmap == null) lastError = "could not decode the screenshot"
        return bitmap
    }

    private fun shootOnce(timeoutMs: Long): ByteArray? {
        val su = suBinary()
        if (su == null) {
            mode = Mode.NONE
            lastError = "no su binary"
            return null
        }
        if (mode != Mode.ROOT) {
            val id = readAll(su, "id", timeoutMs)
            val text = id?.toString(Charsets.UTF_8) ?: ""
            if (!text.contains("uid=0")) {
                mode = Mode.NONE
                lastError =
                    if (text.isBlank()) "su did not answer (missing or denied)"
                    else "su is not root: " + text.trim().take(80)
                return null
            }
            mode = Mode.ROOT
        }
        // PNG on stdout first: no file permissions to fight with.
        val png = readAll(su, "screencap -p", timeoutMs)
        if (png != null && isPng(png)) return png
        return shootToFile(su, timeoutMs)
    }

    /** Some builds lose the PNG on stdout; ask screencap to write into our own cache dir instead. */
    private fun shootToFile(su: String, timeoutMs: Long): ByteArray? {
        val out = File(cacheDir, "secure_shot.png")
        try { if (out.exists()) out.delete() } catch (e: Exception) { }
        val path = out.absolutePath
        readAll(su, "screencap -p $path; chmod 0644 $path 2>/dev/null", timeoutMs)
        val bytes = try {
            if (out.exists() && out.length() > 8L) out.readBytes() else null
        } catch (e: Exception) {
            null
        }
        try { out.delete() } catch (e: Exception) { }
        return if (bytes != null && isPng(bytes)) bytes else null
    }

    private fun readAll(su: String, command: String, timeoutMs: Long): ByteArray? {
        val err = StringBuilder()
        var started: Process? = null
        try {
            val p = ProcessBuilder(su, "-c", command).start()
            started = p
            current = p

            val drain = Thread {
                try {
                    val r = InputStreamReader(p.errorStream)
                    val ca = CharArray(512)
                    var n = r.read(ca)
                    while (n > 0) {
                        if (err.length < 1024) err.append(ca, 0, n)
                        n = r.read(ca)
                    }
                } catch (e: Exception) {
                }
            }
            drain.isDaemon = true
            drain.start()

            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = p.inputStream.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) { lastError = "screenshot too large"; break }
                buf.write(chunk, 0, n)
            }
            p.waitFor()
            if (err.isNotBlank()) lastError = err.toString().trim().take(160)
            return buf.toByteArray()
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            return null
        } finally {
            current = null
            try { started?.destroy() } catch (e: Exception) { }
        }
    }

    private fun suBinary(): String? {
        val candidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su"
        )
        for (c in candidates) {
            val f = File(c)
            if (f.exists() && f.canExecute()) return c
        }
        return "su" // let the shell resolve it through PATH
    }

    private fun isPng(b: ByteArray): Boolean =
        b.size > 8 &&
            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()

    private companion object {
        const val MAX_BYTES = 48 * 1024 * 1024
    }
}
