package fr.babouch.echecs

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import fr.babouch.echecs.core.BoardDetector
import fr.babouch.echecs.core.BoardRect
import fr.babouch.echecs.core.CalibrationException
import fr.babouch.echecs.core.EngineLine
import fr.babouch.echecs.core.Orientation
import fr.babouch.echecs.core.PieceRecognizer
import fr.babouch.echecs.core.Position
import fr.babouch.echecs.core.RgbImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs
import kotlin.math.min

/**
 * Service au premier plan : garde la capture d'écran ouverte, affiche la bulle flottante et
 * dessine les flèches des meilleurs coups par-dessus l'échiquier.
 */
class OverlayService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "fr.babouch.echecs.STOP"
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var running = false
            private set

        private val ARROW_COLORS = intArrayOf(0xE000C853.toInt(), 0xC02979FF.toInt(), 0xB0FF9100.toInt())
        private val ARROW_WIDTHS = floatArrayOf(0.20f, 0.15f, 0.12f)
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    // Capture (thread « capture »)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var latestImage: Image? = null

    // Analyse et moteur (thread « worker » uniquement)
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var engine: StockfishEngine? = null
    private var cachedRect: BoardRect? = null
    private var cachedScore = 0f

    // Interface (thread principal)
    private var arrowView: ArrowView? = null
    private var panel: LinearLayout? = null
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var details: LinearLayout
    private lateinit var status: TextView
    private var busy = false

    // Dernière position reconnue (thread principal)
    private var lastScreen: CharArray? = null
    private var lastRect: BoardRect? = null
    private var whiteAtBottom = true
    private var whiteToMove = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)!!
        prefs = Prefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        // Android 14+ : le service doit être au premier plan AVANT d'obtenir la MediaProjection.
        startInForeground()
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mp = getSystemService(MediaProjectionManager::class.java)!!.getMediaProjection(code, data)
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)
        startCapture(mp)
        showOverlay()
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        arrowView?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        arrowView = null
        panel = null
        val handler = captureHandler
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.stop()
        projection = null
        val reader = imageReader
        imageReader = null
        handler?.post {
            latestImage?.close()
            latestImage = null
            reader?.close()
        }
        captureThread?.quitSafely()
        runWorker { engine?.stop() }
        worker.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Notification

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Capture de l'écran", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Analyse d'échecs active")
            .setContentText("Touchez la bulle ♞ pour analyser l'échiquier affiché.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "Arrêter", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------- Capture

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun startCapture(mp: MediaProjection) {
        val (w, h) = screenSize()
        val thread = HandlerThread("capture").also { it.start() }
        val handler = Handler(thread.looper)
        captureThread = thread
        captureHandler = handler
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        // On garde toujours la dernière image reçue : l'écran ne produit de nouvelles images que s'il change.
        reader.setOnImageAvailableListener({ r ->
            val img = try {
                r.acquireLatestImage()
            } catch (e: IllegalStateException) {
                null
            }
            if (img != null) {
                latestImage?.close()
                latestImage = img
            }
        }, handler)
        imageReader = reader
        virtualDisplay = mp.createVirtualDisplay(
            "echecs", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        )
    }

    /** Copie la dernière image de l'écran (appelé sur le thread de capture). */
    private fun grabFrame(): RgbImage? {
        val img = latestImage ?: return null
        val plane = img.planes[0]
        val buf = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride
        val w = img.width
        val h = img.height
        val px = IntArray(w * h)
        val row = ByteArray(rs)
        for (y in 0 until h) {
            buf.position(y * rs)
            buf.get(row, 0, min(rs, buf.remaining()))
            var o = 0
            val base = y * w
            for (x in 0 until w) {
                val r = row[o].toInt() and 0xFF
                val g = row[o + 1].toInt() and 0xFF
                val b = row[o + 2].toInt() and 0xFF
                px[base + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                o += ps
            }
        }
        return RgbImage(w, h, px)
    }

    /** Masque l'overlay, attend une image propre de l'écran, puis lance [block] sur le thread d'analyse. */
    private fun captureThen(block: (RgbImage) -> Unit) {
        val handler = captureHandler ?: return
        busy = true
        setOverlayVisible(false)
        handler.postDelayed({
            val frame = try {
                grabFrame()
            } catch (e: Exception) {
                null
            }
            main.post { setOverlayVisible(true) }
            if (frame == null) {
                main.post { fail("Aucune image reçue de la capture d'écran.") }
            } else {
                runWorker {
                    try {
                        block(frame)
                    } catch (e: Exception) {
                        main.post { fail("Erreur : ${e.message}") }
                    }
                }
            }
        }, 400)
    }

    /** Trouve l'échiquier (en réutilisant la dernière position s'il n'a pas bougé). Thread worker. */
    private fun findBoard(img: RgbImage): BoardRect? {
        cachedRect?.let { r ->
            if (r.x + r.size <= img.width + 2 && r.y + r.size <= img.height + 2 &&
                BoardDetector.score(img, r) >= cachedScore * 0.8f
            ) {
                return r
            }
        }
        val r = BoardDetector.detect(img) ?: return null
        cachedRect = r
        cachedScore = BoardDetector.score(img, r)
        return r
    }

    private fun runWorker(task: () -> Unit) {
        try {
            worker.execute(task)
        } catch (e: RejectedExecutionException) {
            // service en cours d'arrêt
        }
    }

    // ---------------------------------------------------------------- Actions

    private fun analyze() {
        if (busy) return
        val templates = TemplateStore.load(this)
        if (templates.isEmpty()) {
            expand()
            showStatus(
                "Pas encore calibré.\nAffichez une partie à la position initiale, puis touchez « Calibrer ».",
            )
            return
        }
        arrowView?.arrows = emptyList()
        arrowView?.board = null
        showStatus("Lecture de l'échiquier…")
        captureThen { img ->
            val rect = findBoard(img)
            if (rect == null) {
                main.post { fail("Échiquier introuvable à l'écran.") }
                return@captureThen
            }
            val screen = PieceRecognizer.recognize(img, rect, templates)
            val wb = when (prefs.orientation) {
                1 -> true
                2 -> false
                else -> PieceRecognizer.guessWhiteAtBottom(screen) ?: prefs.lastCalibrationWhiteAtBottom
            }
            main.post {
                lastRect = rect
                lastScreen = screen
                whiteAtBottom = wb
                whiteToMove = wb // par défaut, c'est au camp du bas (le vôtre) de jouer
                busy = false
                runEngine()
            }
        }
    }

    private fun calibrate() {
        if (busy) return
        arrowView?.arrows = emptyList()
        arrowView?.board = null
        showStatus("Calibration…")
        captureThen { img ->
            cachedRect = null
            val rect = findBoard(img)
            if (rect == null) {
                main.post { fail("Échiquier introuvable à l'écran.") }
                return@captureThen
            }
            val msg = try {
                val cal = PieceRecognizer.calibrate(img, rect)
                prefs.lastCalibrationWhiteAtBottom = cal.whiteAtBottom
                val sets = TemplateStore.add(this, cal.templates)
                "Calibration réussie ✓ ($sets jeu(x) de pièces en mémoire).\n" +
                    "Touchez ♞ pendant une partie pour voir les meilleurs coups."
            } catch (e: CalibrationException) {
                "Calibration impossible : ${e.message}"
            }
            main.post {
                busy = false
                arrowView?.board = rect.toRectF()
                showStatus(msg)
            }
        }
    }

    private fun runEngine() {
        val screen = lastScreen ?: return
        val rect = lastRect ?: return
        var pos = Position(Orientation.toBoard(screen, whiteAtBottom), whiteToMove)
        var error = pos.validate()
        if (error != null && pos.inCheck(!whiteToMove)) {
            // Le camp « sans le trait » est en échec : c'est donc forcément à lui de jouer.
            val other = pos.withSideToMove(!whiteToMove)
            if (other.validate() == null) {
                pos = other
                whiteToMove = other.whiteToMove
                error = null
            }
        }
        arrowView?.board = rect.toRectF()
        arrowView?.arrows = emptyList()
        if (error != null) {
            showStatus("Position non valide : $error\n${pos.toFen()}\n(vérifiez le sens ⇅ ou recalibrez)")
            return
        }
        busy = true
        showStatus("Analyse… (${sideName(whiteToMove)} au trait)")
        val multi = prefs.multiPv.coerceIn(1, 3)
        val time = prefs.moveTimeMs
        val fen = pos.toFen()
        val libDir = applicationInfo.nativeLibraryDir
        runWorker {
            try {
                val eng = engine ?: StockfishEngine("$libDir/libstockfish.so").also { engine = it }
                val lines = eng.analyze(fen, multi, time)
                main.post {
                    busy = false
                    showResult(pos, rect, lines)
                }
            } catch (e: Exception) {
                main.post { fail("Moteur : ${e.message}") }
            }
        }
    }

    private fun showResult(pos: Position, rect: BoardRect, lines: List<EngineLine>) {
        if (lines.isEmpty()) {
            arrowView?.arrows = emptyList()
            showStatus(if (pos.inCheck(pos.whiteToMove)) "Échec et mat." else "Pat (aucun coup possible).")
            return
        }
        val arrows = ArrayList<ArrowView.Arrow>()
        val sb = StringBuilder()
        sb.append(sideName(pos.whiteToMove)).append(" au trait :\n")
        for ((i, line) in lines.withIndex()) {
            val move = line.pv.first()
            val from = Position.squareIndex(move.substring(0, 2))
            val to = Position.squareIndex(move.substring(2, 4))
            if (from != null && to != null && i < ARROW_COLORS.size) {
                arrows += ArrowView.Arrow(
                    rect.cellX(Orientation.colOf(from, whiteAtBottom)),
                    rect.cellY(Orientation.rowOf(from, whiteAtBottom)),
                    rect.cellX(Orientation.colOf(to, whiteAtBottom)),
                    rect.cellY(Orientation.rowOf(to, whiteAtBottom)),
                    ARROW_COLORS[i],
                    rect.square * ARROW_WIDTHS[i],
                )
            }
            sb.append(COLOR_NAMES.getOrElse(i) { "" }).append(' ')
                .append(pos.describe(move)).append("   ")
                .append(line.scoreText(pos.whiteToMove))
                .append("  (prof. ").append(line.depth).append(")\n")
        }
        sb.append(pos.toFen())
        arrowView?.arrows = arrows
        showStatus(sb.toString())
    }

    private fun sideName(white: Boolean) = if (white) "Blancs" else "Noirs"

    private fun fail(msg: String) {
        busy = false
        expand()
        showStatus(msg)
    }

    private fun BoardRect.toRectF() = RectF(x, y, x + size, y + size)

    // ---------------------------------------------------------------- Interface flottante

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun overlayParams(width: Int, height: Int, flags: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT,
    )

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showOverlay() {
        // 1) Calque des flèches : ne capte aucun toucher. Opacité ≤ 0,8 obligatoire depuis Android 12
        //    pour que les touchers passent à l'application en dessous.
        val arrows = ArrowView(this)
        val ap = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        ap.alpha = 0.8f
        if (Build.VERSION.SDK_INT >= 28) {
            ap.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(arrows, ap)
        arrowView = arrows

        // 2) Bulle + panneau d'informations, déplaçables.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        val bubble = TextView(this).apply {
            text = "♞"
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE0263238.toInt())
                setStroke(dp(2f), 0xFF00C853.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(56f), dp(56f))
        }
        root.addView(bubble)

        details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(0xE0263238.toInt())
            }
            visibility = View.GONE
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            maxWidth = dp(320f)
            minWidth = dp(240f)
        }
        // Largeurs explicites « contenu » : dans un parent en WRAP_CONTENT, un enfant en MATCH_PARENT
        // (valeur par défaut d'un LinearLayout vertical) ne compte pas, et le panneau se réduisait
        // à la largeur de la bulle.
        fun wrap() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        details.addView(status, wrap())
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setAllCaps(false)
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8f), 0, dp(8f), 0)
            setOnClickListener { action() }
            buttons.addView(this)
        }
        button("⇄ Trait") {
            if (!busy && lastScreen != null) {
                whiteToMove = !whiteToMove
                runEngine()
            }
        }
        button("⇅ Sens") {
            if (!busy && lastScreen != null) {
                whiteAtBottom = !whiteAtBottom
                whiteToMove = whiteAtBottom
                runEngine()
            }
        }
        button("Calibrer") { calibrate() }
        button("✕") {
            details.visibility = View.GONE
            arrowView?.arrows = emptyList()
            arrowView?.board = null
        }
        details.addView(buttons, wrap())
        root.addView(details, wrap().apply { topMargin = dp(4f) })

        panelParams = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8f)
            y = dp(140f)
        }

        // Glisser pour déplacer, toucher pour analyser.
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        bubble.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = panelParams.x; startY = panelParams.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        panelParams.x = startX + dx.toInt()
                        panelParams.y = startY + dy.toInt()
                        panel?.let { wm.updateViewLayout(it, panelParams) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) analyze()
            }
            true
        }
        wm.addView(root, panelParams)
        panel = root
    }

    private fun expand() {
        if (::details.isInitialized) details.visibility = View.VISIBLE
    }

    private fun showStatus(text: String) {
        if (!::status.isInitialized) return
        expand()
        status.text = text
    }

    private fun setOverlayVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        arrowView?.visibility = v
        panel?.visibility = v
    }
}

private val COLOR_NAMES = listOf("🟢", "🔵", "🟠")
