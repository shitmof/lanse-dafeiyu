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
    private boolean paused;
    private Runnable endCallback;
    public String current = "";
    private final Animatable2.AnimationCallback callback = new Animatable2.AnimationCallback() {
        @Override public void onAnimationEnd(Drawable drawable) {
            Runnable run = endCallback;
            if (run != null) run.run();
        }
    };

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
        endCallback = onEnd;
        setContentDescription(motion.name());
        // Replaying the same clip (e.g. a held local work state) restarts the
        // existing drawable instead of decoding the WebP again.
        if (motion.id().equals(current) && getDrawable() instanceof AnimatedImageDrawable same) {
            same.stop();
            same.setRepeatCount(0);
            same.clearAnimationCallbacks();
            same.registerAnimationCallback(callback);
            if (!paused) same.start();
            return;
        }
        current = motion.id();
        DECODER.execute(() -> {
            try {
                Drawable drawable = ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(getContext().getAssets(), motion.file()),
                    (decoder, info, source) -> decoder.setTargetSize(info.getSize().getWidth(), info.getSize().getHeight())
                );
                main.post(() -> {
                    if (request != generation) return;
                    stopDrawable();
                    setImageDrawable(drawable);
                    if (drawable instanceof AnimatedImageDrawable a) {
                        a.setRepeatCount(0);
                        a.clearAnimationCallbacks();
                        a.registerAnimationCallback(callback);
                        if (!paused) a.start();
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (request != generation) return;
                    current = "";
                    setContentDescription("动画加载失败：" + motion.name());
                    // Notify the controller so a broken clip cannot stall rotation.
                    Runnable run = endCallback;
                    if (run != null) run.run();
                });
            }
        });
    }

    public void pause(boolean value) {
        paused = value;
        if (getDrawable() instanceof AnimatedImageDrawable a) {
            if (value) a.stop(); else a.start();
        }
    }

    private void stopDrawable() {
        if (getDrawable() instanceof AnimatedImageDrawable a) {
            a.clearAnimationCallbacks();
            a.stop();
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!paused && getDrawable() instanceof AnimatedImageDrawable a) a.start();
    }

    @Override protected void onDetachedFromWindow() {
        generation++;
        stopDrawable();
        setImageDrawable(null);
        current = "";
        super.onDetachedFromWindow();
    }
}
