package com.example.chessreviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import java.lang.reflect.Method

/**
 * One display capture, one route per Android generation.
 *
 * This runs inside the Shizuku process (see [SecureShotService]), where hidden APIs are
 * reachable and the uid is shell (2000) or root. MediaProjection, `screencap` and
 * AccessibilityService all leave FLAG_SECURE layers out, so the frame is asked for from the
 * system with secure layers switched on. The class that can do that is not the same on every
 * release, which is what this file is for:
 *
 *  * Android 14 and newer capture through `android.window.ScreenCapture`, and its
 *    WindowManagerService entry point dropped the display id:
 *    `captureDisplay(CaptureArgs, ScreenCaptureListener)`.
 *  * Android 12 and 13 capture through the same `ScreenCapture` class, but the entry point
 *    still takes the display id first:
 *    `captureDisplay(int displayId, CaptureArgs, ScreenCaptureListener)`.
 *  * Android 11 and older have no `android.window.ScreenCapture` at all. The route there is
 *    `SurfaceControl.captureDisplay(DisplayCaptureArgs)`, which is how the framework itself
 *    took a screenshot. Android 13 keeps it as the fallback when the WindowManagerService
 *    call is refused or missing.
 *
 * Every call is by reflection, because all of it is hidden API. The shapes are probed on the
 * running phone rather than compiled against one release: a method that a build does not
 * have is skipped, a route that throws is recorded and the next one is tried, and `screencap`
 * is the last resort. Nothing here blocks on anything but the system call itself, so it is
 * called from the Shizuku service's binder thread.
 */
object DisplayCapture {

    enum class Route(val label: String) {
        NONE("no route"),
        SCREEN_CAPTURE("ScreenCapture.captureDisplay"),
        SURFACE_CONTROL("SurfaceControl.captureDisplay"),
        SCREENCAP("screencap")
    }

    /** Which route answered last. NONE until the first successful frame. */
    @Volatile
    var route = Route.NONE
        private set

    /** Why the last frame failed, or blank when it did not. */
    @Volatile
    var lastError = ""
        private set

    /** The display as a Bitmap, or null with [lastError] set when every route failed. */
    fun capture(width: Int, height: Int): Bitmap? {
        val notes = ArrayList<String>(3)
        val size = if (width > 0 && height > 0) intArrayOf(width, height) else displaySize()
        val w = size[0]
        val h = size[1]
        if (w <= 0 || h <= 0) {
            lastError = "could not work out the screen size"
            return null
        }

        val routes = ArrayList<Pair<Route, () -> Bitmap>>(3)
        if (Build.VERSION.SDK_INT >= 31) {          // android.window.ScreenCapture exists
            routes.add(Route.SCREEN_CAPTURE to { viaScreenCapture(w, h) })
        }
        if (Build.VERSION.SDK_INT >= 30) {          // SurfaceControl.captureDisplay, 11 and up
            routes.add(Route.SURFACE_CONTROL to { viaSurfaceControl(w, h) })
        }
        routes.add(Route.SCREENCAP to { viaScreencap() })

        // A route can answer with a frame that still has the secure layers left out, which is
        // black. That frame is kept as a last result, but a later route is asked first for one
        // with the real pixels in it.
        var dark: Bitmap? = null
        for ((which, run) in routes) {
            val shot = attempt(which, notes, run) ?: continue
            if (looksBlack(shot)) {
                if (dark == null) dark = shot else shot.recycle()
                continue
            }
            dark?.recycle()
            lastError = ""
            return shot
        }
        lastError = notes.joinToString("; ").take(220)
        return dark
    }

    private inline fun attempt(which: Route, notes: MutableList<String>, block: () -> Bitmap): Bitmap? =
        try {
            block().also { route = which }
        } catch (t: Throwable) {
            notes.add(which.label + ": " + describe(t))
            null
        }

    /** True when nearly every sampled pixel is black, which is a missed secure layer. */
    private fun looksBlack(b: Bitmap): Boolean = try {
        val w = b.width
        val h = b.height
        if (w < 32 || h < 32) {
            false
        } else {
            var dark = 0
            var n = 0
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val p = b.getPixel(x, y)
                    if (((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255) < 12) dark++
                    n++
                    x += w / 16
                }
                y += h / 32
            }
            n > 0 && dark * 100 / n >= 85
        }
    } catch (t: Throwable) {
        false
    }

    // ------------------------------------------------------------------ Android 12 and newer

    /**
     * `android.window.ScreenCapture` through WindowManagerService, with secure layers on.
     *
     * The entry point is looked up on the running build instead of being compiled against one
     * shape: Android 12 and 13 declare `captureDisplay(int, CaptureArgs, listener)` and
     * Android 14 and newer declare `captureDisplay(CaptureArgs, listener)`. Whatever the
     * parameter order, the arguments are filled in by type.
     */
    private fun viaScreenCapture(w: Int, h: Int): Bitmap {
        val screenCapture = Class.forName("android.window.ScreenCapture")
        val argsClass = Class.forName("android.window.ScreenCapture\$CaptureArgs")
        val listenerClass = Class.forName("android.window.ScreenCapture\$ScreenCaptureListener")

        val args = buildCaptureArgs(w, h)
        val listener = screenCapture.getMethod("createSyncCaptureListener").invoke(null)
            ?: error("no screen capture listener")
        val wms = Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService")
            .invoke(null)
            ?: error("window manager service is not reachable")

        val entry = wms.javaClass.methods.firstOrNull { m ->
            m.name == "captureDisplay" &&
                m.parameterTypes.size in 2..3 &&
                m.parameterTypes.all { p ->
                    p == Int::class.javaPrimitiveType ||
                        p.isAssignableFrom(argsClass) ||
                        p.isAssignableFrom(listenerClass)
                }
        } ?: error("this build has no captureDisplay entry point")

        val invocation = entry.parameterTypes.map { p ->
            when {
                p == Int::class.javaPrimitiveType -> 0
                p.isAssignableFrom(listenerClass) -> listener
                else -> args
            }
        }.toTypedArray()
        entry.invoke(wms, *invocation)

        val buffer = listener.javaClass.getMethod("getBuffer").invoke(listener)
            ?: error("the system returned no frame")
        return toBitmap(buffer)
    }

    /** CaptureArgs for the whole display, with the setters this build happens to declare. */
    private fun buildCaptureArgs(w: Int, h: Int): Any {
        val builderClass = Class.forName("android.window.ScreenCapture\$CaptureArgs\$Builder")
        val builder = builderClass.getDeclaredConstructor().newInstance()
        // Each setter arrived in a different release; the ones present are used, the rest skipped.
        call(builder, "setSourceCrop", Rect(0, 0, w, h))
        call(builder, "setSize", w, h)
        call(builder, "setDisplayId", 0)                 // Android 14 and newer
        call(builder, "setCaptureSecureLayers", true)
        return call(builder, "build") ?: error("could not build the capture arguments")
    }

    // ------------------------------------------------------------------ Android 11 and fallback

    /**
     * `SurfaceControl.captureDisplay`, the route Android 11 and 12 have and the one Android 13
     * still answers when the WindowManagerService call is missing or refused. The display token
     * comes from SurfaceFlinger, so no window manager call is involved.
     */
    private fun viaSurfaceControl(w: Int, h: Int): Bitmap {
        val surfaceControl = Class.forName("android.view.SurfaceControl")
        val token = surfaceControl.getMethod("getInternalDisplayToken").invoke(null)
            ?: error("no internal display token")
        val builderClass = Class.forName("android.view.SurfaceControl\$DisplayCaptureArgs\$Builder")
        // Android 12 and newer take the display token; Android 11 wants a SurfaceControl.
        val builder = run {
            val byToken = builderClass.constructors.firstOrNull { c ->
                c.parameterTypes.size == 1 && c.parameterTypes[0].isAssignableFrom(token.javaClass)
            }
            if (byToken != null) {
                byToken.newInstance(token)
            } else {
                val tokenCtor = surfaceControl.declaredConstructors.firstOrNull { c ->
                    c.parameterTypes.size == 1 && c.parameterTypes[0].isAssignableFrom(token.javaClass)
                } ?: error("no display capture builder")
                tokenCtor.isAccessible = true
                val display = tokenCtor.newInstance(token)
                val byDisplay = builderClass.constructors.firstOrNull { c ->
                    c.parameterTypes.size == 1 && c.parameterTypes[0].isAssignableFrom(display.javaClass)
                } ?: error("no display capture builder")
                byDisplay.newInstance(display)
            }
        }

        call(builder, "setSourceCrop", Rect(0, 0, w, h))
        call(builder, "setSize", w, h)
        call(builder, "setPixelFormat", 1)                // PixelFormat.RGBA_8888
        call(builder, "setSecureLayersAllowed", true)     // not on every build
        val args = call(builder, "build") ?: error("could not build the capture arguments")

        val captureDisplay = methodFor(surfaceControl, "captureDisplay", arrayOf(args))
            ?: error("this build has no SurfaceControl.captureDisplay")
        val buffer = captureDisplay.invoke(null, args) ?: error("the system returned no frame")
        return toBitmap(buffer)
    }

    /** Last resort: the stock binary, which is not secure-aware but always exists. */
    private fun viaScreencap(): Bitmap {
        val p = Runtime.getRuntime().exec(arrayOf("screencap", "-p"))
        val bytes = try {
            p.inputStream.readBytes()
        } finally {
            try { p.destroy() } catch (t: Throwable) { }
        }
        if (bytes.size < 8) error("screencap returned nothing")
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("could not decode the screencap")
    }

    // ------------------------------------------------------------------ shared

    private fun toBitmap(buffer: Any): Bitmap {
        val bitmap = try {
            buffer.javaClass.getMethod("asBitmap").invoke(buffer) as? Bitmap
        } catch (t: Throwable) {
            null
        } ?: error("the frame was not a bitmap")
        // Screenshot bitmaps are hardware backed; the encoder and the caller want a normal one.
        return try {
            bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: bitmap
        } catch (t: Throwable) {
            bitmap
        }
    }

    private fun displaySize(): IntArray {
        val text = try {
            val p = Runtime.getRuntime().exec(arrayOf("wm", "size"))
            val out = p.inputStream.bufferedReader().readText()
            try { p.waitFor() } catch (t: Throwable) { }
            out
        } catch (t: Throwable) {
            ""
        }
        // "Physical size: 1080x2400" and, when the resolution was changed, "Override size: …"
        val match = Regex("size:\\s*(\\d+)x(\\d+)").findAll(text).lastOrNull()
            ?: return intArrayOf(0, 0)
        return intArrayOf(
            match.groupValues[1].toIntOrNull() ?: 0,
            match.groupValues[2].toIntOrNull() ?: 0
        )
    }

    /** Invokes [name] when this build declares a method that takes exactly these arguments. */
    private fun call(target: Any, name: String, vararg args: Any?): Any? =
        try {
            methodFor(target.javaClass, name, args)?.invoke(target, *args)
        } catch (t: Throwable) {
            null
        }

    private fun methodFor(cls: Class<*>, name: String, args: Array<out Any?>): Method? {
        for (m in cls.methods) {
            if (m.name != name || m.parameterTypes.size != args.size) continue
            var fits = true
            for (i in args.indices) {
                val p = m.parameterTypes[i]
                val a = args[i]
                fits = when {
                    a == null -> !p.isPrimitive
                    p.isPrimitive -> boxed(p) == a.javaClass
                    else -> p.isAssignableFrom(a.javaClass)
                }
                if (!fits) break
            }
            if (fits) return m
        }
        return null
    }

    private fun boxed(p: Class<*>): Class<*>? = when (p) {
        Int::class.javaPrimitiveType -> Integer::class.java
        Boolean::class.javaPrimitiveType -> java.lang.Boolean::class.java
        Float::class.javaPrimitiveType -> java.lang.Float::class.java
        else -> null
    }

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
