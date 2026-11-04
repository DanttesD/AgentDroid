package com.termux.app.agent;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.agentdroid.agent.AgentLoop;
import com.agentdroid.agent.StreamListener;
import com.agentdroid.agent.ToolCall;
import com.agentdroid.exec.BackendSelector;
import com.termux.R;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Floating assistant: a draggable button over any app; tap it to open a panel, speak or type a task and the
 * agent works with the screen (accessibility). While it works a glowing border runs around the screen and a small
 * pill lets you stop it. Confirmations are asked inside the overlay. The look follows the voice overlay idea of
 * the MIT-licensed droidclaw project; the code is original.
 */
public final class AssistantOverlayService extends Service {
    public static final String ACTION_STOP = "com.agentdroid.assistant.STOP";
    private static final String CHANNEL = "agentdroid_assistant";
    private static volatile boolean running;

    public static boolean isRunning() { return running; }

    public static void start(Context c) {
        Intent i = new Intent(c, AssistantOverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void stop(Context c) { c.stopService(new Intent(c, AssistantOverlayService.class)); }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView log;
    private EditText input;
    private Button mic;
    private Button send;
    private TextView pill;
    private GlowView glow;
    private LinearLayout confirm;
    private TextView confirmText;
    private SpeechRecognizer recognizer;
    private boolean listening;
    private boolean panelShown;
    private boolean pillShown;
    private boolean glowShown;
    private boolean confirmShown;

    private volatile AgentLoop loop;
    private volatile boolean busy;
    private volatile CountDownLatch confirmLatch;
    private final AtomicBoolean confirmOk = new AtomicBoolean(false);

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        startForeground(7003, notification());
        if (!canDraw()) {
            Toast.makeText(this, R.string.assistant_overlay_btn, Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (bubble == null) {
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            buildViews();
            wm.addView(bubble, bubbleLp);
            running = true;
            ApprovalCenter.setOverlay(this::askInOverlay);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        ApprovalCenter.setOverlay(null);
        AgentLoop l = loop;
        if (l != null) l.cancel();
        if (recognizer != null) { try { recognizer.destroy(); } catch (RuntimeException ignored) { } recognizer = null; }
        if (glow != null) glow.stop();
        removeQuiet(bubble); removeQuiet(panel); removeQuiet(pill); removeQuiet(glow); removeQuiet(confirm);
        bubble = null;
        super.onDestroy();
    }

    private boolean canDraw() { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this); }

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.assistant_title), NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, AssistantActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent off = PendingIntent.getService(this, 1, new Intent(this, AssistantOverlayService.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        nb.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(getString(R.string.assistant_notif))
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.assistant_notif_stop), off).build());
        return nb.build();
    }

    // ---------- views ----------

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    private WindowManager.LayoutParams lp(int w, int h, int flags, int gravity) {
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        return p;
    }

    private static final int PASSIVE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

    private void buildViews() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;

        bubble = new TextView(this);
        bubble.setText("✦");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF4285F4, 0xFF9B72CB, 0xFFD96570});
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(dp(2), 0x66FFFFFF);
        bubble.setBackground(bg);
        bubbleLp = lp(dp(52), dp(52), PASSIVE, Gravity.TOP | Gravity.START);
        bubbleLp.x = dp(8);
        bubbleLp.y = screenH / 2;
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = bubbleLp.x; startY = bubbleLp.y; downX = e.getRawX(); downY = e.getRawY(); moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float mx = e.getRawX() - downX, my = e.getRawY() - downY;
                        if (Math.abs(mx) > dp(6) || Math.abs(my) > dp(6)) moved = true;
                        if (moved) { bubbleLp.x = startX + (int) mx; bubbleLp.y = startY + (int) my; wm.updateViewLayout(bubble, bubbleLp); }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!moved) onBubbleTap();
                        return true;
                    default:
                        return true;
                }
            }
        });

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xF2161B22);
        pbg.setCornerRadius(dp(22));
        pbg.setStroke(dp(1), 0x44FFFFFF);
        panel.setBackground(pbg);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.assistant_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView fresh = smallAction(getString(R.string.assistant_new));
        fresh.setOnClickListener(v -> { if (!busy) { loop = null; log.setText(""); } });
        head.addView(fresh);
        TextView close = smallAction("✕");
        close.setOnClickListener(v -> hidePanel());
        head.addView(close);
        panel.addView(head);

        log = new TextView(this);
        log.setTextColor(0xFFE6EDF3);
        log.setTextSize(14);
        log.setMaxLines(9);
        log.setMovementMethod(new ScrollingMovementMethod());
        log.setPadding(0, dp(8), 0, dp(8));
        panel.addView(log, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint(R.string.assistant_hint);
        input.setHintTextColor(0x88FFFFFF);
        input.setTextColor(Color.WHITE);
        input.setTextSize(15);
        input.setMaxLines(3);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, ev) -> { if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); return true; } return false; });
        row.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mic = new Button(this);
        mic.setText("🎤");
        mic.setOnClickListener(v -> toggleListening());
        row.addView(mic, new LinearLayout.LayoutParams(dp(52), dp(48)));
        send = new Button(this);
        send.setText("➤");
        send.setOnClickListener(v -> submit());
        row.addView(send, new LinearLayout.LayoutParams(dp(52), dp(48)));
        panel.addView(row);

        panelLp = lp(screenW - dp(16), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        panelLp.y = dp(16);
        panelLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

        pill = new TextView(this);
        pill.setText(R.string.assistant_working);
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(13);
        pill.setPadding(dp(16), dp(10), dp(16), dp(10));
        pill.setMaxLines(2);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0xEE161B22);
        pillBg.setCornerRadius(dp(22));
        pillBg.setStroke(dp(1), 0x66FFFFFF);
        pill.setBackground(pillBg);
        pill.setOnClickListener(v -> { AgentLoop l = loop; if (l != null) l.cancel(); });

        glow = new GlowView(this);

        confirm = new LinearLayout(this);
        confirm.setOrientation(LinearLayout.VERTICAL);
        confirm.setPadding(dp(16), dp(14), dp(16), dp(12));
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(0xF51C2128);
        cbg.setCornerRadius(dp(22));
        cbg.setStroke(dp(2), 0xFFD96570);
        confirm.setBackground(cbg);
        confirmText = new TextView(this);
        confirmText.setTextColor(Color.WHITE);
        confirmText.setTextSize(15);
        confirm.addView(confirmText);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        Button deny = new Button(this);
        deny.setText(R.string.assistant_deny);
        deny.setOnClickListener(v -> answerConfirm(false));
        Button allow = new Button(this);
        allow.setText(R.string.assistant_allow);
        allow.setOnClickListener(v -> answerConfirm(true));
        buttons.addView(deny);
        buttons.addView(allow);
        confirm.addView(buttons);
    }

    private TextView smallAction(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF9BB0C3);
        t.setTextSize(13);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        return t;
    }

    private void removeQuiet(View v) {
        if (v == null || wm == null) return;
        try { wm.removeView(v); } catch (RuntimeException ignored) { }
    }

    private void onBubbleTap() {
        if (busy) { AgentLoop l = loop; if (l != null) l.cancel(); return; }
        if (panelShown) hidePanel(); else showPanel();
    }

    private void showPanel() {
        if (panelShown) return;
        wm.addView(panel, panelLp);
        panelShown = true;
        input.requestFocus();
    }

    private void hidePanel() {
        if (!panelShown) return;
        stopListening();
        removeQuiet(panel);
        panelShown = false;
    }

    private void showPill(String text) {
        pill.setText(text);
        if (pillShown) return;
        WindowManager.LayoutParams p = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, PASSIVE, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        p.y = dp(36);
        wm.addView(pill, p);
        pillShown = true;
    }

    private void hidePill() { if (pillShown) { removeQuiet(pill); pillShown = false; } }

    private void showGlow() {
        if (glowShown) return;
        WindowManager.LayoutParams p = lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                PASSIVE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, Gravity.TOP | Gravity.START);
        wm.addView(glow, p);
        glow.start();
        glowShown = true;
    }

    private void hideGlow() { if (glowShown) { glow.stop(); removeQuiet(glow); glowShown = false; } }

    // ---------- confirmations ----------

    /** Called from the agent thread: asks the user inside the overlay and waits. */
    private boolean askInOverlay(final String description, boolean force) {
        final CountDownLatch latch = new CountDownLatch(1);
        confirmOk.set(false);
        confirmLatch = latch;
        ui.post(() -> {
            if (bubble == null) { latch.countDown(); return; }
            confirmText.setText(description);
            if (!confirmShown) {
                WindowManager.LayoutParams p = lp(getResources().getDisplayMetrics().widthPixels - dp(24), WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                p.y = dp(90);
                wm.addView(confirm, p);
                confirmShown = true;
            }
        });
        try { latch.await(3, TimeUnit.MINUTES); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        ui.post(() -> { if (confirmShown) { removeQuiet(confirm); confirmShown = false; } });
        confirmLatch = null;
        return confirmOk.get();
    }

    private void answerConfirm(boolean ok) {
        confirmOk.set(ok);
        CountDownLatch l = confirmLatch;
        if (l != null) l.countDown();
    }

    // ---------- voice ----------

    private void toggleListening() {
        if (listening) { stopListening(); return; }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.assistant_mic_perm, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, AssistantActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.assistant_no_speech, Toast.LENGTH_LONG).show();
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onEvent(int eventType, Bundle params) { }
                @Override public void onError(int error) { setListening(false); }
                @Override public void onPartialResults(Bundle partial) {
                    String t = firstResult(partial);
                    if (!t.isEmpty()) { input.setText(t); input.setSelection(t.length()); }
                }
                @Override public void onResults(Bundle results) {
                    setListening(false);
                    String t = firstResult(results);
                    if (!t.isEmpty()) { input.setText(t); submit(); }
                }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        setListening(true);
        recognizer.startListening(i);
    }

    private static String firstResult(Bundle b) {
        ArrayList<String> l = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return l == null || l.isEmpty() ? "" : l.get(0);
    }

    private void stopListening() {
        if (recognizer != null && listening) { try { recognizer.stopListening(); } catch (RuntimeException ignored) { } }
        setListening(false);
    }

    private void setListening(boolean on) {
        listening = on;
        mic.setText(on ? "■" : "🎤");
    }

    // ---------- running the agent ----------

    private void say(String text) {
        log.setText(text);
        log.scrollTo(0, 0);
    }

    private void submit() {
        final String msg = input.getText().toString().trim();
        if (msg.isEmpty() || busy) return;
        AgentConfig cfg = new AgentConfig(this);
        if (cfg.apiKey().isEmpty() && !cfg.provider().equals("custom")) { say(getString(R.string.assistant_no_key)); return; }
        if (!cfg.baseUrlValid()) { say(getString(R.string.agent_bad_url)); return; }
        if (!AgentAccessibilityService.isRunning()) say(getString(R.string.assistant_no_a11y));
        input.setText("");
        stopListening();
        busy = true;
        hidePanel();
        showGlow();
        showPill(getString(R.string.assistant_working));
        final StringBuilder answer = new StringBuilder();
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                AgentLoop l = loop;
                if (l == null) {
                    BackendSelector sel = AgentBackends.create(app);
                    String pin = new AgentConfig(app).backendPin();
                    if (!pin.isEmpty()) { try { sel.pin(pin); } catch (Exception ignored) { } }
                    SelectedBackend backend = new SelectedBackend(sel);
                    if (sel.select() == null) { ui.post(() -> say(getString(R.string.assistant_no_backend))); return; }
                    l = PhoneAgent.create(app, backend, PhoneAgent.approver(app));
                    loop = l;
                }
                l.send(msg, new StreamListener() {
                    @Override public void onText(String delta) { answer.append(delta); }
                    @Override public void onToolCall(ToolCall call) {
                        answer.setLength(0);
                        final String t = "▶ " + call.name;
                        ui.post(() -> { if (pillShown) pill.setText(t); });
                    }
                    @Override public void onError(final String m) { ui.post(() -> say("⚠ " + m)); }
                });
            } catch (final Exception e) {
                ui.post(() -> say("⚠ " + e.getMessage()));
            } finally {
                final String done = answer.toString().trim();
                ui.post(() -> {
                    busy = false;
                    hideGlow();
                    hidePill();
                    if (!done.isEmpty()) say(done);
                    if (bubble != null) showPanel();
                });
            }
        }, "assistant-loop").start();
    }

    // ---------- screen glow ----------

    /** A colored light running around the screen edge while the agent works. */
    static final class GlowView extends View {
        private final Paint wide = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thin = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private final RectF rect = new RectF();
        private SweepGradient gradient;
        private ValueAnimator animator;
        private float angle;

        GlowView(Context c) {
            super(c);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            float d = c.getResources().getDisplayMetrics().density;
            wide.setStyle(Paint.Style.STROKE);
            wide.setStrokeWidth(14 * d);
            wide.setAlpha(150);
            wide.setMaskFilter(new BlurMaskFilter(18 * d, BlurMaskFilter.Blur.NORMAL));
            thin.setStyle(Paint.Style.STROKE);
            thin.setStrokeWidth(3 * d);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            gradient = new SweepGradient(w / 2f, h / 2f,
                    new int[]{0xFF4285F4, 0xFF9B72CB, 0xFFD96570, 0xFFF9AB00, 0xFF34A853, 0xFF4285F4}, null);
            wide.setShader(gradient);
            thin.setShader(gradient);
            float d = getResources().getDisplayMetrics().density;
            rect.set(4 * d, 4 * d, w - 4 * d, h - 4 * d);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (gradient == null) return;
            matrix.setRotate(angle, getWidth() / 2f, getHeight() / 2f);
            gradient.setLocalMatrix(matrix);
            float r = 28 * getResources().getDisplayMetrics().density;
            canvas.drawRoundRect(rect, r, r, wide);
            canvas.drawRoundRect(rect, r, r, thin);
        }

        void start() {
            if (animator != null) return;
            animator = ValueAnimator.ofFloat(0f, 360f);
            animator.setDuration(3200);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new android.view.animation.LinearInterpolator());
            animator.addUpdateListener(a -> { angle = (Float) a.getAnimatedValue(); invalidate(); });
            animator.start();
        }

        void stop() {
            if (animator != null) { animator.cancel(); animator = null; }
        }
    }
}
