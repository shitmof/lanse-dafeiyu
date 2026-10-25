package com.lanse.fish;

import android.content.Context;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;

/**
 * One-shot animation surface.
 *
 * The old view set REPEAT_INFINITE and let the service overwrite the clip every
 * 30 seconds. That made "one clip ends" impossible to observe, so rotation was
 * effectively random and repetitive. This view plays a clip exactly once and
 * reports its real end; the pet owns what comes next.
 */
public final class PetView extends ImageView {
    private static final ExecutorService DECODER = Executors.newFixedThreadPool(2);
    private static final int CANVAS_W = 480;
    private static final int CANVAS_H = 270;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation;
    private boolean paused, completed;
    private Future<?> pendingDecode;
    private Runnable endCallback;
    private String loaded = "";
    private CompletionObserver observer;
    public String current = "";

    private void finish(int request) {
        if (request != generation || completed) return;
        completed = true;
        Runnable run = endCallback;
        endCallback = null;
        if (run != null) run.run();
    }

    // Android can already have posted an end event when stop/detach happens.
    // Clearing its callback list then crashes inside the framework's queued
    // runnable. Keep one weak observer per drawable; invalidate our ownership.
    private static final class CompletionObserver extends Animatable2.AnimationCallback {
        final WeakReference<PetView> owner;
        int request;
        CompletionObserver(PetView view, int request) {
            owner = new WeakReference<>(view); this.request = request;
        }
        @Override public void onAnimationEnd(Drawable ended) {
            PetView view = owner.get();
            if (view != null && view.observer == this && ended == view.getDrawable()
                    && !view.paused && !((AnimatedImageDrawable) ended).isRunning())
                view.finish(request);
        }
    }

    private void observe(AnimatedImageDrawable drawable, int request) {
        observer = new CompletionObserver(this, request);
        drawable.registerAnimationCallback(observer);
    }

    public PetView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);
        setContentDescription("蓝色大肥鱼动画");
    }

    @Override public boolean performClick() { return super.performClick(); }

    /**
     * Crop the full 480x270 canvas into a small window around this clip's visible
     * bounds. The character keeps its original scale; only transparent margins are
     * removed, so the body can reach the screen edge and the touch area stays tight.
     */
    public void setCrop(AnimationCatalog.Motion motion, int canvasW, int canvasH, int padPx) {
        if (motion == null) return;
        Matrix matrix = new Matrix();
        float sx = canvasW / (float) CANVAS_W;
        float sy = canvasH / (float) CANVAS_H;
        matrix.setScale(sx, sy);
        matrix.postTranslate(-motion.x0() * canvasW + padPx, -motion.y0() * canvasH + padPx);
        setImageMatrix(matrix);
    }

    public void play(AnimationCatalog.Motion motion) { play(motion, null); }

    public void play(AnimationCatalog.Motion motion, Runnable onEnd) {
        if (motion == null) return;
        final int request = ++generation;
        if (pendingDecode != null) pendingDecode.cancel(false);
        completed = false;
        endCallback = onEnd;
        setContentDescription(motion.name());
        current = motion.id();
        // Replaying the same clip (e.g. a held local work state) restarts the
        // existing drawable instead of decoding the WebP again.
        if (observer != null && motion.id().equals(loaded) && getDrawable() instanceof AnimatedImageDrawable same) {
            same.stop();
            same.setRepeatCount(0);
            observer.request = request;
            if (!paused) same.start();
            return;
        }
        stopDrawable();
        pendingDecode = DECODER.submit(() -> {
            try {
                Drawable drawable = ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(getContext().getAssets(), motion.file()),
                    (decoder, info, source) -> decoder.setTargetSize(info.getSize().getWidth(), info.getSize().getHeight())
                );
                main.post(() -> {
                    if (request != generation) return;
                    stopDrawable();
                    setImageDrawable(drawable);
                    loaded = motion.id();
                    if (drawable instanceof AnimatedImageDrawable a) {
                        a.setRepeatCount(0);
                        observe(a, request);
                        if (!paused) a.start();
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (request != generation) return;
                    current = "";
                    setContentDescription("动画加载失败：" + motion.name());
                    // Notify the controller so a broken clip cannot stall rotation.
                    finish(request);
                });
            }
        });
    }

    public void pause(boolean value) {
        paused = value;
        if (getDrawable() instanceof AnimatedImageDrawable a) {
            if (value) a.stop(); else if (!completed) a.start();
        }
    }

    private void stopDrawable() {
        observer = null;
        if (getDrawable() instanceof AnimatedImageDrawable a) {
            a.stop();
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!paused && !completed && getDrawable() instanceof AnimatedImageDrawable a) a.start();
    }

    @Override protected void onDetachedFromWindow() {
        generation++;
        if (pendingDecode != null) pendingDecode.cancel(false);
        endCallback = null;
        stopDrawable();
        setImageDrawable(null);
        current = "";
        loaded = "";
        super.onDetachedFromWindow();
    }
}
