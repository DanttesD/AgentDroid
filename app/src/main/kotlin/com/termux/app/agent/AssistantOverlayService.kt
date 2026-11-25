package com.termux.app.agent

import android.Manifest
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.agentdroid.agent.AgentLoop
import com.agentdroid.agent.StreamListener
import com.agentdroid.agent.ThemeColors
import com.agentdroid.agent.ToolCall
import com.agentdroid.exec.BackendSelector
import com.termux.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Floating assistant: a draggable button over any app; tap it to open a panel, speak or type a task and the
 * agent works with the screen (accessibility). While it works a glowing border runs around the screen and a small
 * pill lets you stop it. Confirmations are asked inside the overlay. The look follows the voice overlay idea of
 * the MIT-licensed droidclaw project; the code is original.
 **/
class AssistantOverlayService : Service() {

    private val ui: Handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var log: TextView? = null
    private var input: EditText? = null
    private var mic: Button? = null
    private var send: Button? = null
    private var pill: TextView? = null
    private var glow: GlowView? = null
    private var confirm: LinearLayout? = null
    private var confirmText: TextView? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening: Boolean = false
    private var panelShown: Boolean = false
    private var pillShown: Boolean = false
    private var glowShown: Boolean = false
    private var confirmShown: Boolean = false

    @Volatile private var loop: AgentLoop? = null
    @Volatile private var busy: Boolean = false
    @Volatile private var confirmLatch: CountDownLatch? = null
    private val confirmOk: AtomicBoolean = AtomicBoolean(false)

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf()
            return Service.START_NOT_STICKY
        }
        startForeground(7003, notification())
        if (!canDraw()) {
            Toast.makeText(this, R.string.assistant_overlay_btn, Toast.LENGTH_LONG).show()
            stopSelf()
            return Service.START_NOT_STICKY
        }
        if (bubble == null) {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager?
            buildViews()
            wm!!.addView(bubble, bubbleLp)
            running = true
            ApprovalCenter.setOverlay(ApprovalCenter.Presenter(this::askInOverlay))
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        running = false
        ApprovalCenter.setOverlay(null)
        val l: AgentLoop? = loop
        if (l != null) {
            l.cancel()
        }
        if (recognizer != null) {
            try {
                recognizer!!.destroy()
            } catch (ignored: RuntimeException) {
            }
            recognizer = null
        }
        if (glow != null) {
            glow!!.stop()
        }
        removeQuiet(bubble)
        removeQuiet(panel)
        removeQuiet(pill)
        removeQuiet(glow)
        removeQuiet(confirm)
        bubble = null
        super.onDestroy()
    }

    private fun canDraw(): Boolean {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
    }

    private fun notification(): Notification {
        val nm: NotificationManager? = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.assistant_title), NotificationManager.IMPORTANCE_LOW))
        }
        val open: PendingIntent = PendingIntent.getActivity(this, 0, Intent(this, AssistantActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
        val off: PendingIntent = PendingIntent.getService(this, 1, Intent(this, AssistantOverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT)
        val nb: Notification.Builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        nb.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(getString(R.string.assistant_notif)).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.assistant_notif_stop), off).build())
        return nb.build()
    }

    // ---------- views ----------
    private fun dp(v: Float): Int {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()))
    }

    private fun lp(w: Int, h: Int, flags: Int, gravity: Int): WindowManager.LayoutParams {
        val type: Int = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT)
        p.gravity = gravity
        return p
    }

    private var tc: ThemeColors? = null

    private fun buildViews() {
        tc = AppTheme.colors(this)
        val screenW: Int = getResources().getDisplayMetrics().widthPixels
        val screenH: Int = getResources().getDisplayMetrics().heightPixels

        bubble = TextView(this)
        bubble!!.setText("✦")
        bubble!!.setTextColor(Color.WHITE)
        bubble!!.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22.0f)
        bubble!!.setGravity(Gravity.CENTER)
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF4285F4.toInt(), 0xFF9B72CB.toInt(), 0xFFD96570.toInt()))
        bg.setShape(GradientDrawable.OVAL)
        bg.setStroke(dp(2.0f), 0x66FFFFFF)
        bubble!!.setBackground(bg)
        bubbleLp = lp(dp(52.0f), dp(52.0f), PASSIVE, Gravity.TOP or Gravity.START)
        bubbleLp!!.x = dp(8.0f)
        bubbleLp!!.y = screenH / 2
        bubble!!.setOnTouchListener(object : View.OnTouchListener {
            @JvmField var downX: Float = 0f
            @JvmField var downY: Float = 0f
            @JvmField var startX: Int = 0
            @JvmField var startY: Int = 0
            @JvmField var moved: Boolean = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.getActionMasked()) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = bubbleLp!!.x
                        startY = bubbleLp!!.y
                        downX = e.getRawX()
                        downY = e.getRawY()
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val mx: Float = e.getRawX() - downX
                        val my: Float = e.getRawY() - downY
                        if (Math.abs(mx) > dp(6.0f) || Math.abs(my) > dp(6.0f)) {
                            moved = true
                        }
                        if (moved) {
                            bubbleLp!!.x = startX + mx.toInt()
                            bubbleLp!!.y = startY + my.toInt()
                            wm!!.updateViewLayout(bubble, bubbleLp)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            onBubbleTap()
                        }
                        return true
                    }
                    else -> {
                        return true
                    }
                }
            }
        })

        panel = LinearLayout(this)
        panel!!.setOrientation(LinearLayout.VERTICAL)
        panel!!.setPadding(dp(14.0f), dp(12.0f), dp(14.0f), dp(12.0f))
        val pbg = GradientDrawable()
        pbg.setColor(tc!!.s1 and 0xFFFFFF or 0xF2000000.toInt())
        pbg.setCornerRadius(dp(22.0f).toFloat())
        pbg.setStroke(dp(1.0f), tc!!.line)
        panel!!.setBackground(pbg)

        val head = LinearLayout(this)
        head.setOrientation(LinearLayout.HORIZONTAL)
        head.setGravity(Gravity.CENTER_VERTICAL)
        val title = TextView(this)
        title.setText(R.string.assistant_title)
        title.setTextColor(tc!!.on)
        title.setTextSize(15.0f)
        title.setTypeface(null, Typeface.BOLD)
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val fresh: TextView = smallAction(getString(R.string.assistant_new))
        fresh.setOnClickListener(View.OnClickListener { v ->
            if (!busy) {
                loop = null
                log!!.setText("")
            }
        })
        head.addView(fresh)
        val close: TextView = smallAction("✕")
        close.setOnClickListener(View.OnClickListener { v -> hidePanel() })
        head.addView(close)
        panel!!.addView(head)

        log = TextView(this)
        log!!.setTextColor(tc!!.on)
        log!!.setTextSize(14.0f)
        log!!.setMaxLines(9)
        log!!.setMovementMethod(ScrollingMovementMethod())
        log!!.setPadding(0, dp(8.0f), 0, dp(8.0f))
        panel!!.addView(log, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(this)
        row.setOrientation(LinearLayout.HORIZONTAL)
        row.setGravity(Gravity.CENTER_VERTICAL)
        input = EditText(this)
        input!!.setHint(R.string.assistant_hint)
        input!!.setHintTextColor(tc!!.on2)
        input!!.setTextColor(tc!!.on)
        input!!.setTextSize(15.0f)
        input!!.setMaxLines(3)
        input!!.setImeOptions(EditorInfo.IME_ACTION_SEND)
        input!!.setOnEditorActionListener(TextView.OnEditorActionListener { v, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit()
                return@OnEditorActionListener true
            }
            return@OnEditorActionListener false
        })
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        mic = Button(this)
        mic!!.setText("\ud83c\udfa4")
        mic!!.setOnClickListener(View.OnClickListener { v -> toggleListening() })
        row.addView(mic, LinearLayout.LayoutParams(dp(52.0f), dp(48.0f)))
        send = Button(this)
        send!!.setText("➤")
        send!!.setOnClickListener(View.OnClickListener { v -> submit() })
        row.addView(send, LinearLayout.LayoutParams(dp(52.0f), dp(48.0f)))
        panel!!.addView(row)

        panelLp = lp(screenW - dp(16.0f), ViewGroup.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        panelLp!!.y = dp(16.0f)
        panelLp!!.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        pill = TextView(this)
        pill!!.setText(R.string.assistant_working)
        pill!!.setTextColor(tc!!.on)
        pill!!.setTextSize(13.0f)
        pill!!.setPadding(dp(16.0f), dp(10.0f), dp(16.0f), dp(10.0f))
        pill!!.setMaxLines(2)
        val pillBg = GradientDrawable()
        pillBg.setColor(tc!!.s1 and 0xFFFFFF or 0xEE000000.toInt())
        pillBg.setCornerRadius(dp(22.0f).toFloat())
        pillBg.setStroke(dp(1.0f), tc!!.line)
        pill!!.setBackground(pillBg)
        pill!!.setOnClickListener(View.OnClickListener { v ->
            val l: AgentLoop? = loop
            if (l != null) {
                l.cancel()
            }
        })

        glow = GlowView(this)

        confirm = LinearLayout(this)
        confirm!!.setOrientation(LinearLayout.VERTICAL)
        confirm!!.setPadding(dp(16.0f), dp(14.0f), dp(16.0f), dp(12.0f))
        val cbg = GradientDrawable()
        cbg.setColor(tc!!.s2 and 0xFFFFFF or 0xF5000000.toInt())
        cbg.setCornerRadius(dp(22.0f).toFloat())
        cbg.setStroke(dp(2.0f), tc!!.err)
        confirm!!.setBackground(cbg)
        confirmText = TextView(this)
        confirmText!!.setTextColor(tc!!.on)
        confirmText!!.setTextSize(15.0f)
        confirm!!.addView(confirmText)
        val buttons = LinearLayout(this)
        buttons.setOrientation(LinearLayout.HORIZONTAL)
        buttons.setGravity(Gravity.END)
        val deny = Button(this)
        deny.setText(R.string.assistant_deny)
        deny.setOnClickListener(View.OnClickListener { v -> answerConfirm(false) })
        val allow = Button(this)
        allow.setText(R.string.assistant_allow)
        allow.setOnClickListener(View.OnClickListener { v -> answerConfirm(true) })
        buttons.addView(deny)
        buttons.addView(allow)
        confirm!!.addView(buttons)
    }

    private fun smallAction(text: String): TextView {
        val t = TextView(this)
        t.setText(text)
        t.setTextColor(tc!!.on2)
        t.setTextSize(13.0f)
        t.setPadding(dp(10.0f), dp(6.0f), dp(10.0f), dp(6.0f))
        return t
    }

    private fun removeQuiet(v: View?) {
        if (v == null || wm == null) {
            return
        }
        try {
            wm!!.removeView(v)
        } catch (ignored: RuntimeException) {
        }
    }

    private fun onBubbleTap() {
        if (busy) {
            val l: AgentLoop? = loop
            if (l != null) {
                l.cancel()
            }
            return
        }
        if (panelShown) {
            hidePanel()
        } else {
            showPanel()
        }
    }

    private fun showPanel() {
        if (panelShown) {
            return
        }
        wm!!.addView(panel, panelLp)
        panelShown = true
        input!!.requestFocus()
    }

    private fun hidePanel() {
        if (!panelShown) {
            return
        }
        stopListening()
        removeQuiet(panel)
        panelShown = false
    }

    private fun showPill(text: String) {
        pill!!.setText(text)
        if (pillShown) {
            return
        }
        val p: WindowManager.LayoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, PASSIVE, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        p.y = dp(36.0f)
        wm!!.addView(pill, p)
        pillShown = true
    }

    private fun hidePill() {
        if (pillShown) {
            removeQuiet(pill)
            pillShown = false
        }
    }

    private fun showGlow() {
        if (glowShown) {
            return
        }
        val p: WindowManager.LayoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, PASSIVE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, Gravity.TOP or Gravity.START)
        wm!!.addView(glow, p)
        glow!!.start()
        glowShown = true
    }

    private fun hideGlow() {
        if (glowShown) {
            glow!!.stop()
            removeQuiet(glow)
            glowShown = false
        }
    }

    // ---------- confirmations ----------

    /** Called from the agent thread: asks the user inside the overlay and waits.*/
    private fun askInOverlay(description: String, force: Boolean): Boolean {
        val latch = CountDownLatch(1)
        confirmOk.set(false)
        confirmLatch = latch
        ui.post(Runnable {
            if (bubble == null) {
                latch.countDown()
                return@Runnable
            }
            confirmText!!.setText(description)
            if (!confirmShown) {
                val p: WindowManager.LayoutParams = lp(getResources().getDisplayMetrics().widthPixels - dp(24.0f), ViewGroup.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                p.y = dp(90.0f)
                wm!!.addView(confirm, p)
                confirmShown = true
            }
        })
        try {
            latch.await(3L, TimeUnit.MINUTES)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        ui.post(Runnable {
            if (confirmShown) {
                removeQuiet(confirm)
                confirmShown = false
            }
        })
        confirmLatch = null
        return confirmOk.get()
    }

    private fun answerConfirm(ok: Boolean) {
        confirmOk.set(ok)
        val l: CountDownLatch? = confirmLatch
        if (l != null) {
            l.countDown()
        }
    }

    // ---------- voice ----------
    private fun toggleListening() {
        if (listening) {
            stopListening()
            return
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.assistant_mic_perm, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.assistant_no_speech, Toast.LENGTH_LONG).show()
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer!!.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle) {
                }

                override fun onBeginningOfSpeech() {
                }

                override fun onRmsChanged(rmsdB: Float) {
                }

                override fun onBufferReceived(buffer: ByteArray) {
                }

                override fun onEndOfSpeech() {
                }

                override fun onEvent(eventType: Int, params: Bundle) {
                }

                override fun onError(error: Int) {
                    setListening(false)
                }

                override fun onPartialResults(partial: Bundle) {
                    val t: String = firstResult(partial)
                    if (!t.isEmpty()) {
                        input!!.setText(t)
                        input!!.setSelection(t.length)
                    }
                }

                override fun onResults(results: Bundle) {
                    setListening(false)
                    val t: String = firstResult(results)
                    if (!t.isEmpty()) {
                        input!!.setText(t)
                        submit()
                    }
                }
            })
        }
        val i: Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU").putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        setListening(true)
        recognizer!!.startListening(i)
    }

    private fun stopListening() {
        if (recognizer != null && listening) {
            try {
                recognizer!!.stopListening()
            } catch (ignored: RuntimeException) {
            }
        }
        setListening(false)
    }

    private fun setListening(on: Boolean) {
        listening = on
        mic!!.setText(if (on) "■" else "\ud83c\udfa4")
    }

    // ---------- running the agent ----------
    private fun say(text: String) {
        log!!.setText(text)
        log!!.scrollTo(0, 0)
    }

    private fun submit() {
        val msg: String = (input!!.getText().toString() as java.lang.String).trim()
        if (msg.isEmpty() || busy) {
            return
        }
        val cfg = AgentConfig(this)
        if (cfg.apiKey().isEmpty() && !cfg.provider()!!.equals("custom")) {
            say(getString(R.string.assistant_no_key))
            return
        }
        if (!cfg.baseUrlValid()) {
            say(getString(R.string.agent_bad_url))
            return
        }
        if (!AgentAccessibilityService.isRunning()) {
            say(getString(R.string.assistant_no_a11y))
        }
        input!!.setText("")
        stopListening()
        busy = true
        hidePanel()
        showGlow()
        showPill(getString(R.string.assistant_working))
        val answer = StringBuilder()
        val app: Context = getApplicationContext()
        Thread(Runnable {
            try {
                var l: AgentLoop? = loop
                if (l == null) {
                    val sel: BackendSelector = AgentBackends.create(app)
                    val pin: String? = AgentConfig(app).backendPin()
                    if (!pin!!.isEmpty()) {
                        try {
                            sel.pin(pin)
                        } catch (ignored: Exception) {
                        }
                    }
                    val backend = SelectedBackend(sel)
                    if (sel.select() == null) {
                        ui.post(Runnable { say(getString(R.string.assistant_no_backend)) })
                        return@Runnable
                    }
                    l = PhoneAgent.create(app, backend, PhoneAgent.approver(app))
                    loop = l
                }
                l.send(msg, object : StreamListener() {
                    override fun onText(delta: String) {
                        answer.append(delta)
                    }

                    override fun onToolCall(call: ToolCall) {
                        answer.setLength(0)
                        val t: String = "▶ " + call.name
                        ui.post(Runnable {
                            if (pillShown) {
                                pill!!.setText(t)
                            }
                        })
                    }

                    override fun onError(m: String) {
                        ui.post(Runnable { say("⚠ " + m) })
                    }
                })
            } catch (e: Exception) {
                ui.post(Runnable { say("⚠ " + e.message) })
            } finally {
                val done: String = (answer.toString() as java.lang.String).trim()
                ui.post(Runnable {
                    busy = false
                    hideGlow()
                    hidePill()
                    if (!done.isEmpty()) {
                        say(done)
                    }
                    if (bubble != null) {
                        showPanel()
                    }
                })
            }
        }, "assistant-loop").start()
    }

    // ---------- screen glow ----------

    /** A colored light running around the screen edge while the agent works.*/
    class GlowView : View {
        private val wide: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thin: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val matrix: Matrix = Matrix()
        private val rect: RectF = RectF()
        private var gradient: SweepGradient? = null
        private var animator: ValueAnimator? = null
        private var angle: Float = 0f

        constructor(c: Context) : super(c) {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            val d: Float = c.getResources().getDisplayMetrics().density
            wide.setStyle(Paint.Style.STROKE)
            wide.setStrokeWidth(14 * d)
            wide.setAlpha(150)
            wide.setMaskFilter(BlurMaskFilter(18 * d, BlurMaskFilter.Blur.NORMAL))
            thin.setStyle(Paint.Style.STROKE)
            thin.setStrokeWidth(3 * d)
        }

        protected override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            gradient = SweepGradient(w / 2f, h / 2f, intArrayOf(0xFF4285F4.toInt(), 0xFF9B72CB.toInt(), 0xFFD96570.toInt(), 0xFFF9AB00.toInt(), 0xFF34A853.toInt(), 0xFF4285F4.toInt()), null)
            wide.setShader(gradient)
            thin.setShader(gradient)
            val d: Float = getResources().getDisplayMetrics().density
            rect.set(4 * d, 4 * d, w - 4 * d, h - 4 * d)
        }

        protected override fun onDraw(canvas: Canvas) {
            if (gradient == null) {
                return
            }
            matrix.setRotate(angle, getWidth() / 2f, getHeight() / 2f)
            gradient!!.setLocalMatrix(matrix)
            val r: Float = 28 * getResources().getDisplayMetrics().density
            canvas.drawRoundRect(rect, r, r, wide)
            canvas.drawRoundRect(rect, r, r, thin)
        }

        fun start() {
            if (animator != null) {
                return
            }
            animator = ValueAnimator.ofFloat(0f, 360f)
            animator!!.setDuration(3200L)
            animator!!.setRepeatCount(ValueAnimator.INFINITE)
            animator!!.setInterpolator(LinearInterpolator())
            animator!!.addUpdateListener(ValueAnimator.AnimatorUpdateListener { a ->
                angle = a.getAnimatedValue() as Float
                invalidate()
            })
            animator!!.start()
        }

        fun stop() {
            if (animator != null) {
                animator!!.cancel()
                animator = null
            }
        }
    }

    companion object {
        const val ACTION_STOP: String = "com.agentdroid.assistant.STOP"
        private const val CHANNEL: String = "agentdroid_assistant"
        @Volatile private var running: Boolean = false

        @JvmStatic
        fun isRunning(): Boolean {
            return running
        }

        @JvmStatic
        fun start(c: Context) {
            val i = Intent(c, AssistantOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i)
            } else {
                c.startService(i)
            }
        }

        @JvmStatic
        fun stop(c: Context) {
            c.stopService(Intent(c, AssistantOverlayService::class.java))
        }

        private const val PASSIVE: Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        @JvmStatic
        private fun firstResult(b: Bundle?): String {
            val l: ArrayList<String>? = if (b == null) null else b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            return if (l == null || l.isEmpty()) "" else l.get(0)
        }
    }
}
