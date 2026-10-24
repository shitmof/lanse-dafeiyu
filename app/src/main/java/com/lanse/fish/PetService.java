package com.lanse.fish;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class PetService extends Service {
    public static final String STOP="com.lanse.fish.STOP", REFRESH="com.lanse.fish.REFRESH", PLAY="com.lanse.fish.PLAY", SAY="com.lanse.fish.SAY";
    public static volatile boolean running;
    private static final int NOTICE=45;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Random random=new Random();
    private final ArrayList<Pet> pets=new ArrayList<>();
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private WindowManager windows;
    private Prefs prefs;
    private AnimationCatalog catalog;
    private View menu, bubble;
    private boolean screenOn=true, destroyed=false, networkBusy=false;
    private int screenWidth,screenHeight;
    private long nextMotion,nextWhisper,nextRoam,nextRemote,nextBalance,nextPermissionCheck;
    private final Map<String,Long> remoteCounters=new HashMap<>();
    private final BroadcastReceiver screenReceiver=new BroadcastReceiver() {
        @Override public void onReceive(Context c,Intent i) {
            screenOn=!Intent.ACTION_SCREEN_OFF.equals(i.getAction());
            for(Pet p:pets) p.view.pause(!screenOn);
            if(!screenOn){hideMenu();hideBubble();}
        }
    };
    public static void command(Context c,String action,String value) {
        Intent i=new Intent(c,PetService.class).setAction(action).putExtra("value",value);
        if(STOP.equals(action)) { c.stopService(i); new Prefs(c).put("enabled",false); return; }
        if(!Settings.canDrawOverlays(c)) return;
        c.startForegroundService(i);
    }
    @Override public void onCreate() {
        super.onCreate(); prefs=new Prefs(this); windows=(WindowManager)getSystemService(WINDOW_SERVICE);
        createNotificationChannel(this);
        Notification n=notification();
        if(Build.VERSION.SDK_INT>=34) startForeground(NOTICE,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); else startForeground(NOTICE,n);
        if(!Settings.canDrawOverlays(this)){stopSelf();return;}
        try { catalog=new AnimationCatalog(this); } catch(Exception e){ prefs.put("last_error","动画目录读取失败，请重新安装完整 APK");stopSelf();return; }
        measureScreen();
        IntentFilter f=new IntentFilter(); f.addAction(Intent.ACTION_SCREEN_OFF); f.addAction(Intent.ACTION_SCREEN_ON); f.addAction(Intent.ACTION_USER_PRESENT);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(screenReceiver,f,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(screenReceiver,f);
        screenOn=((PowerManager)getSystemService(POWER_SERVICE)).isInteractive();
        running=true; prefs.put("enabled",true);prefs.put("last_error","");
        rebuild(); nextMotion=SystemClock.uptimeMillis()+30000; nextWhisper=SystemClock.uptimeMillis()+60000;nextRoam=SystemClock.uptimeMillis()+15000;nextBalance=SystemClock.uptimeMillis()+30000;
        handler.post(tick);
    }
    public static void createNotificationChannel(Context c) {
        NotificationChannel channel=new NotificationChannel("pet","桌宠陪伴",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("悬浮宠物运行状态与关闭入口");
        ((NotificationManager)c.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }
    private Notification notification() {
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,2,new Intent(this,PetService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"pet").setSmallIcon(R.drawable.ic_fish).setContentTitle("蓝色大肥鱼正在陪伴你")
            .setContentText("长按宠物打开菜单 · 点此返回小窝").setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null,"停止陪伴",stop).build()).build();
    }
    private void measureScreen(){ Point p=new Point(); windows.getDefaultDisplay().getSize(p);screenWidth=p.x;screenHeight=p.y; }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private WindowManager.LayoutParams params(int w,int h){
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.LEFT; return lp;
    }
    private void rebuild(){
        rebuild(true);
    }
    private void rebuild(boolean save){
        hideMenu(); hideBubble();
        for(Pet p:pets){if(save)p.save();remove(p.view);} pets.clear();
        int count=Math.max(1,Math.min(3,prefs.number("count",1)));
        for(int i=0;i<count;i++){
            Pet p=new Pet(i); pets.add(p);
            try{windows.addView(p.view,p.lp);p.play(catalog.match("吃白饭"));p.view.pause(!screenOn);}catch(RuntimeException e){prefs.put("last_error","悬浮窗无法显示，请检查悬浮窗授权");stopSelf();return;}
        }
    }
    private void remove(View v){ if(v!=null) try{ windows.removeViewImmediate(v); }catch(IllegalArgumentException ignored){} }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null && STOP.equals(intent.getAction())){prefs.put("enabled",false);stopSelf();return START_NOT_STICKY;}
        if(catalog==null || !running) return START_NOT_STICKY;
        if(intent!=null){
            String a=intent.getAction(), value=intent.getStringExtra("value");
            if(REFRESH.equals(a)) { rebuild();remoteCounters.clear(); }
            if(PLAY.equals(a)&&value!=null){for(Pet p:pets){p.play(catalog.find(value));p.manualUntil=SystemClock.uptimeMillis()+30000;} }
            if(SAY.equals(a)&&value!=null&&!pets.isEmpty()) say(pets.get(0),value);
        }
        return START_STICKY;
    }
    private final Runnable tick=new Runnable(){@Override public void run(){
        if(destroyed||!running)return;
        long now=SystemClock.uptimeMillis();
        if(now>=nextPermissionCheck){nextPermissionCheck=now+1000;if(!Settings.canDrawOverlays(PetService.this)){prefs.put("enabled",false);stopSelf();return;}}
        if(screenOn){
            for(Pet p:pets) p.step();
            if(now>=nextMotion){
                int hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                for(Pet p:pets) if(!p.dragging&&now>p.manualUntil) p.play(prefs.flag("schedule",true)?catalog.scheduled(hour,random):catalog.random(random));
                nextMotion=now+30000;
            }
            if(now>=nextRoam){
                if(prefs.flag("wander",true)&&!(prefs.flag("schedule",true)&&(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)<6||Calendar.getInstance().get(Calendar.HOUR_OF_DAY)>=23)))
                    for(Pet p:pets) if(!p.dragging&&now>p.manualUntil){p.vx=random.nextBoolean()?2:-2;p.walkUntil=now+4500;p.play(catalog.match("左转奔跑"));}
                nextRoam=now+30000+random.nextInt(20000);
            }
            if(now>=nextWhisper){ if(prefs.flag("whisper",true)&&!pets.isEmpty()) whisper(pets.get(0)); nextWhisper=now+Math.max(1,prefs.number("whisper_minutes",5))*60000L; }
            if(now>=nextRemote){if(prefs.flag("bridge",false)) pollRemote();nextRemote=now+5000;}
            if(now>=nextBalance){if(prefs.flag("balance_auto",false)&&!pets.isEmpty()&&!prefs.text("mode","offline").equals("offline"))balance(pets.get(0));nextBalance=now+1800000;}
        }
        handler.postDelayed(this,screenOn?40:1000);
    }};
    private final class Pet {
        final int index; final PetView view; final WindowManager.LayoutParams lp;
        boolean dragging,moved,longPressed; float downX,downY,startX,startY,lastX,lastY,vx,vy; long lastMove,manualUntil,walkUntil;
        VelocityTracker velocity;
        final Runnable hold=()->{if(!moved){longPressed=true;menu(this);}};
        Pet(int i){
            index=i;view=new PetView(PetService.this); int width=Math.min(screenWidth,dp(prefs.number("size",260)));
            lp=params(width,Math.round(width*360f/640));
            lp.x=Math.round((screenWidth-width)*prefs.number("x"+i,i==0?800:200)/1000f);
            lp.y=Math.round(Math.max(0,screenHeight-lp.height)*prefs.number("y"+i,600+i*100)/1000f);
            view.setAlpha(prefs.number("opacity",100)/100f);
            view.setOnClickListener(v->tap());view.setOnLongClickListener(v->{menu(this);return true;});
            view.setOnTouchListener((v,e)->touch(e));
        }
        void tap(){String[] hit={"开心跃动","害羞惊讶","元气挥手","挠痒"};play(catalog.match(hit[random.nextInt(hit.length)]));say(this,new String[]{"饭可以再添一碗吗？","我在这里陪着你呀。","摸摸头，就继续加油！","今天也有好好吃饭吗？"}[random.nextInt(4)]);}
        void play(AnimationCatalog.Motion m){if(m.category().equals("文字"))view.setScaleX(1);view.play(m);}
        void save(){ prefs.put("x"+index,Math.round(lp.x*1000f/Math.max(1,screenWidth-lp.width)));prefs.put("y"+index,Math.round(lp.y*1000f/Math.max(1,screenHeight-lp.height))); }
        boolean touch(MotionEvent e){
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN -> { hideMenu();hideBubble();vx=vy=0;walkUntil=0;dragging=true;moved=longPressed=false;downX=e.getRawX();downY=e.getRawY();startX=lp.x;startY=lp.y;manualUntil=SystemClock.uptimeMillis()+30000;velocity=VelocityTracker.obtain();velocity.addMovement(e);handler.postDelayed(hold,550);return true; }
                case MotionEvent.ACTION_MOVE -> { if(velocity!=null)velocity.addMovement(e);float dx=e.getRawX()-downX,dy=e.getRawY()-downY;
                    if(!moved&&Math.hypot(dx,dy)>dp(8)){moved=true;handler.removeCallbacks(hold);hideMenu();play(catalog.match("拖拽"));}
                    if(moved&&!prefs.flag("fixed",false)){lp.x=Math.round(startX+dx);lp.y=Math.round(startY+dy);clamp();update();} return true;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(hold);dragging=false;
                    if(e.getActionMasked()==MotionEvent.ACTION_UP){
                        if(moved){
                            if(velocity!=null&&prefs.flag("physics",true)&&!prefs.flag("fixed",false)){velocity.computeCurrentVelocity(1000);vx=Math.max(-45,Math.min(45,velocity.getXVelocity()/25));vy=Math.max(-45,Math.min(45,velocity.getYVelocity()/25));}
                            play(catalog.match("漂浮踏步")); save();
                        } else if(!longPressed){view.performClick();}
                    }
                    if(velocity!=null){velocity.recycle();velocity=null;}return true;
                }
            }return true;
        }
        void clamp(){lp.x=Math.max(0,Math.min(Math.max(0,screenWidth-lp.width),lp.x));lp.y=Math.max(0,Math.min(Math.max(0,screenHeight-lp.height),lp.y));}
        void update(){if(view.isAttachedToWindow())try{windows.updateViewLayout(view,lp);}catch(RuntimeException e){stopSelf();}}
        void step(){
            if(dragging||prefs.flag("fixed",false))return;
            if(Math.abs(vx)+Math.abs(vy)<0.12f){vx=vy=0;return;}
            lp.x+=Math.round(vx);lp.y+=Math.round(vy);
            if(lp.x<0||lp.x>screenWidth-lp.width)vx=-vx*.65f;
            if(lp.y<0||lp.y>screenHeight-lp.height)vy=-vy*.65f;
            clamp();update();
            if(SystemClock.uptimeMillis()>walkUntil){vx*=.88f;vy*=.88f;} else view.setScaleX(vx<0?1:-1);
            if(Math.abs(vx)+Math.abs(vy)<.25f)save();
        }
    }
    private GradientDrawable background(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(20));return d;}
    private void menu(Pet pet){
        hideMenu(); LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(12),dp(8),dp(12),dp(8));box.setBackground(background(0xFFF6F8F5));box.setElevation(dp(10));
        String[] labels={"和我聊聊","挑一个动作","吃一碗白饭","说句话","查余额","安静 / 碎碎念","回到小窝","停止陪伴"};
        for(int i=0;i<labels.length;i++){final int action=i;TextView row=new TextView(this);row.setText(labels[i]);row.setTextColor(0xFF163D4B);row.setTextSize(16);row.setPadding(dp(12),dp(10),dp(12),dp(10));box.addView(row);row.setOnClickListener(v->{hideMenu();switch(action){
            case 0->open("chat");case 1->open("motions");case 2->{pet.play(catalog.match("吃白饭"));pet.manualUntil=SystemClock.uptimeMillis()+60000;}
            case 3->whisper(pet);case 4->balance(pet);case 5->{prefs.put("whisper",!prefs.flag("whisper",true));say(pet,prefs.flag("whisper",true)?"那我偶尔说一句。":"好，我安静陪着你。");}
            case 6->open("home");case 7->{prefs.put("enabled",false);stopSelf();}
        }});}
        WindowManager.LayoutParams lp=params(dp(200),WindowManager.LayoutParams.WRAP_CONTENT);lp.x=Math.max(0,Math.min(screenWidth-lp.width,pet.lp.x));lp.y=Math.max(0,Math.min(screenHeight-dp(400),pet.lp.y-dp(300)));
        lp.flags|=WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        box.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_OUTSIDE){hideMenu();return true;}return false;});
        menu=box;try{windows.addView(box,lp);}catch(RuntimeException ignored){menu=null;}
        handler.postDelayed(()->{if(menu==box)hideMenu();},15000);
    }
    private void open(String page){startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("page",page));}
    private void hideMenu(){remove(menu);menu=null;}
    private void hideBubble(){remove(bubble);bubble=null;}
    private void say(Pet pet,String text){
        if(destroyed||!screenOn)return;hideBubble();
        String plain=text.replaceAll("\\[表情:[^\\]]+\\]","");
        TextView b=new TextView(this); b.setText(plain.length()>180?plain.substring(0,180)+"…":plain);b.setTextSize(14);b.setTextColor(0xFF163D4B);b.setPadding(dp(14),dp(10),dp(14),dp(10));b.setBackground(background(0xFFF6F8F5));b.setElevation(dp(6));
        WindowManager.LayoutParams lp=params(Math.min(dp(240),screenWidth),WindowManager.LayoutParams.WRAP_CONTENT); lp.x=Math.max(0,Math.min(screenWidth-lp.width,pet.lp.x));lp.y=Math.max(0,Math.min(screenHeight-dp(180),pet.lp.y-dp(90)));
        b.setOnClickListener(v->hideBubble());bubble=b;try{windows.addView(b,lp);}catch(RuntimeException ignored){bubble=null;}
        handler.postDelayed(()->{if(bubble==b)hideBubble();},10000);
    }
    private void whisper(Pet p){
        p.play(catalog.match("碎碎念-"));
        if(prefs.flag("ai_whisper",false)&&!prefs.text("mode","offline").equals("offline")){
            if(networkBusy)return;networkBusy=true;
            network.execute(()->{String text;try{text=new PetApi(PetService.this).whisper();}catch(Exception e){text="联网碎碎念暂时不可用，先安静陪你。";}String result=text;handler.post(()->{networkBusy=false;say(p,result);});});
        }else say(p,new String[]{"饭香里藏着今天的小确幸。","记得眨眨眼，喝口水。","不着急，一件一件来。","这碗白饭，真香。","你忙你的，我在呢。","忙完以后，伸个懒腰吧。"}[random.nextInt(6)]);
    }
    private void balance(Pet p){
        if(networkBusy)return;networkBusy=true;say(p,"我去看看钱包……");
        network.execute(()->{String result;boolean ok;try{result=new PetApi(PetService.this).balance();ok=true;}catch(Exception e){result=e instanceof java.io.IOException?e.getMessage():"余额查询失败，请检查服务配置。";ok=false;}String text=result;boolean success=ok;handler.post(()->{networkBusy=false;if(!destroyed){say(p,text);if(success)balanceMotion(p);}});});
    }
    private void balanceMotion(Pet p){JSONArray pool=catalog.config.optJSONObject("animations").optJSONObject("events").optJSONArray("balance");if(pool!=null)p.play(catalog.find(pool.optString(prefs.number("balance_motion",2))));}
    private void pollRemote(){
        if(networkBusy||prefs.text("dsh_url","").isEmpty())return;networkBusy=true;
        network.execute(()->{
            try{JSONObject state=new PetApi(PetService.this).state();handler.post(()->{networkBusy=false;if(destroyed)return; prefs.put("bridge_status","已连接 DSH");applyRemote(state);});}
            catch(Exception e){handler.post(()->{networkBusy=false;prefs.put("bridge_status","DSH 未连接，请检查地址和网络");});}
        });
    }
    private boolean changed(String name,JSONObject leaf){if(leaf==null)return false;long c=leaf.optLong("counter",0);Long old=remoteCounters.put(name,c);return old!=null&&old!=c;}
    private void applyRemote(JSONObject state){
        if(pets.isEmpty())return;Pet pet=pets.get(0);
        JSONObject all=state.optJSONObject("pets"), one=all==null?null:all.optJSONObject(prefs.text("dsh_pet","main"));
        if(one!=null){
            JSONObject say=one.optJSONObject("say"),anim=one.optJSONObject("anim");
            if(changed("say",say)){JSONObject d=say.optJSONObject("data");if(d!=null){if(d.optBoolean("clear"))hideBubble();else if(!d.optString("text").isEmpty())say(pet,d.optString("text"));}}
            if(changed("anim",anim)){JSONObject d=anim.optJSONObject("data");if(d!=null){String name=d.optString("name",d.optString("file",""));for(AnimationCatalog.Motion m:catalog.motions)if(name.contains(m.name())){pet.play(m);pet.manualUntil=SystemClock.uptimeMillis()+15000;break;}}}
        }
        JSONObject sections=state.optJSONObject("sections");if(sections!=null){JSONObject work=sections.optJSONObject("workStatus");if(changed("work",work)){JSONObject d=work.optJSONObject("data");if(d!=null){String s=d.optString("state");String motion=switch(s){case "thinking"->"思考冒泡";case "working"->"忙碌";case "result"->"清点归档";case "waiting"->"踱步";case "success"->"庆祝";case "error"->"叹气";default->"待机";};pet.play(catalog.match(motion));pet.manualUntil=SystemClock.uptimeMillis()+15000;
                int index=Arrays.asList("thinking","working","result","waiting","success","error").indexOf(s);JSONArray texts=catalog.config.optJSONArray("workStatusTexts");if(index>=0&&texts!=null){JSONArray options=texts.optJSONArray(index);if(options!=null&&options.length()>0)say(pet,options.optString(random.nextInt(options.length())));}
            }}JSONObject balance=sections.optJSONObject("balance");if(changed("balance",balance)){JSONObject raw=balance.optJSONObject("data");if(raw!=null)try{say(pet,new PetApi(this).formatBalance(raw));balanceMotion(pet);}catch(java.io.IOException ignored){}}}
    }
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);for(Pet p:pets)p.save();measureScreen();rebuild(false);}
    @Override public void onDestroy(){
        destroyed=true;running=false;handler.removeCallbacksAndMessages(null);network.shutdownNow();hideMenu();hideBubble();
        for(Pet p:pets){p.save();remove(p.view);}pets.clear();try{unregisterReceiver(screenReceiver);}catch(IllegalArgumentException ignored){}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
