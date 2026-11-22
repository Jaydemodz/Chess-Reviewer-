package com.example.chessreviewer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.Locale

class MainActivity : Activity() {
    private val reqCapture = 100
    private val reqShizuku = 200
    private var engine: StockfishEngine? = null
    private lateinit var shizuku: ShizukuCapture
    private var shizukuOut: TextView? = null

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == reqShizuku) refreshShizuku()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        shizuku = ShizukuCapture(applicationContext)
        // one engine for this activity; the strength controls below configure it directly
        engine = StockfishEngine(this)
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (e: Throwable) {
            // Shizuku's service is not up; the buttons below report that.
        }

        val info = TextView(this)
        info.textSize = 16f
        info.text = "Automatic mode:\n\n" +
            "1. Tap 'Allow overlay' and switch it on.\n" +
            "2. Tap 'Start' and accept the screen-capture prompt.\n" +
            "3. Open your chess game so the whole board is visible. A small panel floats at the bottom.\n" +
            "4. With the pieces in the START position, tap 'Learn' (once per piece set).\n" +
            "5. Play. The board is found automatically and the best move updates by itself.\n\n" +
            "Flip = swap which side is at the bottom. Turn = swap whose turn it is. " +
            "Save = writes a picture of what the app sees to Pictures/ChessReviewer (for debugging).\n\n" +
            "Screens that block capture (FLAG_SECURE) come back black to a normal screen recording. " +
            "The app reads those anyway when it can: through root if the phone is rooted, or through " +
            "Shizuku if it is not. For Shizuku, install Shizuku, start it over wireless debugging " +
            "(Android 11 and newer), then tap 'Grant Shizuku access' here. Protected screens are " +
            "readable that way on Android 11 and newer; on older versions only root can read them.\n\n" +
            "Stockfish plays at full strength by default. The Elo button on the floating panel, " +
            "and the strength slider below, set it to a lower level instead; the choice sticks " +
            "and both the overlay and this screen use it."

        val overlayBtn = Button(this)
        overlayBtn.text = "Allow overlay"
        overlayBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        val startBtn = Button(this)
        startBtn.text = "Start"
        startBtn.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow 'Display over other apps' first", Toast.LENGTH_LONG).show()
            } else {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                @Suppress("DEPRECATION")
                startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
            }
        }

        val stopBtn = Button(this)
        stopBtn.text = "Stop"
        stopBtn.setOnClickListener { stopService(Intent(this, CaptureService::class.java)) }

        // ---------------------------------------------------------------- protected screens

        val shizukuLabel = TextView(this)
        shizukuLabel.textSize = 16f
        shizukuLabel.text = "\nProtected screens (FLAG_SECURE):"

        val shizukuOut = TextView(this)
        shizukuOut.textSize = 14f
        this.shizukuOut = shizukuOut

        val grantBtn = Button(this)
        grantBtn.text = "Grant Shizuku access"
        grantBtn.setOnClickListener { requestShizukuPermission() }

        val openBtn = Button(this)
        openBtn.text = "Open Shizuku"
        openBtn.setOnClickListener { openShizuku() }

        val checkBtn = Button(this)
        checkBtn.text = "Check screen access"
        checkBtn.setOnClickListener {
            shizukuOut.text = "Checking root, then Shizuku…"
            Thread {
                val secure = SecureCapture(this, cacheDir)
                val dm = resources.displayMetrics
                val shot = secure.grab(dm.widthPixels, dm.heightPixels)
                val message = if (shot == null) {
                    "Can't read protected screens: " + secure.lastError +
                        ".\n" + secure.describe() +
                        "\nThey will stay black; type the position in below instead."
                } else {
                    val size = shot.width.toString() + "x" + shot.height
                    shot.recycle()
                    "Works (" + secure.describe() + ") read the screen at " + size +
                        ". Protected screens are readable while the app is running."
                }
                runOnUiThread { shizukuOut.text = message }
            }.start()
        }

        // ------------------------------------------------------------- Stockfish strength

        val strengthLabel = TextView(this)
        strengthLabel.textSize = 16f
        strengthLabel.text = "\nStockfish strength:"

        val strengthOut = TextView(this)
        strengthOut.textSize = 14f

        val seek = SeekBar(this)
        seek.max = (EngineSettings.MAX_ELO - EngineSettings.MIN_ELO) / EngineSettings.STEP
        seek.progress =
            ((EngineSettings.elo(this) ?: EngineSettings.MAX_ELO) - EngineSettings.MIN_ELO) / EngineSettings.STEP

        val fullChk = CheckBox(this)
        fullChk.text = "Full strength"
        fullChk.isChecked = EngineSettings.elo(this) == null

        fun refreshStrengthOut() {
            val v = EngineSettings.targetElo(fullChk.isChecked, seek.progress)
            strengthOut.text = EngineSettings.label(v) +
                (if (v == null) ". The engine plays its hardest." else " (slide right for stronger play)")
        }
        refreshStrengthOut()

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fullChk.isChecked) refreshStrengthOut()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                if (!fullChk.isChecked) {
                    engine?.setElo(EngineSettings.targetElo(false, seek.progress))
                }
            }
        })

        fullChk.setOnCheckedChangeListener { _, full ->
            refreshStrengthOut()
            engine?.setElo(EngineSettings.targetElo(full, seek.progress))
        }

        val posLabel = TextView(this)
        posLabel.textSize = 16f
        posLabel.text = "\nAnalyze a position by hand:"

        val posInput = EditText(this)
        posInput.hint = "Paste a FEN, or UCI moves from the start, e.g.  e2e4 e7e5 g1f3"
        posInput.minLines = 2

        val result = TextView(this)
        result.textSize = 18f

        val analyzeBtn = Button(this)
        analyzeBtn.text = "Analyze position"
        analyzeBtn.setOnClickListener { analyze(posInput.text.toString(), result) }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(40, 80, 40, 80)
        col.addView(info)
        col.addView(overlayBtn)
        col.addView(startBtn)
        col.addView(stopBtn)
        col.addView(shizukuLabel)
        col.addView(shizukuOut)
        col.addView(grantBtn)
        col.addView(openBtn)
        col.addView(checkBtn)
        col.addView(strengthLabel)
        col.addView(strengthOut)
        col.addView(seek)
        col.addView(fullChk)
        col.addView(posLabel)
        col.addView(posInput)
        col.addView(analyzeBtn)
        col.addView(result)

        val scroll = ScrollView(this)
        scroll.addView(col)
        setContentView(scroll)

        refreshShizuku()
    }

    override fun onResume() {
        super.onResume()
        refreshShizuku()
    }

    // -------------------------------------------------------------------- Shizuku

    private fun refreshShizuku() {
        val out = shizukuOut ?: return
        out.text = try {
            shizuku.report()
        } catch (e: Throwable) {
            "Shizuku: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun requestShizukuPermission() {
        val out = shizukuOut ?: return
        try {
            if (!shizuku.isRunning()) {
                out.text = "Shizuku is not running yet. Open it, start it, then come back."
                return
            }
            if (shizuku.hasPermission()) {
                out.text = shizuku.report()
                return
            }
            out.text = "Asking Shizuku for access…"
            Shizuku.requestPermission(reqShizuku)
        } catch (e: Throwable) {
            out.text = "Could not ask Shizuku: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun openShizuku() {
        val launch = try {
            packageManager.getLaunchIntentForPackage(ShizukuCapture.PACKAGE_SHIZUKU)
        } catch (e: Throwable) {
            null
        }
        if (launch != null) {
            startActivity(launch)
            return
        }
        // Not installed: the Play Store page is the quickest install path.
        val market = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=" + ShizukuCapture.PACKAGE_SHIZUKU)
        )
        try {
            startActivity(market)
        } catch (e: Throwable) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + ShizukuCapture.PACKAGE_SHIZUKU)
                )
            )
        }
    }

    // -------------------------------------------------------------------- engine

    private fun analyze(input: String, out: TextView) {
        val text = input.trim()
        if (text.isEmpty()) {
            out.text = "Type or paste a position first."
            return
        }
        val cmd: String
        val blackToMove: Boolean
        if (text.contains('/')) {
            val f = text.split(Regex("\\s+")).toMutableList()
            if (f.size < 2) f.add("w")
            if (f.size < 3) f.add("-")
            if (f.size < 4) f.add("-")
            if (f.size < 5) f.add("0")
            if (f.size < 6) f.add("1")
            cmd = "position fen " + f.joinToString(" ")
            blackToMove = f[1] == "b"
        } else {
            val moves = text.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            val ok = Regex("[a-h][1-8][a-h][1-8][qrbn]?")
            if (moves.any { !ok.matches(it) }) {
                out.text = "Moves must look like e2e4 (start square + end square), not Nf3. Or paste a FEN."
                return
            }
            cmd = "position startpos moves " + moves.joinToString(" ")
            blackToMove = moves.size % 2 == 1
        }

        out.text = "Thinking…"
        Thread {
            val e = engine ?: StockfishEngine(this).also { engine = it }
            val available = e.available()
            val r = if (available) e.analyzeRaw(cmd, 1000) else null
            runOnUiThread {
                out.text =
                    if (!available) "Stockfish isn't inside this APK (libstockfish.so missing)."
                    else if (r == null) "The engine couldn't read that position."
                    else format(r, blackToMove)
            }
        }.start()
    }

    private fun format(r: EngineResult, black: Boolean): String {
        val b = r.best
        if (b.length < 4 || b == "(none)") return "No legal moves (game over)."
        val mate = r.mate
        val cp = r.cp
        val ev = if (mate != null) {
            val m = if (black) -mate else mate
            if (m > 0) "White mates in $m" else "Black mates in ${-m}"
        } else if (cp != null) {
            String.format(Locale.US, "%+.2f", (if (black) -cp else cp) / 100.0)
        } else "?"
        return (if (black) "Black" else "White") + " to move\nBest move: $b\nEval (White): $ev"
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (e: Throwable) {
        }
        engine?.stop()
        super.onDestroy()
    }
}
