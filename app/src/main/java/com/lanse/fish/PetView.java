package com.lanse.fish;

import android.content.Context;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import java.util.concurrent.*;

public final class PetView extends ImageView {
    private static final ExecutorService DECODER = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation;
    private boolean paused;
    public String current = "";
    public PetView(Context c) { super(c); setScaleType(ScaleType.FIT_CENTER); setContentDescription("蓝色大肥鱼动画"); }
    @Override public boolean performClick(){return super.performClick();}
    public void play(AnimationCatalog.Motion motion) {
        if (motion.id().equals(current) && getDrawable()!=null) return;
        current=motion.id(); int request=++generation;
        setContentDescription(motion.name());
        DECODER.execute(()-> {
            try {
                Drawable drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(getContext().getAssets(),motion.file()), (decoder, info, source)-> {
                    decoder.setTargetSize(info.getSize().getWidth(),info.getSize().getHeight());
                });
                main.post(()-> { if (request!=generation) return;
                    stopDrawable(); setImageDrawable(drawable);
                    if(drawable instanceof AnimatedImageDrawable a) { a.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE); if(!paused) a.start(); }
                });
            } catch(Exception e) { main.post(()-> { if(request==generation) { current=""; setContentDescription("动画加载失败："+motion.name()); } }); }
        });
    }
    public void pause(boolean value) { paused=value; if(getDrawable() instanceof AnimatedImageDrawable a) { if(value) a.stop(); else a.start(); } }
    private void stopDrawable() { if(getDrawable() instanceof AnimatedImageDrawable a) a.stop(); }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(!paused&&getDrawable() instanceof AnimatedImageDrawable a)a.start();}
    @Override protected void onDetachedFromWindow() { generation++; stopDrawable(); setImageDrawable(null); current=""; super.onDetachedFromWindow(); }
}
