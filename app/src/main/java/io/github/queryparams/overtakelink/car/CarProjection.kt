package io.github.queryparams.overtakelink.car

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.content.Intent
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.ProjectionWatchdogPolicy
import com.andrerinas.openheadunit.aap.protocol.messages.TouchEvent
import com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.decoder.video.DecoderStopPolicy
import com.andrerinas.openheadunit.decoder.video.VideoDimensionsListener
import com.andrerinas.openheadunit.input.TouchCoordinateMapper
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.andrerinas.openheadunit.view.IProjectionView
import com.andrerinas.openheadunit.view.ProjectionView
import com.andrerinas.openheadunit.view.ProjectionViewScaler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import io.github.queryparams.overtakelink.link.EventLog

/**
 * Android Auto на экране машины. Окно-презентация на собственном виртуальном дисплее: стороннее
 * приложение не может запускать activity на недоверенный виртуальный дисплей (нужно системное
 * ACTIVITY_EMBEDDING), а окно на своём приватном дисплее — может, и без оглядки на блокировку телефона.
 * Делает то же, что экран проекции Open Headunit: отдаёт свою поверхность декодеру видео AA,
 * запускает чтение после рукопожатия, просит видеофокус и пересчитывает касания в координаты AA.
 */
class CarProjection private constructor(context: Context, display: Display) :
    Presentation(context, display), IProjectionView.Callbacks, VideoDimensionsListener {

    private val app = App.provide(context)
    private val commManager get() = app.commManager
    private val videoDecoder get() = app.videoDecoder
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var view: ProjectionView
    private lateinit var splash: TextView
    /** Пиксель для принудительной перерисовки: кодировщик выдаёт кадр, только когда картинка меняется. */
    private lateinit var nudgePixel: android.view.View
    /** AA был на экране и закрылся (Exit в его меню): касание экрана машины открывает его снова. */
    private var closed = false
    private var wasProjecting = false
    private var surfaceSet = false
    private var downTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareSettings(app.settings)
        HeadUnitScreenConfig.init(context, context.resources.displayMetrics, app.settings)
        val root = FrameLayout(context)
        root.setBackgroundColor(Color.BLACK)
        view = ProjectionView(context)
        view.addCallback(this)
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        splash = TextView(context).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            textSize = 40f
            gravity = Gravity.CENTER
        }
        root.addView(splash, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        showSplash(context.getString(R.string.ol_car_starting))
        nudgePixel = android.view.View(context).apply { setBackgroundColor(Color.BLACK) }
        root.addView(nudgePixel, FrameLayout.LayoutParams(1, 1))
        setContentView(root)
        videoDecoder.dimensionsListener = this
        scope.launch {
            commManager.connectionState.collect { state ->
                EventLog.add("Android Auto: ${state.javaClass.simpleName}")
                if (state is CommManager.ConnectionState.TransportStarted) wasProjecting = true
                if (state is CommManager.ConnectionState.Disconnected && wasProjecting) {
                    wasProjecting = false
                    closed = true
                    showSplash(context.getString(R.string.ol_car_closed))
                }
                if (state is CommManager.ConnectionState.HandshakeComplete) {
                    HeadUnitScreenConfig.lockResolution()
                    if (surfaceSet) commManager.startReading()
                    main.removeCallbacks(firstFrameWatchdog)
                    main.postDelayed(firstFrameWatchdog, 1000)
                }
            }
        }
    }

    /** Как сторож экрана проекции OHU: пока на поверхности нет картинки, просим у AA видео. */
    private val firstFrameWatchdog = object : Runnable {
        override fun run() {
            if (!ProjectionWatchdogPolicy.shouldNudgeForFirstFrame(
                    sessionLive = ProjectionWatchdogPolicy.isSessionLive(commManager.connectionState.value),
                    surfaceSet = surfaceSet,
                    crediblePictureOnSurface = videoDecoder.hasCrediblePicture,
                    warmRelaunchCycleSpent = false,
                )
            ) return
            commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
            main.postDelayed(this, 1500)
        }
    }

    override fun onSurfaceCreated(surface: Surface) {}

    override fun onSurfaceChanged(surface: Surface, width: Int, height: Int) {
        val changed = !videoDecoder.isCurrentSurface(surface)
        surfaceSet = true
        videoDecoder.setSurface(surface)
        HeadUnitScreenConfig.updateSurfaceDimensions(width, height)
        EventLog.add("Экран машины: поверхность AA ${width}x$height, AA рисует " +
            "${HeadUnitScreenConfig.getNegotiatedWidth()}x${HeadUnitScreenConfig.getNegotiatedHeight()}, " +
            "поля ${HeadUnitScreenConfig.getWidthMargin()}x${HeadUnitScreenConfig.getHeightMargin()}")
        when (commManager.connectionState.value) {
            is CommManager.ConnectionState.Connected -> scope.launch { commManager.startHandshake() }
            is CommManager.ConnectionState.StartingTransport -> {}
            is CommManager.ConnectionState.HandshakeComplete -> scope.launch { commManager.startReading() }
            is CommManager.ConnectionState.TransportStarted ->
                if (changed) commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
            else -> commManager.send(VideoFocusEvent(gain = true, unsolicited = false))
        }
        if (videoDecoder.videoWidth > 0 && videoDecoder.videoHeight > 0) {
            onVideoDimensionsChanged(videoDecoder.videoWidth, videoDecoder.videoHeight)
        }
        main.removeCallbacks(firstFrameWatchdog)
        main.postDelayed(firstFrameWatchdog, 1000)
    }

    override fun onSurfaceDestroyed(surface: Surface) {
        if (!videoDecoder.isCurrentSurface(surface)) return
        surfaceSet = false
        commManager.send(VideoFocusEvent(gain = false, unsolicited = false))
        videoDecoder.detachSurfaceIfCurrent(surface, DecoderStopPolicy.REASON_SURFACE_DESTROYED)
    }

    override fun onVideoDimensionsChanged(width: Int, height: Int) {
        main.post {
            EventLog.add("Android Auto: видео ${width}x$height")
            view.setVideoSize(width, height)
            ProjectionViewScaler.updateScale(view, width, height)
        }
    }

    private fun showSplash(text: String) {
        splash.text = text
        splash.visibility = android.view.View.VISIBLE
        videoDecoder.onFirstFrameListener = { main.post { splash.visibility = android.view.View.GONE } }
    }

    /** Касание с экрана ГУ: action как в MotionEvent, координаты — пиксели экрана машины. */
    private fun handleTouch(action: Int, x: Float, y: Float) {
        if (closed) {
            if (action == MotionEvent.ACTION_UP) {
                closed = false
                showSplash(context.getString(R.string.ol_car_starting))
                EventLog.add("Экран машины: касание — открываем Android Auto снова")
                context.startService(Intent(context, AapService::class.java).setAction(AapService.ACTION_START_SELF_MODE))
            }
            return
        }
        val w = HeadUnitScreenConfig.getNegotiatedWidth()
        val h = HeadUnitScreenConfig.getNegotiatedHeight()
        if (w <= 0 || h <= 0 || view.width <= 0 || view.height <= 0) return
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val ev = MotionEvent.obtain(downTime, now, action, x, y, 0)
        try {
            val aaAction = TouchEvent.motionEventToAction(ev) ?: return
            val p = TouchCoordinateMapper.map(
                rawX = x,
                rawY = y,
                inputSurfaceWidth = view.width.toFloat(),
                inputSurfaceHeight = view.height.toFloat(),
                negotiatedWidth = w,
                negotiatedHeight = h,
                marginWidth = HeadUnitScreenConfig.getWidthMargin().toFloat(),
                marginHeight = HeadUnitScreenConfig.getHeightMargin().toFloat(),
                fitMode = app.settings.videoFitMode,
                hudMirroring = false
            )
            commManager.send(TouchEvent(SystemClock.elapsedRealtime(), aaAction, 0, listOf(Triple(0, p.x, p.y))))
        } finally {
            ev.recycle()
        }
    }

    private fun release() {
        scope.cancel()
        main.removeCallbacks(firstFrameWatchdog)
        if (videoDecoder.dimensionsListener === this) videoDecoder.dimensionsListener = null
        videoDecoder.onFirstFrameListener = null
        view.removeCallback(this)
        dismiss()
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())

        /**
         * Настройки приёмника AA под перекодирование: AA рисует 1080p и раскладывает интерфейс в полосе
         * экрана машины (поля сверху и снизу), полоса попадает в кодировщик пиксель в пиксель, без растяжения.
         */
        @JvmStatic
        fun prepareSettings(settings: com.andrerinas.openheadunit.utils.Settings) {
            settings.resolutionId = com.andrerinas.openheadunit.utils.Settings.Resolution._1920x1080.id
            settings.videoFitMode = com.andrerinas.openheadunit.utils.Settings.VideoFitMode.CONTAIN
            // Плотность — автоматически по полосе экрана (прямой режим задаёт свою).
            settings.dpiPixelDensity = 0
            settings.videoCodec = "Auto"
            // 30 к/с: ГУ всё равно показывает ~24, а распаковывать вдвое меньше кадров — меньше расход батареи.
            settings.fpsLimit = 30
        }
        private var current: CarProjection? = null

        /** Показать Android Auto на экране машины (с любого потока). */
        @JvmStatic
        fun show(context: Context, screen: CarScreen) = main.post {
            // Показ привязан к своему экрану: если до исполнения кабель переподключили и экран сменился,
            // старая презентация не создаётся и не перепишет настройки нового показа.
            if (CarScreen.current() !== screen) return@post
            val display = screen.display() ?: return@post
            if (current != null) return@post
            try {
                current = CarProjection(context.applicationContext, display).also { it.show() }
                EventLog.add("Экран машины: окно Android Auto открыто на дисплее #${display.displayId}")
            } catch (e: Exception) {
                EventLog.error("окно Android Auto на экране машины", e)
            }
        }

        /** Сообщение на экране машины вместо картинки AA (например, «сервер AA выключен»). */
        @JvmStatic
        fun notice(text: String) = main.post { current?.showSplash(text) }

        @JvmStatic
        fun close() = main.post {
            current?.release()
            current = null
        }

        /** Касания по порядку для главного потока: подряд идущие движения сливаются, нажатия и отпускания — нет. */
        private val touches = ArrayDeque<FloatArray>()
        private var touchesScheduled = false
        private const val TOUCHES_MAX = 64
        private val redrawPending = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Касание с экрана ГУ. Порядок DOWN → MOVE → UP сохраняется; подряд идущие движения сливаются в одно с
         * последними координатами, так что очередь не растёт, если главный поток не успевает. Переполнение (за 64
         * события) — сначала выпадают движения, границы жеста остаются.
         */
        @JvmStatic
        fun touch(action: Int, x: Float, y: Float) {
            synchronized(touches) {
                val last = touches.lastOrNull()
                if (action == MotionEvent.ACTION_MOVE && last != null && last[0].toInt() == MotionEvent.ACTION_MOVE) {
                    last[1] = x
                    last[2] = y
                } else {
                    if (touches.size >= TOUCHES_MAX) {
                        val move = touches.indexOfFirst { it[0].toInt() == MotionEvent.ACTION_MOVE }
                        if (move >= 0) {
                            touches.removeAt(move)
                        } else {
                            // Одни нажатия и отпускания — главный поток сильно отстал. Не терять конец жеста молча:
                            // всё накопленное отменяется, AA получает отмену (палец не «зависнет»), новое нажатие — заново.
                            touches.clear()
                            touches.addLast(floatArrayOf(MotionEvent.ACTION_CANCEL.toFloat(), x, y))
                            if (action != MotionEvent.ACTION_DOWN) {
                                if (!touchesScheduled) {
                                    touchesScheduled = true
                                    main.post(drainTouches)
                                }
                                return
                            }
                        }
                    }
                    touches.addLast(floatArrayOf(action.toFloat(), x, y))
                }
                if (touchesScheduled) return
                touchesScheduled = true
            }
            main.post(drainTouches)
        }

        private val drainTouches = Runnable {
            while (true) {
                val e = synchronized(touches) {
                    touches.removeFirstOrNull() ?: run { touchesScheduled = false; null }
                } ?: break
                current?.handleTouch(e[0].toInt(), e[1], e[2])
            }
        }

        /**
         * Новый кадр без изменений на экране AA: ГУ начало показ или просит ключевой кадр, а
         * неподвижная картинка иначе не дала бы кодировщику ни одного кадра — на ГУ был бы чёрный экран.
         */
        @JvmStatic
        fun redraw() {
            // Частые запросы ключевого кадра — одна перерисовка в очереди, а не сотни.
            if (!redrawPending.compareAndSet(false, true)) return
            main.post {
                redrawPending.set(false)
                val p = current?.nudgePixel ?: return@post
                p.setBackgroundColor(if (p.tag == true) Color.BLACK else 0xFF010101.toInt())
                p.tag = p.tag != true
            }
        }
    }
}
