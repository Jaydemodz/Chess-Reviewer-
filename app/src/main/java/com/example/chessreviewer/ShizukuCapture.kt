package com.example.chessreviewer

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app's side of the Shizuku path: binds [SecureShotService] in Shizuku's privileged
 * process and asks it for frames. Everything here blocks, so call [grab] off the main thread.
 *
 * Nothing is requested from the user here. The permission is asked for from MainActivity,
 * where there is a window for Shizuku's dialog to sit on.
 */
class ShizukuCapture(private val context: Context) {

    @Volatile private var service: ISecureShot? = null
    @Volatile private var connectLatch: CountDownLatch? = null

    @Volatile var lastError = ""
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder == null) null else ISecureShot.Stub.asInterface(binder)
            connectLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    // ------------------------------------------------------------------ state

    /** Shizuku's own service is up and answering. */
    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    /** This app has been given access to Shizuku. */
    fun hasPermission(): Boolean = try {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** 0 when Shizuku runs as root, 2000 when it was started over ADB, -1 when unknown. */
    fun uid(): Int = try {
        Shizuku.getUid()
    } catch (t: Throwable) {
        -1
    }

    fun isInstalled(): Boolean = try {
        context.packageManager.getApplicationInfo(PACKAGE_SHIZUKU, 0) != null
    } catch (t: Throwable) {
        false
    }

    fun ready(): Boolean = isRunning() && hasPermission()

    /** One line for the UI, in plain words. */
    fun report(): String = when {
        !isInstalled() -> "Shizuku is not installed on this phone."
        !isRunning() -> "Shizuku is installed but not running. Open Shizuku and start it (wireless debugging), then come back."
        !hasPermission() -> "Shizuku is running. Tap 'Grant Shizuku access' so this app can use it."
        uid() == 0 -> "Shizuku is ready and running as root (uid 0)."
        uid() == 2000 -> "Shizuku is ready as shell (uid 2000). Protected screens are readable on Android 11 and newer."
        else -> "Shizuku is ready (uid " + uid() + ")."
    }

    // ------------------------------------------------------------------ capture

    /** The display as the privileged process sees it, or null with [lastError] set. */
    fun grab(width: Int, height: Int, timeoutMs: Long): Bitmap? {
        val api = serviceOrBind(timeoutMs) ?: return null
        return try {
            val total = api.capture(width, height)
            if (total <= 0) {
                lastError = api.lastError().takeIf { it.isNotBlank() }
                    ?: ("the Shizuku capture failed (code " + total + ")")
                return null
            }
            val bytes = readAll(api, total)
            if (bytes == null) {
                if (lastError.isBlank()) lastError = "the Shizuku screenshot came back short"
                return null
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) {
                lastError = "could not decode the Shizuku screenshot"
                null
            } else {
                lastError = ""
                bitmap
            }
        } catch (t: Throwable) {
            // A dead binder means the process went away: drop it and try again next time.
            service = null
            lastError = describe(t)
            null
        }
    }

    private fun readAll(api: ISecureShot, total: Int): ByteArray? {
        val out = ByteArrayOutputStream(total)
        var offset = 0
        while (offset < total) {
            val want = minOf(CHUNK_BYTES, total - offset)
            val part = api.chunk(offset, want)
            if (part == null || part.isEmpty()) {
                lastError = "the Shizuku service stopped at byte " + offset + " of " + total
                return null
            }
            out.write(part, 0, part.size)
            offset += part.size
        }
        return out.toByteArray()
    }

    private fun serviceOrBind(timeoutMs: Long): ISecureShot? {
        service?.let { return it }
        if (!isRunning()) {
            lastError = "Shizuku is not running"
            return null
        }
        if (!hasPermission()) {
            lastError = "Shizuku access is not granted"
            return null
        }
        val latch = CountDownLatch(1)
        connectLatch = latch
        try {
            Shizuku.bindUserService(userServiceArgs(), connection)
        } catch (t: Throwable) {
            connectLatch = null
            lastError = "could not bind the Shizuku service: " + describe(t)
            return null
        }
        try {
            // First bind spawns the process, which can take a couple of seconds.
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            connectLatch = null
        }
        val api = service
        if (api == null && lastError.isBlank()) {
            lastError = "Shizuku did not connect in " + timeoutMs + " ms"
        }
        return api
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, SecureShotService::class.java.name)
        )
            .daemon(true)
            .processNameSuffix("shot")
            .debuggable(false)
            .version(1)

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

    companion object {
        const val PACKAGE_SHIZUKU = "moe.shizuku.privileged.api"
        private const val CHUNK_BYTES = 192 * 1024
    }
}
