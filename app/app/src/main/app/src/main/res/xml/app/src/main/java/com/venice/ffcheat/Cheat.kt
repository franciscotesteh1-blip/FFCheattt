package com.venice.ffcheat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.hypot

object Config {
    @Volatile var espEnabled = true
    @Volatile var showBox = true
    @Volatile var showLine = true
    @Volatile var showLabel = true
    @Volatile var showFovCircle = true
    @Volatile var boxColor = Color.rgb(0, 255, 120)
    @Volatile var lineColor = Color.rgb(0, 200, 255)
    @Volatile var boxThickness = 3f
    @Volatile var lineOriginY = 1.0f

    @Volatile var targetColor = Color.rgb(225, 45, 45)
    @Volatile var colorTolerance = 70
    @Volatile var minBlobPixels = 10
    @Volatile var maxBlobPixels = 20000
    @Volatile var useShapeFilter = true
    @Volatile var minAspect = 1.15f
    @Volatile var pixelStep = 1
    @Volatile var scanTop = 0.05f
    @Volatile var scanBottom = 0.78f
    @Volatile var hideMinimap = true
    @Volatile var pickRequested = false

    @Volatile var aimEnabled = false
    @Volatile var aimMode = 0
    @Volatile var aimFov = 260f
    @Volatile var aimTrigger = 70f
    @Volatile var aimSmooth = 0.30f
    @Volatile var aimSensitivity = 1.00f
    @Volatile var aimDeadzone = 6f
    @Volatile var aimVerticalBias = 0.18f
    @Volatile var aimStepMs = 16L
    @Volatile var aimResetRadius = 180f
    @Volatile var aimAnchorX = 0.62f
    @Volatile var aimAnchorY = 0.30f

    @Volatile var crossX = 0.50f
    @Volatile var crossY = 0.50f
}

object ProjectionHolder {
    var resultCode = 0
    var data: Intent? = null
}

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    private val projLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK && r.data != null) {
                ProjectionHolder.resultCode = r.resultCode
                ProjectionHolder.data = r.data
                val i = Intent(this, CheatService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                toast("Cheat ativo. Abra o Free Fire.")
            } else toast("Captura negada")
        }

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
            setBackgroundColor(Color.parseColor("#0B0B10"))
        }

        root.addView(TextView(this).apply {
            text = "VENICE FF CHEAT"
            setTextColor(Color.parseColor("#3DDC84"))
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 40)
        })

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 13f
            setPadding(0, 0, 0, 60)
        }
        root.addView(status)

        root.addView(mkBtn("1. Permissao de sobreposicao") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(mkBtn("2. Ativar servico de toque") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(mkBtn("3. Iniciar cheat") {
            if (!Settings.canDrawOverlays(this)) {
                toast("Conceda Sobrepor a outros apps")
                return@mkBtn
            }
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projLauncher.launch(mpm.createScreenCaptureIntent())
        })
        root.addView(mkBtn("Parar") {
            stopService(Intent(this, CheatService::class.java))
        })

        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch("android.permission.POST_NOTIFICATIONS")
    }

    override fun onResume() {
        super.onResume()
        val ov = Settings.canDrawOverlays(this)
        val ac = TouchService.instance != null
        status.text = "Sobreposicao: " + (if (ov) "OK" else "PENDENTE") +
                "  -  Acessibilidade: " + (if (ac) "OK" else "PENDENTE")
    }

    private fun mkBtn(t: String, a: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { a() }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}

class TouchService : AccessibilityService() {
    companion object { @Volatile var instance: TouchService? = null }

    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

class ScreenCapture(
    private val ctx: Context,
    private val resultCode: Int,
    private val data: Intent,
    private val onFrame: (Bitmap) -> Unit
) {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var padded: Bitmap? = null
    private var cropped: Bitmap? = null
    private val blit = Canvas()

    fun start(capW: Int, capH: Int, density: Int) {
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
        projection = proj

        val ht = HandlerThread("ff-cap").also { it.start() }
        thread = ht
        val h = Handler(ht.looper)
        handler = h

        proj.registerCallback(object : MediaProjection.Callback() {}, h)
        recreate(capW, capH, density)
    }

    fun recreate(capW: Int, capH: Int, density: Int) {
        try { vd?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        vd = null
        reader = null
        padded = null
        cropped = null

        val h = handler ?: return
        val proj = projection ?: return

        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        reader = r

        vd = proj.createVirtualDisplay(
            "ffcap", capW, capH, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, h
        )

        r.setOnImageAvailableListener({ ir ->
            val img = try { ir.acquireLatestImage() } catch (_: Exception) { null }
            if (img != null) {
                try {
                    val plane = img.planes[0]
                    val buf = plane.buffer
                    buf.rewind()

                    val pixelStride = plane.pixelStride
                    val rowPadding = plane.rowStride - pixelStride * capW
                    val bmpW = capW + rowPadding / pixelStride

                    var src = padded
                    if (src == null || src.width != bmpW || src.height != capH) {
                        src = Bitmap.createBitmap(bmpW, capH, Bitmap.Config.ARGB_8888)
                        padded = src
                    }
                    src.copyPixelsFromBuffer(buf)

                    if (bmpW == capW) {
                        onFrame(src)
                    } else {
                        var c = cropped
                        if (c == null || c.width != capW || c.height != capH) {
                            c = Bitmap.createBitmap(capW, capH, Bitmap.Config.ARGB_8888)
                            cropped = c
                            blit.setBitmap(c)
                        }
                        blit.drawBitmap(src, 0f, 0f, null)
                        onFrame(c)
                    }
                } catch (_: Throwable) {
                } finally {
                    img.close()
                }
            }
        }, h)
    }

    fun stop() {
        try { vd?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        thread?.quitSafely()
        vd = null; reader = null; projection = null; handler = null; thread = null
        padded = null; cropped = null
    }
}

data class Target(
    val cx: Float, val cy: Float,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val pixels: Int
)

class Detector {

    private val CELL = 6
    private var buf = IntArray(0)
    private var hits = IntArray(0)
    private var visited = BooleanArray(0)

    fun detect(bmp: Bitmap): List<Target> {
        val w = bmp.width
        val h = bmp.height
        val n = w * h

        if (buf.size < n) buf = IntArray(n)
        bmp.getPixels(buf, 0, w, 0, 0, w, h)

        val tr = Color.red(Config.targetColor)
        val tg = Color.green(Config.targetColor)
        val tb = Color.blue(Config.targetColor)
        val tol = Config.colorTolerance
        val tolSq = tol * tol * 3
        val step = Config.pixelStep.coerceAtLeast(1)

        val yTop = (h * Config.scanTop).toInt().coerceAtLeast(0)
        val yBot = (h * Config.scanBottom).toInt().coerceAtMost(h)
        val mmX = (w * 0.26f).toInt()
        val mmY = (h * 0.30f).toInt()

        val gw = (w + CELL - 1) / CELL
        val gh = (h + CELL - 1) / CELL
        val gn = gw * gh
        if (hits.size < gn) {
            hits = IntArray(gn)
            visited = BooleanArray(gn)
        } else {
            java.util.Arrays.fill(hits, 0, gn, 0)
            java.util.Arrays.fill(visited, 0, gn, false)
        }

        for (y in yTop until yBot step step) {
            val row = y * w
            val gy = y / CELL
            val skipMm = Config.hideMinimap && y < mmY
            for (x in 0 until w step step) {
                if (skipMm && x < mmX) continue
                val c = buf[row + x]
                val dr = ((c shr 16) and 0xFF) - tr
                val dg = ((c shr 8) and 0xFF) - tg
                val db = (c and 0xFF) - tb
                if (dr * dr + dg * dg + db * db <= tolSq) hits[gy * gw + x / CELL]++
            }
        }

        val out = ArrayList<Target>()
        val minPx = Config.minBlobPixels
        val maxPx = Config.maxBlobPixels

        for (i in 0 until gn) {
            if (visited[i] || hits[i] == 0) continue
            var count = 0
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = -1
            var maxY = -1
            val stack = ArrayDeque<Int>()
            stack.addLast(i)
            visited[i] = true

            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                val cx = cur % gw
                val cy = cur / gw
                count += hits[cur]
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy

                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                    val ni = ny * gw + nx
                    if (!visited[ni] && hits[ni] > 0) {
                        visited[ni] = true
                        stack.addLast(ni)
                    }
                }
            }

            if (count < minPx || count > maxPx) continue

            val bw = ((maxX - minX + 1) * CELL).toFloat()
            val bh = ((maxY - minY + 1) * CELL).toFloat()
            if (Config.useShapeFilter && (bw / bh) < Config.minAspect) continue

            out.add(
                Target(
                    cx = (minX + maxX + 1) * CELL / 2f,
                    cy = (minY + maxY + 1) * CELL / 2f,
                    left = (minX * CELL).toFloat(),
                    top = (minY * CELL).toFloat(),
                    right = ((maxX + 1) * CELL).toFloat(),
                    bottom = ((maxY + 1) * CELL).toFloat(),
                    pixels = count
                )
            )
        }
        return out
    }
}

class AimController(private val svc: AccessibilityService) {

    private var stroke: GestureDescription.StrokeDescription? = null
    private var curX = 0f
    private var curY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var active = false

    fun configure(screenW: Int, screenH: Int) {
        anchorX = screenW * Config.aimAnchorX
        anchorY = screenH * Config.aimAnchorY
    }

    fun start() {
        if (active) return
        active = true
        curX = anchorX
        curY = anchorY
        val p = Path().apply { moveTo(0f, 0f); lineTo(0.5f, 0.5f) }
        val s = GestureDescription.StrokeDescription(p, 0, 20, true)
        stroke = s
        dispatch(s)
    }

    fun stop() {
        if (!active) return
        active = false
        val s = stroke
        stroke = null
        if (s != null) {
            val p = Path().apply { moveTo(0f, 0f); lineTo(0f, 0f) }
            dispatch(s.continueStroke(p, 0, 12, false))
        }
    }

    fun move(dx: Float, dy: Float) {
        if (!active) start()
        val s = stroke ?: return
        curX += dx
        curY += dy
        val p = Path().apply { moveTo(0f, 0f); lineTo(dx, dy) }
        val ns = s.continueStroke(p, 0, Config.aimStepMs, true)
        stroke = ns
        dispatch(ns)
    }

    fun shouldReset(): Boolean = hypot(curX - anchorX, curY - anchorY) > Config.aimResetRadius

    fun reset() {
        val s = stroke
        active = false
        stroke = null
        if (s != null) {
            val p = Path().apply { moveTo(0f, 0f); lineTo(0f, 0f) }
            dispatch(s.continueStroke(p, 0, 12, false))
        }
        start()
    }

    private fun dispatch(s: GestureDescription.StrokeDescription) {
        svc.dispatchGesture(GestureDescription.Builder().addStroke(s).build(), null, null)
    }
}

class EspView(context: Context) : View(context) {

    @Volatile private var targets: List<Target> = emptyList()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fovPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
    }

    init { setWillNotDraw(false) }

    fun setTargets(t: List<Target>) {
        targets = t
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f) return

        if (Config.espEnabled) {
            boxPaint.color = Config.boxColor
            boxPaint.strokeWidth = Config.boxThickness
            linePaint.color = Config.lineColor
            linePaint.strokeWidth = Config.boxThickness
            textPaint.color = Config.boxColor

            for (t in targets) {
                if (Config.showBox) canvas.drawRect(t.left, t.top, t.right, t.bottom, boxPaint)
                if (Config.showLine) canvas.drawLine(w / 2f, h * Config.lineOriginY, t.cx, t.bottom, linePaint)
                if (Config.showLabel) {
                    val hBox = (t.bottom - t.top).coerceAtLeast(1f)
                    val dist = (14000f / hBox).toInt()
                    canvas.drawText("${dist}m", t.left, (t.top - 10f).coerceAtLeast(30f), textPaint)
                }
            }
        }

        if (Config.showFovCircle) {
            val cx = w * Config.crossX
            val cy = h * Config.crossY
            fovPaint.color = 0x99FFFFFF.toInt()
            fovPaint.strokeWidth = 3f
            canvas.drawCircle(cx, cy, Config.aimFov, fovPaint)

            crossPaint.color = 0xFFFF00FF.toInt()
            crossPaint.strokeWidth = 3f
            canvas.drawCircle(cx, cy, 12f, crossPaint)
        }
    }
}

class PanelView(context: Context) : LinearLayout(context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val body = LinearLayout(context)
    private val scroll = ScrollView(context)
    private var dx = 0f
    private var dy = 0f
    private var moved = false

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(0xE60D0D12.toInt())
            cornerRadius = 28f
            setStroke(2, 0xFF3DDC84.toInt())
        }
        setPadding(26, 10, 26, 18)

        val header = TextView(context).apply {
            text = "VENICE FF CHEAT"
            setTextColor(0xFF3DDC84.toInt())
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(16))
        }
        addView(header)

        scroll.addView(body)
        scroll.layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            (resources.displayMetrics.heightPixels * 0.70f).toInt()
        )
        addView(scroll)
        body.orientation = VERTICAL

        header.setOnTouchListener { _, e ->
            val lp = layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX - lp.x
                    dy = e.rawY - lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nx = (e.rawX - dx).toInt()
                    val ny = (e.rawY - dy).toInt()
                    if (abs(nx - lp.x) > 4 || abs(ny - lp.y) > 4) moved = true
                    lp.x = nx
                    lp.y = ny
                    wm.updateViewLayout(this@PanelView, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        scroll.visibility =
                            if (scroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                    true
                }
                else -> true
            }
        }

        build()
    }

    private fun build() {
        section("ESP")
        sw("ESP ativo") { Config.espEnabled } { Config.espEnabled = it }
        sw("Caixa") { Config.showBox } { Config.showBox = it }
        sw("Linha") { Config.showLine } { Config.showLine = it }
        sw("Distancia") { Config.showLabel } { Config.showLabel = it }
        sw("Circulo FOV") { Config.showFovCircle } { Config.showFovCircle = it }
        sl("Espessura", 1, 10, { Config.boxThickness }) { Config.boxThickness = it }

        section("DETECCAO")
        sl("Tolerancia cor", 10, 150, { Config.colorTolerance.toFloat() }) { Config.colorTolerance = it.toInt() }
        sl("Tam. minimo", 2, 120, { Config.minBlobPixels.toFloat() }) { Config.minBlobPixels = it.toInt() }
        sl("Tam. maximo", 500, 50000, { Config.maxBlobPixels.toFloat() }) { Config.maxBlobPixels = it.toInt() }
        sl("Topo scan", 0, 50, { Config.scanTop * 100 }) { Config.scanTop = it / 100f }
        sl("Base scan", 50, 100, { Config.scanBottom * 100 }) { Config.scanBottom = it / 100f }
        sl("Passo pixel", 1, 3, { Config.pixelStep.toFloat() }) { Config.pixelStep = it.toInt() }
        sw("Filtrar proporcao") { Config.useShapeFilter } { Config.useShapeFilter = it }
        sl("Proporcao min %", 100, 300, { Config.minAspect * 100 }) { Config.minAspect = it / 100f }
        sw("Ignorar minimapa") { Config.hideMinimap } { Config.hideMinimap = it }
        btn("Capturar cor sob a mira") { Config.pickRequested = true }

        section("AIMBOT")
        sw("Aimbot ativo") { Config.aimEnabled } { Config.aimEnabled = it }
        sw("Modo: so no gatilho") { Config.aimMode == 1 } { Config.aimMode = if (it) 1 else 0 }
        sl("FOV (raio)", 40, 700, { Config.aimFov }) { Config.aimFov = it }
        sl("Gatilho", 20, 300, { Config.aimTrigger }) { Config.aimTrigger = it }
        sl("Suavidade %", 5, 100, { Config.aimSmooth * 100 }) { Config.aimSmooth = it / 100f }
        sl("Sensibilidade %", 20, 300, { Config.aimSensitivity * 100 }) { Config.aimSensitivity = it / 100f }
        sl("Deadzone", 0, 40, { Config.aimDeadzone }) { Config.aimDeadzone = it }
        sl("Offset vertical %", 0, 60, { Config.aimVerticalBias * 100 }) { Config.aimVerticalBias = it / 100f }
        sl("Dedo X %", 5, 95, { Config.aimAnchorX * 100 }) { Config.aimAnchorX = it / 100f }
        sl("Dedo Y %", 5, 95, { Config.aimAnchorY * 100 }) { Config.aimAnchorY = it / 100f }
    }

    private fun section(t: String) {
        body.addView(TextView(context).apply {
            text = "-- " + t + " --"
            setTextColor(0xFF3DDC84.toInt())
            textSize = 11f
            setPadding(0, dp(14), 0, dp(4))
        })
    }

    private fun sw(title: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(context).apply {
            text = title
            setTextColor(0xFFDADADA.toInt())
            textSize = 12f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Switch(context).apply {
            isChecked = get()
            setOnCheckedChangeListener { _, v -> set(v) }
        })
        body.addView(row)
    }

    private fun sl(title: String, min: Int, max: Int, get: () -> Float, set: (Float) -> Unit) {
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        val label = TextView(context).apply {
            setTextColor(0xFFDADADA.toInt())
            textSize = 12f
            text = title + ": " + get().toInt()
        }
        val bar = SeekBar(context).apply {
            this.max = (max - min).coerceAtLeast(1)
            progress = (get().toInt() - min).coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = (min + p).toFloat()
                    label.text = title + ": " + v.toInt()
                    set(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        col.addView(label)
        col.addView(bar)
        body.addView(col)
    }

    private fun btn(title: String, action: () -> Unit) {
        body.addView(Button(context).apply {
            text = title
            textSize = 11f
            setOnClickListener { action() }
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

class CheatService : Service() {

    companion object {
        private const val CH_ID = "ffcheat"
        private const val NID = 7
    }

    private lateinit var wm: WindowManager
    private lateinit var esp: EspView
    private lateinit var panel: PanelView
    private var capture: ScreenCapture? = null
    private var aim: AimController? = null
    private val detector = Detector()

    private var screenW = 0
    private var screenH = 0
    private var detScale = 2.5f
    private var curW = 0
    private var curH = 0
    private var lastRotate = 0L
    private var lastFrameTime = 0L

    private val dm by lazy { getSystemService(DISPLAY_SERVICE) as DisplayManager }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id != Display.DEFAULT_DISPLAY) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastRotate < 600) return
            lastRotate = now

            val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") d.getRealMetrics(m)
            if (m.widthPixels == curW && m.heightPixels == curH) return

            curW = m.widthPixels
            curH = m.heightPixels
            screenW = curW
            screenH = curH
            applyOrientation()
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NID, notif())

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        screenW = m.widthPixels
        screenH = m.heightPixels
        curW = screenW
        curH = screenH

        addEspWindow()
        addPanelWindow()

        TouchService.instance?.let {
            aim = AimController(it).also { a -> a.configure(screenW, screenH) }
        }

        startCapture()

        dm.registerDisplayListener(displayListener, Handler(mainLooper))
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (aim == null) {
            TouchService.instance?.let {
                aim = AimController(it).also { a -> a.configure(screenW, screenH) }
            }
        }
        return START_STICKY
    }

    private fun addEspWindow() {
        esp = EspView(this)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        wm.addView(esp, p)
    }

    private fun addPanelWindow() {
        panel = PanelView(this)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = (screenW * 0.03f).toInt()
        p.y = (screenH * 0.12f).toInt()
        wm.addView(panel, p)
    }

    private fun startCapture() {
        val code = ProjectionHolder.resultCode
        val data = ProjectionHolder.data ?: return
        val capW = (screenW * 0.35f).toInt().coerceAtLeast(320)
        val capH = (screenH * 0.35f).toInt().coerceAtLeast(240)
        detScale = screenW.toFloat() / capW
        capture = ScreenCapture(this, code, data) { bmp -> onFrame(bmp) }
        capture?.start(capW, capH, resources.displayMetrics.densityDpi)
    }

    private fun applyOrientation() {
        val capW = (screenW * 0.35f).toInt().coerceAtLeast(320)
        val capH = (screenH * 0.35f).toInt().coerceAtLeast(240)
        detScale = screenW.toFloat() / capW
        capture?.recreate(capW, capH, resources.displayMetrics.densityDpi)
        aim?.configure(screenW, screenH)
    }

    private fun onFrame(bmp: Bitmap) {
        val now = SystemClock.elapsedRealtime()
        if (lastFrameTime != 0L) Config.aimStepMs = (now - lastFrameTime).coerceIn(16L, 60L)
        lastFrameTime = now

        if (Config.pickRequested) {
            val cx = (bmp.width * Config.crossX).toInt().coerceIn(0, bmp.width - 1)
            val cy = (bmp.height * Config.crossY).toInt().coerceIn(0, bmp.height - 1)
            Config.targetColor = bmp.getPixel(cx, cy)
            Config.pickRequested = false
        }

        if (!Config.espEnabled && !Config.aimEnabled) return

        val list = detector.detect(bmp).map {
            Target(
                it.cx * detScale, it.cy * detScale,
                it.left * detScale, it.top * detScale,
                it.right * detScale, it.bottom * detScale,
                it.pixels
            )
        }

        esp.setTargets(list)
        runAim(list)
    }

    private fun runAim(list: List<Target>) {
        val a = aim ?: return
        if (!Config.aimEnabled) { a.stop(); return }

        val cx = screenW * Config.crossX
        val cy = screenH * Config.crossY
        val radius = if (Config.aimMode == 1) Config.aimTrigger else Config.aimFov

        var best: Target? = null
        var bestD = Float.MAX_VALUE

        for (t in list) {
            val hBox = t.bottom - t.top
            val d = hypot(t.cx - cx, (t.cy - hBox * Config.aimVerticalBias) - cy)
            if (d > radius) continue
            if (d < bestD) { bestD = d; best = t }
        }

        val t = best
        if (t == null) { a.stop(); return }

        val hBox = t.bottom - t.top
        val ty = t.cy - hBox * Config.aimVerticalBias

        var dx = (t.cx - cx) * Config.aimSmooth * Config.aimSensitivity
        var dy = (ty - cy) * Config.aimSmooth * Config.aimSensitivity

        if (abs(dx) < Config.aimDeadzone) dx = 0f
        if (abs(dy) < Config.aimDeadzone) dy = 0f
        if (dx == 0f && dy == 0f) return

        if (a.shouldReset()) a.reset()
        a.move(dx, dy)
    }

    override fun onDestroy() {
        try { dm.unregisterDisplayListener(displayListener) } catch (_: Throwable) {}
        aim?.stop()
        capture?.stop()
        try { wm.removeView(esp) } catch (_: Throwable) {}
        try { wm.removeView(panel) } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH_ID, "FF Cheat", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun notif(): Notification =
        NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("FF Cheat ativo")
            .setContentText("ESP + Aimbot rodando")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
}
