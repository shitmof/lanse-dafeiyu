package com.lanse.fish;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import com.lanse.fish.AnimationCatalog.Motion;

/**
 * 0.1.1 base-experience service.
 *
 * Changes over 0.1.0:
 *  - window cropped to each animation's visible bounds (transparent canvas no
 *    longer limits dragging or intercepts touches);
 *  - one compact floating panel with direct 13-category action playback;
 *  - one-shot animation ending drives an 85-clip shuffle-bag scheduler instead
 *    of REPEAT_INFINITE + a 30-second timer;
 *  - local six-state work pose, auto interval, and no model calls for local actions.
 */
public final class PetService extends Service {
    public static final String STOP = "com.lanse.fish.STOP", REFRESH = "com.lanse.fish.REFRESH",
        PLAY = "com.lanse.fish.PLAY", SAY = "com.lanse.fish.SAY", WORK = "com.lanse.fish.WORK";
    public static volatile boolean running;
    private static final int NOTICE = 45;
    private static final float ANCHOR_X = 0.5f;   // canvas x of the body centre
    private static final float ANCHOR_Y = 0.92f;  // canvas y of the feet
    private static final int MENU_WIDTH_DP = 156;
    private static final int MENU_ROW_DP = 40;
    private enum Edge { NONE, LEFT, RIGHT }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final ArrayList<Pet> pets = new ArrayList<>();
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Map<String, Long> remoteCounters = new HashMap<>();
    private WindowManager windows;
    private Prefs prefs;
    private AnimationCatalog catalog;
    private View menu, bubble;
    private Pet menuPet;
    private String menuPage = "root", menuCategory = "";
    private boolean screenOn = true, destroyed = false, networkBusy = false;
    private int screenWidth, screenHeight;
    private int insetLeft, insetTop, insetRight, insetBottom;
    private final Rect displayBounds = new Rect();
    private Runnable screenRefreshTask;
    private DisplayManager displayManager;
    private long nextWhisper, nextRemote, nextBalance, nextPermissionCheck, lastBalanceAt;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) {}
        @Override public void onDisplayRemoved(int displayId) {}
        @Override public void onDisplayChanged(int displayId) { scheduleScreenRefresh(); }
    };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            screenOn = !Intent.ACTION_SCREEN_OFF.equals(i.getAction());
            for (Pet p : pets) { p.cancelDrag(); p.pause(!screenOn); }
            if (!screenOn) { hideMenu(); hideBubble(); }
        }
    };

    public static void command(Context c, String action, String value) {
        Intent i = new Intent(c, PetService.class).setAction(action).putExtra("value", value);
        if (STOP.equals(action)) { c.stopService(i); new Prefs(c).put("enabled", false); return; }
        if (!Settings.canDrawOverlays(c)) return;
        c.startForegroundService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel(this);
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTICE, n);
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return; }
        try { catalog = new AnimationCatalog(this); }
        catch (Exception e) { prefs.put("last_error", "动画目录读取失败，请重新安装完整 APK"); stopSelf(); return; }
        measureScreen();
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (displayManager != null) displayManager.registerDisplayListener(displayListener, handler);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF); f.addAction(Intent.ACTION_SCREEN_ON); f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, f);
        screenOn = ((PowerManager) getSystemService(POWER_SERVICE)).isInteractive();
        running = true; prefs.put("enabled", true); prefs.put("last_error", "");
        rebuild();
        long now = SystemClock.uptimeMillis();
        nextWhisper = now + 60_000;
        nextRemote = now + 5_000;
        nextBalance = now + 1_800_000;
        handler.post(tick);
    }

    public static void createNotificationChannel(Context c) {
        NotificationChannel channel = new NotificationChannel("pet", "桌宠陪伴", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("悬浮宠物运行状态与关闭入口");
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, PetService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "pet").setSmallIcon(R.drawable.ic_fish).setContentTitle("蓝色大肥鱼正在陪伴你")
            .setContentText("长按宠物打开菜单 · 点此返回小窝").setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "停止陪伴", stop).build()).build();
    }

    private void measureScreen() {
        int oldWidth = screenWidth, oldHeight = screenHeight;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                WindowMetrics metrics = windows.getCurrentWindowMetrics();
                Rect bounds = metrics.getBounds();
                displayBounds.set(bounds);
                screenWidth = Math.max(1, bounds.width());
                screenHeight = Math.max(1, bounds.height());
                WindowInsets insets = metrics.getWindowInsets();
                Insets bars = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                insetLeft = bars.left; insetTop = bars.top;
                insetRight = bars.right; insetBottom = bars.bottom;
            } catch (RuntimeException fallback) {
                fallbackMeasureScreen();
            }
        } else {
            fallbackMeasureScreen();
        }
        edgeLog("screen changed=" + (oldWidth != screenWidth || oldHeight != screenHeight)
            + " size=" + screenWidth + "x" + screenHeight
            + " bounds=" + displayBounds + " insets=" + insetLeft + "," + insetTop + "," + insetRight + "," + insetBottom);
    }

    private void fallbackMeasureScreen() {
        Point p = new Point();
        windows.getDefaultDisplay().getSize(p);
        screenWidth = Math.max(1, p.x);
        screenHeight = Math.max(1, p.y);
        displayBounds.set(0, 0, screenWidth, screenHeight);
        insetLeft = insetTop = insetRight = insetBottom = 0;
    }

    private boolean debugBuild() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void edgeLog(String message) {
        if (debugBuild() && prefs != null && prefs.flag("debug_log", false)) {
            Log.d("FishEdge", message);
        }
    }

    private void scheduleScreenRefresh() {
        if (destroyed) return;
        if (screenRefreshTask != null) handler.removeCallbacks(screenRefreshTask);
        screenRefreshTask = () -> {
            screenRefreshTask = null;
            int oldWidth = screenWidth, oldHeight = screenHeight;
            measureScreen();
            if (oldWidth != screenWidth || oldHeight != screenHeight) {
                for (Pet p : pets) p.save();
                rebuild(false);
            }
        };
        handler.postDelayed(screenRefreshTask, 250);
    }

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private WindowManager.LayoutParams params(int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        return lp;
    }

    private void rebuild() { rebuild(true); }

    private void rebuild(boolean save) {
        if (menuPet != null) menuPet.menuOpen = false;
        menuPet = null;
        hideMenu(); hideBubble();
        for (Pet p : pets) { if (save) p.save(); remove(p.view); }
        pets.clear();
        int count = Math.max(1, Math.min(3, prefs.number("count", 1)));
        for (int i = 0; i < count; i++) {
            Pet p = new Pet(i);
            pets.add(p);
            try {
                windows.addView(p.view, p.lp);
                p.start();
                p.view.pause(!screenOn);
            } catch (RuntimeException e) {
                prefs.put("last_error", "悬浮窗无法显示，请检查悬浮窗授权");
                stopSelf(); return;
            }
        }
    }

    private void remove(View v) { if (v != null) try { windows.removeViewImmediate(v); } catch (IllegalArgumentException ignored) {} }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { prefs.put("enabled", false); stopSelf(); return START_NOT_STICKY; }
        if (catalog == null || !running) return START_NOT_STICKY;
        if (intent != null) {
            String a = intent.getAction(), value = intent.getStringExtra("value");
            if (REFRESH.equals(a)) { rebuild(); remoteCounters.clear(); }
            if (PLAY.equals(a) && value != null && !pets.isEmpty()) pets.get(0).playManual(catalog.find(value));
            if (SAY.equals(a) && value != null && !pets.isEmpty()) say(pets.get(0), value);
            if (WORK.equals(a) && value != null && !pets.isEmpty()) {
                if ("auto".equals(value)) pets.get(0).clearWork();
                else pets.get(0).setWork(value, true);
            }
        }
        return START_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (destroyed || !running) return;
            long now = SystemClock.uptimeMillis();
            if (now >= nextPermissionCheck) {
                nextPermissionCheck = now + 1000;
                if (!Settings.canDrawOverlays(PetService.this)) { prefs.put("enabled", false); stopSelf(); return; }
            }
            if (screenOn) {
                for (Pet p : pets) p.step();
                if (now >= nextWhisper) {
                    if (prefs.flag("whisper", true) && !pets.isEmpty() && !pets.get(0).dragging && !pets.get(0).menuOpen) whisper(pets.get(0));
                    nextWhisper = now + Math.max(1, prefs.number("whisper_minutes", 5)) * 60_000L;
                }
                if (now >= nextRemote) { if (prefs.flag("bridge", false)) pollRemote(); nextRemote = now + 5000; }
                if (now >= nextBalance) { if (prefs.flag("balance_auto", false) && !pets.isEmpty() && !pets.get(0).dragging && !pets.get(0).menuOpen && !prefs.text("mode", "offline").equals("offline")) balance(pets.get(0)); nextBalance = now + 1_800_000; }
            }
            handler.postDelayed(this, screenOn ? 40 : 1000);
        }
    };

    private final class Pet {
        final int index;
        final PetView view;
        WindowManager.LayoutParams lp;
        boolean dragging, moved, longPressed, menuOpen, manualPlay, workHold;
        float downX, downY, startX, startY, vx, vy;
        long lastChange;
        static final int VEL_SAMPLES = 6;
        final float[] velX = new float[VEL_SAMPLES], velY = new float[VEL_SAMPLES];
        final long[] velT = new long[VEL_SAMPLES];
        int velCount;
        WindowManager.LayoutParams dragLp;
        boolean dragExpanded;
        float dragBaseX, dragBaseY;
        Runnable dragSafety;
        AnimationCatalog.Motion motion;
        final int canvasW, canvasH, pad, edgeEps;
        final ArrayDeque<Motion> bag = new ArrayDeque<>();
        final ArrayDeque<String> recent = new ArrayDeque<>();
        String workState;
        Edge edge = Edge.NONE;
        Runnable autoTask;
        final Runnable hold = () -> { if (!moved) { longPressed = true; menu(this); } };

        Pet(int i) {
            index = i;
            view = new PetView(PetService.this);
            canvasW = Math.max(dp(80), Math.min(screenWidth, dp(prefs.number("size", 260))));
            canvasH = Math.round(canvasW * 360f / 640f);
            pad = Math.max(1, dp(2));
            edgeEps = Math.max(1, dp(1));
            motion = catalog.match("吃白饭");
            int[] win = windowSize(motion);
            lp = params(win[0], win[1]);
            float ax, ay;
            int oldSize = Math.max(dp(80), Math.min(screenWidth, dp(prefs.number("size", 260))));
            int oldH = Math.round(oldSize * 360f / 640f);
            int oldLeft = Math.round((screenWidth - oldSize) * prefs.number("x" + i, i == 0 ? 800 : 200) / 1000f);
            int oldTop = Math.round(Math.max(0, screenHeight - oldH) * prefs.number("y" + i, 600 + i * 100) / 1000f);
            ax = oldLeft + oldSize * 0.5f;
            ay = oldTop + oldH * 0.9f;
            int savedAx = prefs.number("ax" + i, Integer.MIN_VALUE);
            if (savedAx != Integer.MIN_VALUE) ax = savedAx / 1000f * screenWidth;
            int savedAy = prefs.number("ay" + i, Integer.MIN_VALUE);
            if (savedAy != Integer.MIN_VALUE) ay = savedAy / 1000f * screenHeight;
            lp.x = Math.round(ax - (ANCHOR_X - motion.x0()) * canvasW);
            lp.y = Math.round(ay - (ANCHOR_Y - motion.y0()) * canvasH);
            clamp();
            try { edge = Edge.valueOf(prefs.text("edge" + i, Edge.NONE.name())); }
            catch (IllegalArgumentException ignored) { edge = Edge.NONE; }
            if (edge != Edge.NONE) snapToEdge();
            clamp();
            refreshEdge();
            view.setAlpha(prefs.number("opacity", 100) / 100f);
            view.setCrop(motion, canvasW, canvasH, pad);
            view.setOnClickListener(v -> tap());
            view.setOnTouchListener((v, e) -> touch(e));
            logPet("init");
        }

        int[] windowSize(AnimationCatalog.Motion m) {
            int w = Math.max(dp(48), Math.round((m.x1() - m.x0()) * canvasW) + pad * 2);
            int h = Math.max(dp(48), Math.round((m.y1() - m.y0()) * canvasH) + pad * 2);
            return new int[]{Math.min(w, screenWidth), Math.min(h, screenHeight)};
        }

        int maxX() { return Math.max(0, screenWidth - lp.width); }

        void snapToEdge() {
            if (edge == Edge.LEFT) lp.x = 0;
            else if (edge == Edge.RIGHT) lp.x = maxX();
        }

        void refreshEdge() {
            int max = maxX();
            if (max <= 0) return; // a full-width clip covers both edges; keep the previous intent
            if (lp.x <= edgeEps) edge = Edge.LEFT;
            else if (lp.x >= max - edgeEps) edge = Edge.RIGHT;
            else edge = Edge.NONE;
        }

        void logPet(String event) {
            if (!debugBuild() || !prefs.flag("debug_log", false)) return;
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            Motion m = motion;
            Log.d("FishEdge", "pet=" + index + " ev=" + event
                + " motion=" + (m == null ? "-" : m.id() + "/" + m.name())
                + " cat=" + (m == null ? "-" : m.category())
                + " visual=" + (m == null ? "-" : m.x0() + "," + m.y0() + "," + m.x1() + "," + m.y1())
                + " screen=" + screenWidth + "x" + screenHeight
                + " bounds=" + displayBounds + " insets=" + insetLeft + "," + insetTop + "," + insetRight + "," + insetBottom
                + " lp=" + lp.x + "," + lp.y + "," + lp.width + "x" + lp.height
                + " view=" + loc[0] + "," + loc[1] + "," + view.getWidth() + "x" + view.getHeight()
                + " edge=" + edge + " vx=" + vx + " vy=" + vy
                + " anchor=" + anchorX() + "," + anchorY());
        }

        float anchorX() {
            if (motion == null) return lp.x + lp.width * 0.5f;
            return lp.x + (ANCHOR_X - motion.x0()) * canvasW;
        }

        float anchorY() {
            if (motion == null) return lp.y + lp.height * 0.9f;
            return lp.y + (ANCHOR_Y - motion.y0()) * canvasH;
        }

        void start() {
            if (prefs.flag("auto_play", true)) playNext(true);
            else play(catalog.match("待机"), false, true);
        }

        void cancelAuto() {
            if (autoTask != null) { handler.removeCallbacks(autoTask); autoTask = null; }
        }

        void play(AnimationCatalog.Motion m, boolean manual, boolean initial) {
            if (m == null) return;
            if (dragging) return;
            cancelAuto();
            Edge beforeEdge = edge;
            float ax = anchorX(), ay = anchorY();
            int[] win = windowSize(m);
            lp.width = win[0]; lp.height = win[1];
            if (beforeEdge == Edge.LEFT) lp.x = 0;
            else if (beforeEdge == Edge.RIGHT) lp.x = Math.max(0, screenWidth - lp.width);
            else lp.x = Math.round(ax - (ANCHOR_X - m.x0()) * canvasW);
            lp.y = Math.round(ay - (ANCHOR_Y - m.y0()) * canvasH);
            clamp();
            refreshEdge();
            if (edge != Edge.NONE) snapToEdge();
            clamp();
            update();
            view.setCrop(m, canvasW, canvasH, pad);
            motion = m;
            manualPlay = manual;
            lastChange = SystemClock.uptimeMillis();
            if ("移动".equals(m.category()) && prefs.flag("wander", true)) {
                // A pet the user deliberately parked on an edge must not walk away
                // just because the next random clip is a locomotion clip. Walking is
                // enabled again as soon as the user drags it in from the edge.
                if (edge == Edge.NONE) {
                    vx = random.nextBoolean() ? 2.2f : -2.2f;
                } else {
                    vx = 0;
                }
                vy = 0;
                view.setScaleX(vx == 0 ? 1 : (vx < 0 ? 1 : -1));
            } else {
                vx = vy = 0;
                view.setScaleX(1);
            }
            final int generation = ++playGeneration;
            view.play(m, () -> onEnd(generation));
            logPet("play " + m.name() + " beforeEdge=" + beforeEdge);
        }

        void onEnd(int generation) {
            if (destroyed || generation != playGeneration) return;
            if (dragging) return;
            if (workHold && workState != null) {
                handler.postDelayed(() -> { if (!destroyed && workHold) play(catalog.workMotion(workState), true, false); }, 250);
                return;
            }
            if (manualPlay) { manualPlay = false; if (prefs.flag("auto_play", true)) scheduleNext(); return; }
            if (prefs.flag("auto_play", true)) scheduleNext();
            else vx = vy = 0;
        }

        int playGeneration;

        void scheduleNext() {
            cancelAuto();
            if (destroyed || !screenOn || workHold || menuOpen || dragging || !prefs.flag("auto_play", true)) return;
            long interval = Math.max(1, prefs.number("auto_interval", 15)) * 1000L;
            long wait = Math.max(0, lastChange + interval - SystemClock.uptimeMillis());
            autoTask = () -> {
                autoTask = null;
                if (!destroyed && screenOn && !manualPlay && !workHold && !menuOpen && !dragging && prefs.flag("auto_play", true)) playNext(false);
            };
            handler.postDelayed(autoTask, wait);
        }

        void playNext(boolean initial) {
            AnimationCatalog.Motion m = catalog.nextAuto(random, recent, motion == null ? null : motion.id(), bag);
            if (m == null) return;
            recent.addLast(m.id());
            while (recent.size() > 6) recent.removeFirst();
            play(m, false, initial);
        }

        void playNextManual() {
            AnimationCatalog.Motion m = catalog.nextAuto(random, recent, motion == null ? null : motion.id(), bag);
            if (m == null) return;
            recent.addLast(m.id());
            while (recent.size() > 6) recent.removeFirst();
            playManual(m);
        }

        void playCategory(String category) {
            AnimationCatalog.Motion m = catalog.nextInCategory(category, random, motion == null ? null : motion.id());
            if (m != null) playManual(m);
        }

        void playManual(AnimationCatalog.Motion m) {
            if (m == null) return;
            workHold = false; workState = null;
            play(m, true, false);
        }

        void setWork(String state, boolean hold) {
            workState = state; workHold = hold;
            cancelAuto();
            AnimationCatalog.Motion m = catalog.workMotion(state);
            if (m != null) play(m, true, false);
        }

        void clearWork() {
            workHold = false; workState = null;
            if (prefs.flag("auto_play", true)) playNextManual();
        }

        void pause(boolean value) {
            view.pause(value);
            if (value) cancelAuto();
            else if (!manualPlay && !workHold) scheduleNext();
        }

        void tap() {
            AnimationCatalog.Motion m = catalog.nextClick(random, motion == null ? null : motion.id());
            if (m != null) playManual(m);
            say(this, new String[]{
                "饭可以再添一碗吗？", "我在这里陪着你呀。", "摸摸头，就继续加油！", "今天也有好好吃饭吗？"
            }[random.nextInt(4)]);
        }

        void save() {
            prefs.put("ax" + index, Math.round(anchorX() * 1000f / Math.max(1, screenWidth)));
            prefs.put("ay" + index, Math.round(anchorY() * 1000f / Math.max(1, screenHeight)));
            prefs.put("edge" + index, edge.name());
        }

        void resetVelocity() { velCount = 0; }

        void addVelocitySample(float rawX, float rawY, long eventTime) {
            if (velCount == VEL_SAMPLES) {
                System.arraycopy(velX, 1, velX, 0, VEL_SAMPLES - 1);
                System.arraycopy(velY, 1, velY, 0, VEL_SAMPLES - 1);
                System.arraycopy(velT, 1, velT, 0, VEL_SAMPLES - 1);
                velCount--;
            }
            velX[velCount] = rawX;
            velY[velCount] = rawY;
            velT[velCount] = eventTime;
            velCount++;
        }

        /**
         * VelocityTracker computes in view-local coordinates. During a drag the pet
         * itself is translated under the finger, so local dx can even have the
         * opposite sign from the finger's screen movement. Use raw screen samples.
         */
        void computeRawVelocity() {
            vx = vy = 0;
            if (velCount < 2) return;
            int last = velCount - 1;
            int first = last;
            while (first > 0 && velT[last] - velT[first - 1] <= 140) first--;
            float dt = (velT[last] - velT[first]) / 1000f;
            if (dt <= 0.001f) return;
            vx = Math.max(-45, Math.min(45, ((velX[last] - velX[first]) / dt) / 25f));
            vy = Math.max(-45, Math.min(45, ((velY[last] - velY[first]) / dt) / 25f));
        }

        /**
         * While a finger is down, temporarily make the window full-screen and move
         * only the view content. A small cropped window follows the finger one frame
         * late; near an edge the pointer can leave that small window and Android
         * cancels the drag, which is the intermittent "stops short of the edge" bug.
         * The full-screen carrier window keeps the pointer captured for the whole
         * gesture. It is restored to the cropped size on UP/CANCEL.
         */
        void expandForDrag() {
            if (prefs.flag("fixed", false)) return;
            dragBaseX = lp.x;
            dragBaseY = lp.y;
            try {
                dragLp = params(screenWidth, screenHeight);
                dragLp.x = 0;
                dragLp.y = 0;
                dragLp.width = screenWidth;
                dragLp.height = screenHeight;
                windows.updateViewLayout(view, dragLp);
                dragExpanded = true;
                view.setTranslationX(dragBaseX);
                view.setTranslationY(dragBaseY);
                if (dragSafety != null) handler.removeCallbacks(dragSafety);
                dragSafety = () -> {
                    dragSafety = null;
                    if (dragExpanded) {
                        dragging = false;
                        restoreAfterDrag(dragBaseX, dragBaseY);
                    }
                };
                handler.postDelayed(dragSafety, 15_000);
                logPet("drag expand base=" + dragBaseX + "," + dragBaseY);
            } catch (RuntimeException e) {
                dragExpanded = false;
                dragLp = null;
                logPet("drag expand failed=" + e.getClass().getSimpleName());
            }
        }

        void restoreAfterDrag(float finalX, float finalY) {
            if (!dragExpanded) return;
            if (dragSafety != null) { handler.removeCallbacks(dragSafety); dragSafety = null; }
            int[] win = windowSize(motion);
            view.setTranslationX(0);
            view.setTranslationY(0);
            lp.width = win[0];
            lp.height = win[1];
            lp.x = Math.round(finalX);
            lp.y = Math.round(finalY);
            clamp();
            refreshEdge();
            update();
            dragExpanded = false;
            dragLp = null;
            logPet("drag restore final=" + lp.x + "," + lp.y + " edge=" + edge);
        }

        void cancelDrag() {
            if (dragSafety != null) { handler.removeCallbacks(dragSafety); dragSafety = null; }
            if (dragExpanded) {
                dragging = false;
                restoreAfterDrag(dragBaseX, dragBaseY);
            }
        }

        boolean touch(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    hideMenu(); hideBubble(); cancelAuto();
                    vx = vy = 0; dragging = true; moved = longPressed = false;
                    downX = e.getRawX(); downY = e.getRawY();
                    startX = lp.x; startY = lp.y;
                    resetVelocity();
                    addVelocitySample(e.getRawX(), e.getRawY(), e.getEventTime());
                    handler.postDelayed(hold, 550);
                    logPet("down raw=" + e.getRawX() + "," + e.getRawY());
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    addVelocitySample(e.getRawX(), e.getRawY(), e.getEventTime());
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!moved && Math.hypot(dx, dy) > dp(8)) { moved = true; handler.removeCallbacks(hold); hideMenu(); }
                    if (moved && !prefs.flag("fixed", false)) {
                        if (!dragExpanded) expandForDrag();
                        if (dragExpanded) {
                            view.setTranslationX(dragBaseX + dx);
                            view.setTranslationY(dragBaseY + dy);
                        } else {
                            Edge previousEdge = edge;
                            lp.x = Math.round(startX + dx); lp.y = Math.round(startY + dy);
                            clamp(); refreshEdge(); update();
                            if (previousEdge != edge) logPet("drag edge=" + edge + " raw=" + e.getRawX() + "," + e.getRawY());
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(hold); dragging = false;
                    addVelocitySample(e.getRawX(), e.getRawY(), e.getEventTime());
                    float finalX = startX + (e.getRawX() - downX);
                    float finalY = startY + (e.getRawY() - downY);
                    if (!moved) { finalX = startX; finalY = startY; }
                    if (dragExpanded) {
                        restoreAfterDrag(finalX, finalY);
                    } else if (moved && !prefs.flag("fixed", false)) {
                        // Fallback when the full-screen drag carrier could not be installed.
                        lp.x = Math.round(finalX); lp.y = Math.round(finalY);
                        clamp(); refreshEdge(); update();
                    }
                    refreshEdge();
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        if (moved) {
                            if (prefs.flag("physics", true) && !prefs.flag("fixed", false)) {
                                computeRawVelocity();
                            }
                            // A fling that points out of the display must not bounce the pet back inward.
                            if (edge == Edge.LEFT && vx < 0) vx = 0;
                            if (edge == Edge.RIGHT && vx > 0) vx = 0;
                            save();
                            manualPlay = false;
                            if (prefs.flag("auto_play", true)) scheduleNext();
                            else vx = vy = 0;
                        } else if (!longPressed) view.performClick();
                        logPet("up moved=" + moved + " raw=" + e.getRawX() + "," + e.getRawY() + " edge=" + edge + " velocity=" + vx + "," + vy);
                    } else {
                        if (moved) save();
                        logPet("cancel raw=" + e.getRawX() + "," + e.getRawY() + " edge=" + edge);
                    }
                    return true;
                }
            }
            return true;
        }

        void clamp() {
            lp.x = Math.max(0, Math.min(Math.max(0, screenWidth - lp.width), lp.x));
            lp.y = Math.max(0, Math.min(Math.max(0, screenHeight - lp.height), lp.y));
        }

        void update() { if (view.isAttachedToWindow()) try { windows.updateViewLayout(view, lp); } catch (RuntimeException e) { stopSelf(); } }

        void step() {
            if (dragging || prefs.flag("fixed", false)) return;
            if (Math.abs(vx) + Math.abs(vy) < 0.12f) { vx = vy = 0; return; }
            Edge previousEdge = edge;
            int max = maxX();
            int maxY = Math.max(0, screenHeight - lp.height);
            boolean hit = false;
            lp.x += Math.round(vx); lp.y += Math.round(vy);
            if (lp.x < 0) { lp.x = 0; if (vx < 0) vx = 0; hit = true; }
            else if (lp.x > max) { lp.x = max; if (vx > 0) vx = 0; hit = true; }
            if (lp.y < 0) { lp.y = 0; if (vy < 0) vy = 0; hit = true; }
            else if (lp.y > maxY) { lp.y = maxY; if (vy > 0) vy = 0; hit = true; }
            refreshEdge();
            update();
            if (hit || previousEdge != edge) logPet("step hit=" + hit + " edge=" + edge);
            if (Math.abs(vx) + Math.abs(vy) < 0.25f) { vx = vy = 0; save(); }
        }
    }

    // ---------------- compact floating menu ----------------

    private GradientDrawable background(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(12));
        return d;
    }

    private TextView menuRow(String label, Runnable action) {
        TextView t = new TextView(this);
        t.setText(label); t.setTextSize(13); t.setTextColor(0xFF163D4B);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(8), 0, dp(8), 0);
        t.setMinHeight(dp(MENU_ROW_DP));
        t.setSingleLine(false);
        t.setMaxLines(2);
        t.setOnClickListener(v -> action.run());
        return t;
    }

    private void menu(Pet pet) {
        hideMenu();
        menuPet = pet;
        pet.menuOpen = true;
        pet.cancelAuto();
        pet.vx = pet.vy = 0;
        menuPage = "root";
        menuCategory = "";
        renderMenu(pet);
    }

    private void renderMenu(Pet pet) {
        if (menu != null) remove(menu);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(6), dp(6), dp(6));
        box.setBackground(background(0xFFF6F8F5));
        box.setElevation(dp(8));
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int rows = 0;
        if (menuPage.equals("root")) {
            list.addView(menuRow("换个动作", () -> { hideMenu(); pet.playNextManual(); })); rows++;
            list.addView(menuRow("动作分类", () -> { menuPage = "categories"; renderMenu(pet); })); rows++;
            list.addView(menuRow("工作状态", () -> { menuPage = "work"; renderMenu(pet); })); rows++;
            list.addView(menuRow("自动播放", () -> { menuPage = "auto"; renderMenu(pet); })); rows++;
            list.addView(menuRow("更多", () -> { menuPage = "more"; renderMenu(pet); })); rows++;
        } else if (menuPage.equals("categories")) {
            list.addView(menuRow("‹ 返回", () -> { menuPage = "root"; renderMenu(pet); })); rows++;
            for (String cat : catalog.categories()) {
                int count = catalog.byCategory(cat).size();
                list.addView(menuRow(cat + "  " + count, () -> { menuCategory = cat; menuPage = "actions"; renderMenu(pet); }));
                rows++;
            }
        } else if (menuPage.equals("actions")) {
            list.addView(menuRow("‹ " + menuCategory, () -> { menuPage = "categories"; renderMenu(pet); })); rows++;
            for (AnimationCatalog.Motion m : catalog.byCategory(menuCategory)) {
                list.addView(menuRow(m.name(), () -> { hideMenu(); pet.playManual(m); }));
                rows++;
            }
        } else if (menuPage.equals("work")) {
            list.addView(menuRow("‹ 返回", () -> { menuPage = "root"; renderMenu(pet); })); rows++;
            String[] labels = {"思考", "工作", "整理", "等待", "成功", "出错"};
            String[] states = {"thinking", "working", "result", "waiting", "success", "error"};
            for (int i = 0; i < labels.length; i++) {
                final String state = states[i];
                list.addView(menuRow(labels[i] + (pet.workHold && state.equals(pet.workState) ? " · 持续中" : ""), () -> {
                    boolean same = pet.workHold && state.equals(pet.workState);
                    hideMenu();
                    if (same) pet.clearWork(); else pet.setWork(state, true);
                    say(pet, "本地陪伴状态 · " + labels[Arrays.asList(states).indexOf(state)]);
                }));
                rows++;
            }
            list.addView(menuRow("恢复自动", () -> { hideMenu(); pet.clearWork(); })); rows++;
        } else if (menuPage.equals("auto")) {
            list.addView(menuRow("‹ 返回", () -> { menuPage = "root"; renderMenu(pet); })); rows++;
            final boolean on = prefs.flag("auto_play", true);
            list.addView(menuRow("自动播放：" + (on ? "开" : "关"), () -> {
                prefs.put("auto_play", !on);
                if (on) pet.cancelAuto(); else pet.scheduleNext();
                renderMenu(pet);
            })); rows++;
            final int[] steps = {5, 10, 15, 30, 60, 120, 300};
            final int found = Arrays.binarySearch(steps, prefs.number("auto_interval", 15));
            final int idx = found >= 0 ? found : 2;
            list.addView(menuRow("切换间隔：" + steps[idx] + " 秒", () -> {
                int next = steps[(idx + 1) % steps.length];
                prefs.put("auto_interval", next);
                renderMenu(pet);
            })); rows++;
            list.addView(menuRow("立即换一个", () -> { hideMenu(); pet.playNextManual(); })); rows++;
        } else { // more
            list.addView(menuRow("‹ 返回", () -> { menuPage = "root"; renderMenu(pet); })); rows++;
            list.addView(menuRow("和我聊聊", () -> { hideMenu(); open("chat"); })); rows++;
            list.addView(menuRow("吃一碗白饭", () -> { hideMenu(); pet.playManual(catalog.match("吃白饭")); })); rows++;
            list.addView(menuRow("说句话", () -> { hideMenu(); whisper(pet); })); rows++;
            list.addView(menuRow("查余额", () -> { hideMenu(); balance(pet); })); rows++;
            final boolean quiet = !prefs.flag("whisper", true);
            list.addView(menuRow(quiet ? "开启碎碎念" : "安静 / 关闭碎碎念", () -> {
                prefs.put("whisper", quiet);
                hideMenu();
                say(pet, quiet ? "那我偶尔说一句。" : "好，我安静陪着你。");
            })); rows++;
            list.addView(menuRow("回到小窝", () -> { hideMenu(); open("home"); })); rows++;
            list.addView(menuRow("停止陪伴", () -> {
                hideMenu();
                prefs.put("enabled", false);
                stopSelf();
            })); rows++;
        }
        sv.addView(list, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        int rowH = dp(MENU_ROW_DP);
        int maxH = Math.min(dp(288), Math.round(screenHeight * 0.36f));
        int listH = Math.min(maxH - dp(12), rows * rowH + dp(4));
        int width = dp(MENU_WIDTH_DP);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(width - dp(12), Math.max(rowH, listH));
        box.addView(sv, svLp);
        int height = Math.max(rowH, listH) + dp(12);
        WindowManager.LayoutParams lp = params(width, height);
        lp.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        int x = pet.lp.x + pet.lp.width / 2 - width / 2;
        lp.x = Math.max(0, Math.min(Math.max(0, screenWidth - width), x));
        int above = pet.lp.y - height - dp(8);
        lp.y = above >= 0 ? above : Math.min(Math.max(0, screenHeight - height), pet.lp.y + pet.lp.height + dp(8));
        box.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) { hideMenu(); return true; }
            return false;
        });
        menu = box;
        try { windows.addView(box, lp); }
        catch (RuntimeException ignored) { menu = null; }
    }

    private void hideMenu() {
        if (menu != null) remove(menu);
        menu = null;
        if (menuPet != null) {
            menuPet.menuOpen = false;
            if (!menuPet.workHold && !menuPet.manualPlay && prefs.flag("auto_play", true)) menuPet.scheduleNext();
            menuPet = null;
        }
        menuPage = "root"; menuCategory = "";
    }

    private void hideBubble() { remove(bubble); bubble = null; }

    private void say(Pet pet, String text) {
        if (destroyed || !screenOn) return;
        hideBubble();
        String plain = text.replaceAll("\\[表情:[^\\]]+\\]", "");
        TextView b = new TextView(this);
        b.setText(plain.length() > 180 ? plain.substring(0, 180) + "…" : plain);
        b.setTextSize(14); b.setTextColor(0xFF163D4B);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setBackground(background(0xFFF6F8F5)); b.setElevation(dp(6));
        int width = Math.min(dp(240), screenWidth);
        WindowManager.LayoutParams lp = params(width, WindowManager.LayoutParams.WRAP_CONTENT);
        lp.x = Math.max(0, Math.min(screenWidth - width, pet.lp.x));
        lp.y = Math.max(0, Math.min(screenHeight - dp(180), pet.lp.y - dp(90)));
        b.setOnClickListener(v -> hideBubble());
        bubble = b;
        try { windows.addView(b, lp); } catch (RuntimeException ignored) { bubble = null; }
        handler.postDelayed(() -> { if (bubble == b) hideBubble(); }, 10_000);
    }

    private void whisper(Pet p) {
        p.playManual(catalog.match("碎碎念-"));
        if (prefs.flag("ai_whisper", false) && !prefs.text("mode", "offline").equals("offline")) {
            if (networkBusy) return;
            networkBusy = true;
            network.execute(() -> {
                String text;
                try { text = new PetApi(PetService.this).whisper(); }
                catch (Exception e) { text = "联网碎碎念暂时不可用，先安静陪你。"; }
                String result = text;
                handler.post(() -> { networkBusy = false; say(p, result); });
            });
        } else {
            say(p, new String[]{"饭香里藏着今天的小确幸。", "记得眨眨眼，喝口水。", "不着急，一件一件来。", "这碗白饭，真香。", "你忙你的，我在呢。", "忙完以后，伸个懒腰吧。"}[random.nextInt(6)]);
        }
    }

    private void balance(Pet p) {
        if (networkBusy) return;
        long now = SystemClock.uptimeMillis();
        if (lastBalanceAt != 0 && now - lastBalanceAt < 30_000) {
            say(p, "刚刚查过啦，30 秒后再试，避免频繁请求。");
            return;
        }
        networkBusy = true;
        say(p, "我去看看钱包……");
        network.execute(() -> {
            String result; boolean ok;
            try { result = new PetApi(PetService.this).balance(); ok = true; }
            catch (Exception e) { result = e instanceof java.io.IOException ? e.getMessage() : "余额查询失败，请检查服务配置。"; ok = false; }
            String text = result; boolean success = ok;
            handler.post(() -> {
                networkBusy = false;
                if (!destroyed) {
                    lastBalanceAt = SystemClock.uptimeMillis();
                    say(p, text);
                    if (success) balanceMotion(p);
                }
            });
        });
    }

    private void balanceMotion(Pet p) {
        try {
            JSONArray pool = catalog.config.optJSONObject("animations").optJSONObject("events").optJSONArray("balance");
            if (pool != null) p.playManual(catalog.find(pool.optString(prefs.number("balance_motion", 2))));
        } catch (Exception ignored) {}
    }

    private void pollRemote() {
        if (networkBusy || prefs.text("dsh_url", "").isEmpty()) return;
        networkBusy = true;
        network.execute(() -> {
            try {
                JSONObject state = new PetApi(PetService.this).state();
                handler.post(() -> { networkBusy = false; if (!destroyed) { prefs.put("bridge_status", "已连接 DSH"); applyRemote(state); } });
            } catch (Exception e) {
                handler.post(() -> { networkBusy = false; prefs.put("bridge_status", "DSH 未连接，请检查地址和网络"); });
            }
        });
    }

    private boolean changed(String name, JSONObject leaf) {
        if (leaf == null) return false;
        long c = leaf.optLong("counter", 0);
        Long old = remoteCounters.put(name, c);
        return old != null && old != c;
    }

    private void applyRemote(JSONObject state) {
        if (pets.isEmpty()) return;
        Pet pet = pets.get(0);
        JSONObject all = state.optJSONObject("pets"), one = all == null ? null : all.optJSONObject(prefs.text("dsh_pet", "main"));
        if (one != null) {
            JSONObject sayLeaf = one.optJSONObject("say"), anim = one.optJSONObject("anim");
            if (changed("say", sayLeaf)) {
                JSONObject d = sayLeaf.optJSONObject("data");
                if (d != null) {
                    if (d.optBoolean("clear")) hideBubble();
                    else if (!d.optString("text").isEmpty()) say(pet, d.optString("text"));
                }
            }
            if (changed("anim", anim)) {
                JSONObject d = anim.optJSONObject("data");
                if (d != null) {
                    String name = d.optString("name", d.optString("file", ""));
                    for (AnimationCatalog.Motion m : catalog.motions) {
                        if (name.contains(m.name())) { pet.playManual(m); break; }
                    }
                }
            }
        }
        JSONObject sections = state.optJSONObject("sections");
        if (sections != null) {
            JSONObject work = sections.optJSONObject("workStatus");
            if (changed("work", work)) {
                JSONObject d = work.optJSONObject("data");
                if (d != null) {
                    String s = d.optString("state");
                    AnimationCatalog.Motion m = catalog.workMotion(s);
                    if (m != null) pet.playManual(m);
                    int index = Arrays.asList("thinking", "working", "result", "waiting", "success", "error").indexOf(s);
                    JSONArray texts = catalog.config.optJSONArray("workStatusTexts");
                    if (index >= 0 && texts != null) {
                        JSONArray options = texts.optJSONArray(index);
                        if (options != null && options.length() > 0) say(pet, options.optString(random.nextInt(options.length())));
                    }
                }
            }
            JSONObject balance = sections.optJSONObject("balance");
            if (changed("balance", balance)) {
                JSONObject raw = balance.optJSONObject("data");
                if (raw != null) {
                    try { say(pet, new PetApi(this).formatBalance(raw)); balanceMotion(pet); }
                    catch (java.io.IOException ignored) {}
                }
            }
        }
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        for (Pet p : pets) { p.cancelDrag(); p.save(); }
        edgeLog("configuration changed " + c.orientation + " " + c.screenWidthDp + "x" + c.screenHeightDp);
        scheduleScreenRefresh();
    }

    @Override public void onDestroy() {
        destroyed = true; running = false;
        handler.removeCallbacksAndMessages(null);
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(displayListener); } catch (RuntimeException ignored) {}
        }
        network.shutdownNow();
        hideMenu(); hideBubble();
        for (Pet p : pets) { p.cancelAuto(); p.save(); remove(p.view); }
        pets.clear();
        try { unregisterReceiver(screenReceiver); } catch (IllegalArgumentException ignored) {}
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void open(String page) {
        startActivity(new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("page", page));
    }
}
