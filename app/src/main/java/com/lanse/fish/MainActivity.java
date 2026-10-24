package com.lanse.fish;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public final class MainActivity extends Activity {
    private static final int INK=0xFF153E4C, TEAL=0xFF176B85, MUTED=0xFF667E85, PAPER=0xFFF6F8F5, SKY=0xFFDDEDF0;
    private Prefs prefs;
    private AnimationCatalog catalog;
    private LinearLayout root,content,nav;
    private String page="home",chatDraft="";
    private PetView preview;
    private boolean pendingStart,sending;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ArrayList<PetView> previews=new ArrayList<>();
    private LinearLayout messages;
    private ScrollView chatScroll;
    private EditText composer;
    private Button send;
    private Typeface displayFont;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);prefs=new Prefs(this);
        displayFont=Typeface.create("serif",Typeface.BOLD);
        try{catalog=new AnimationCatalog(this);}catch(Exception e){TextView error=new TextView(this);error.setText("动画资源不完整，请安装完整版本 APK。");setContentView(error);return;}
        if(state!=null){page=state.getString("page","home");pendingStart=state.getBoolean("pendingStart",false);chatDraft=state.getString("draft","");}
        else if(getIntent().hasExtra("page"))page=getIntent().getStringExtra("page");
        render();
    }
    @Override protected void onSaveInstanceState(Bundle s){super.onSaveInstanceState(s);s.putString("page",page);s.putBoolean("pendingStart",pendingStart);s.putString("draft",composer==null?chatDraft:composer.getText().toString());}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);page=i.getStringExtra("page");if(page==null)page="home";render();}
    @Override protected void onResume(){super.onResume();for(PetView p:previews)p.pause(false);if(catalog==null)return;
        if(pendingStart){pendingStart=false;if(Settings.canDrawOverlays(this))startPet();}
        else if(page.equals("home"))render();
    }
    @Override protected void onPause(){for(PetView p:previews)p.pause(true);super.onPause();}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String value,int sp,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setLineSpacing(dp(3),1);return t;}
    private void gap(LinearLayout parent,int h){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    private void title(LinearLayout parent,String eyebrow,String heading,String detail){
        TextView e=text(eyebrow,11,TEAL);e.setLetterSpacing(.13f);parent.addView(e);gap(parent,8);
        TextView h=text(heading,30,INK);h.setTypeface(displayFont);parent.addView(h);if(!detail.isEmpty()){gap(parent,8);parent.addView(text(detail,14,MUTED));}gap(parent,22);
    }
    private Button button(String label,boolean primary,Runnable action){
        Button b=new Button(this);b.setText(label);b.setTextSize(15);b.setAllCaps(false);b.setTextColor(primary?Color.WHITE:TEAL);b.setBackground(bg(primary?TEAL:SKY,18));b.setMinHeight(dp(50));b.setPadding(dp(16),dp(8),dp(16),dp(8));b.setElevation(0);b.setOnClickListener(v->action.run());return b;
    }
    private void fullButton(LinearLayout parent,String label,boolean primary,Runnable action){Button b=button(label,primary,action);parent.addView(b,new LinearLayout.LayoutParams(-1,dp(52)));}
    private LinearLayout card(LinearLayout parent){LinearLayout c=column();c.setPadding(dp(18),dp(18),dp(18),dp(18));c.setBackground(bg(Color.WHITE,22));parent.addView(c,new LinearLayout.LayoutParams(-1,-2));return c;}
    private void section(LinearLayout p,String title){gap(p,20);TextView t=text(title,19,INK);t.setTypeface(null,Typeface.BOLD);p.addView(t);gap(p,12);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void render(){
        if(catalog==null||isDestroyed())return;
        if(composer!=null){chatDraft=composer.getText().toString();composer=null;}previews.clear();preview=null;
        root=column();root.setBackgroundColor(PAPER);
        root.setOnApplyWindowInsetsListener((v,insets)->{int bottom=insets.getSystemWindowInsetBottom();if(Build.VERSION.SDK_INT>=30)bottom=Math.max(insets.getInsets(WindowInsets.Type.systemBars()).bottom,insets.getInsets(WindowInsets.Type.ime()).bottom);root.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),bottom);return insets;});
        setContentView(root);
        content=column();root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        if(page.equals("chat"))chat();else{
            ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content.addView(scroll,new LinearLayout.LayoutParams(-1,-1));LinearLayout body=column();body.setPadding(dp(22),dp(26),dp(22),dp(28));scroll.addView(body);
            switch(page){case "motions"->motions(body);case "settings"->settings(body);default->home(body);}
        }
        nav=row();nav.setPadding(dp(10),dp(8),dp(10),dp(8));nav.setBackgroundColor(Color.WHITE);
        String[] ids={"home","motions","chat","settings"},labels={"小窝","动作","聊天","设置"};
        for(int i=0;i<ids.length;i++){String id=ids[i];TextView item=text((page.equals(id)?"●  ":"")+labels[i],14,page.equals(id)?TEAL:MUTED);item.setGravity(Gravity.CENTER);item.setPadding(0,dp(13),0,dp(13));item.setBackground(bg(page.equals(id)?SKY:Color.WHITE,16));nav.addView(item,new LinearLayout.LayoutParams(0,dp(48),1));item.setOnClickListener(v->{page=id;render();});}
        root.addView(nav,new LinearLayout.LayoutParams(-1,-2));root.requestApplyInsets();
    }
    private PetView pet(LinearLayout parent,int height,String motion){PetView p=new PetView(this);parent.addView(p,new LinearLayout.LayoutParams(-1,dp(height)));previews.add(p);p.post(()->p.play(catalog.match(motion)));return p;}
    private void home(LinearLayout p){
        title(p,"A LITTLE COMPANY · 01","蓝色大肥鱼","一碗白饭，陪你慢慢过今天。");
        LinearLayout hero=column();hero.setBackground(bg(SKY,30));hero.setPadding(dp(16),dp(18),dp(16),dp(18));p.addView(hero);
        LinearLayout badges=row();TextView badge=text(PetService.running?"● 正在陪伴":"○ 在小窝等你",12,TEAL);badges.addView(badge,new LinearLayout.LayoutParams(0,-2,1));TextView count=text(catalog.motions.size()+" 个原版动作",12,MUTED);badges.addView(count);
        hero.addView(badges);preview=pet(hero,235,"吃白饭");
        TextView quote=text("「再添一碗，就一碗。」",18,INK);quote.setGravity(Gravity.CENTER);hero.addView(quote);gap(hero,10);
        TextView touch=text("点点我，看看今天的小心情",12,MUTED);touch.setGravity(Gravity.CENTER);hero.addView(touch);
        preview.setOnClickListener(v->preview.play(catalog.random(new Random())));
        gap(p,20);
        fullButton(p,PetService.running?"正在陪伴 · 停止":"开始陪伴  ↗",true,()->{if(PetService.running){PetService.command(this,PetService.STOP,"");p.postDelayed(this::render,200);}else requestStart();});
        gap(p,12);TextView hint=text("离开小窝，它也会留在屏幕上。\n拖动换位置，长按打开动作与聊天菜单。",13,MUTED);hint.setGravity(Gravity.CENTER);p.addView(hint);
        section(p,"陪伴准备");LinearLayout checks=card(p);
        checkLine(checks,"悬浮窗",Settings.canDrawOverlays(this)?"已允许":"点击授权",()->openOverlay());
        checkLine(checks,"通知",notificationAllowed()?"已允许":"点击允许",this::askNotification);
        checkLine(checks,"后台运行","查看电池设置",this::batterySettings);
        gap(p,18);String error=prefs.text("last_error","");if(!error.isEmpty())p.addView(text(error,13,0xFF945537));
        p.addView(text("系统安全页面可能暂时隐藏悬浮窗。熄屏时暂停动画，亮屏后继续陪伴。",12,MUTED));
    }
    private void checkLine(LinearLayout p,String label,String value,Runnable action){LinearLayout r=row();TextView a=text(label,14,INK);r.addView(a,new LinearLayout.LayoutParams(0,dp(43),1));a.setGravity(Gravity.CENTER_VERTICAL);TextView b=text(value+"  ›",13,TEAL);r.addView(b);r.setOnClickListener(v->action.run());p.addView(r);}
    private boolean notificationAllowed(){return Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;}
    private void askNotification(){if(Build.VERSION.SDK_INT>=33&&!notificationAllowed())requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},9);else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));}
    private void openOverlay(){startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}
    private void requestStart(){if(!Settings.canDrawOverlays(this)){pendingStart=true;openOverlay();return;}startPet();}
    private void startPet(){
        try{PetService.command(this,PetService.REFRESH,"");if(Build.VERSION.SDK_INT>=33&&!notificationAllowed())requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},9);root.postDelayed(this::render,300);}
        catch(RuntimeException e){toast("暂时无法启动，请检查悬浮窗权限后重试。");}
    }
    @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] results){super.onRequestPermissionsResult(req,permissions,results);if(page.equals("home"))render();}
    private void batterySettings(){try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(ActivityNotFoundException e){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}}
    private void motions(LinearLayout p){
        title(p,"MOTION LIBRARY · 02","今天，做点什么","原版全部 "+catalog.motions.size()+" 个动作，已装进小小的口袋。");
        LinearLayout stage=column();stage.setBackground(bg(SKY,26));p.addView(stage);preview=pet(stage,190,"待机");
        TextView selected=text("待机呼吸休闲",14,TEAL);selected.setGravity(Gravity.CENTER);stage.addView(selected);gap(stage,12);gap(p,16);
        EditText search=field(p,"搜索动作","吃饭、舞蹈、节日……","",false);
        LinkedHashSet<String> categories=new LinkedHashSet<>();categories.add("全部动作");for(AnimationCatalog.Motion m:catalog.motions)categories.add(m.category());
        Spinner filter=spinner(p,categories.toArray(new String[0]),0);LinearLayout list=column();p.addView(list);
        Runnable fill=()->{list.removeAllViews();String query=search.getText().toString().trim(),cat=filter.getSelectedItem().toString();
            for(AnimationCatalog.Motion m:catalog.motions){if(!m.name().contains(query)||(!cat.equals("全部动作")&&!cat.equals(m.category())))continue;
                LinearLayout r=row();r.setPadding(dp(4),dp(10),dp(4),dp(10));LinearLayout labels=column();labels.addView(text(m.name(),15,INK));labels.addView(text(m.category(),11,MUTED));r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
                Button b=button("播放",false,()->{preview.play(m);selected.setText(m.name());if(PetService.running)PetService.command(this,PetService.PLAY,m.id());});r.addView(b,new LinearLayout.LayoutParams(dp(76),dp(46)));list.addView(r);
                View line=new View(this);line.setBackgroundColor(0xFFE3EAE8);list.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
            }
            if(list.getChildCount()==0)list.addView(text("没有找到这个动作，试试别的词。",14,MUTED));
        };
        search.addTextChangedListener(watcher(fill));filter.setOnItemSelectedListener(selection(fill));fill.run();
    }
    private TextWatcher watcher(Runnable r){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){r.run();}public void afterTextChanged(Editable e){}};}
    private AdapterView.OnItemSelectedListener selection(Runnable r){return new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> a,View v,int pos,long id){r.run();}public void onNothingSelected(AdapterView<?> a){}};}
    private void chat(){
        LinearLayout heading=column();heading.setPadding(dp(22),dp(24),dp(22),0);content.addView(heading);title(heading,"LITTLE TALKS · 03","把今天说给我听",prefs.text("mode","offline").equals("offline")?"离线陪伴 · 固定回应，连接 AI 后可自由聊天":"已选联网对话 · 消息将发送至你配置的服务");
        chatScroll=new ScrollView(this);chatScroll.setFillViewport(true);messages=column();messages.setPadding(dp(20),0,dp(20),dp(12));chatScroll.addView(messages);content.addView(chatScroll,new LinearLayout.LayoutParams(-1,0,1));
        JSONArray history=new PetApi(this).history();
        if(history.length()==0){LinearLayout welcome=card(messages);pet(welcome,150,"元气挥手");welcome.addView(text("我在呢。吃饭、发呆，或者随便聊聊，都可以。",16,INK));}
        else for(int i=0;i<history.length();i++){JSONObject m=history.optJSONObject(i);if(m!=null)chatBubble(m.optString("content"),m.optString("role").equals("user"));}
        LinearLayout input=row();input.setPadding(dp(16),dp(10),dp(16),dp(10));composer=new EditText(this);composer.setHint("说点什么……");composer.setText(chatDraft);composer.setTextSize(15);composer.setTextColor(INK);composer.setMaxLines(4);composer.setBackground(bg(Color.WHITE,18));composer.setPadding(dp(14),dp(12),dp(14),dp(12));composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.addView(composer,new LinearLayout.LayoutParams(0,-2,1));
        send=button(sending?"等待…":"发送",true,this::sendChat);send.setEnabled(!sending);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(76),dp(50));bp.leftMargin=dp(8);input.addView(send,bp);content.addView(input);chatScroll.post(()->chatScroll.fullScroll(View.FOCUS_DOWN));
    }
    private void chatBubble(String value,boolean user){
        LinearLayout bubble=column();bubble.setPadding(dp(15),dp(12),dp(15),dp(12));bubble.setBackground(bg(user?SKY:Color.WHITE,18));
        TextView who=text(user?"你":prefs.text("pet_name","蓝色大肥鱼"),11,MUTED);bubble.addView(who);gap(bubble,5);
        Matcher match=Pattern.compile("\\[表情:([^\\]]+)\\]").matcher(value);String meme=match.find()?match.group(1):null;
        TextView body=text(value.replaceAll("\\[表情:[^\\]]+\\]",""),16,INK);body.setTextIsSelectable(true);bubble.addView(body);
        if(meme!=null&&prefs.flag("memes",true)&&!meme.contains("/")&&!meme.contains("\\"))try(InputStream in=getAssets().open("memes/"+meme+".png")){ImageView image=new ImageView(this);image.setImageDrawable(android.graphics.drawable.Drawable.createFromStream(in,null));image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription(meme);bubble.addView(image,new LinearLayout.LayoutParams(dp(150),dp(150)));}catch(IOException ignored){}
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(10);if(user)lp.leftMargin=dp(30);else lp.rightMargin=dp(30);messages.addView(bubble,lp);
    }
    private void sendChat(){
        if(sending||composer==null)return;String question=composer.getText().toString().trim();if(question.isEmpty())return;
        sending=true;send.setEnabled(false);send.setText("等待…");composer.setEnabled(false);
        io.execute(()->{try{String reply=new PetApi(this).chat(question);runOnUiThread(()->{
            if(isDestroyed())return;sending=false;chatDraft="";if(composer!=null)composer.setText("");
            if(PetService.running)PetService.command(this,PetService.SAY,reply);
            if(page.equals("chat"))render();
        });}catch(Exception e){runOnUiThread(()->{if(isDestroyed())return;sending=false;toast(safeError(e));if(send!=null){send.setEnabled(true);send.setText("重试");}if(composer!=null)composer.setEnabled(true);});}});
    }
    private String safeError(Exception e){return e instanceof IOException?e.getMessage():"请求未完成，请检查配置后重试。";}
    private EditText field(LinearLayout parent,String label,String hint,String value,boolean secret){
        parent.addView(text(label,13,MUTED));gap(parent,6);EditText e=new EditText(this);e.setTextSize(15);e.setTextColor(INK);e.setHint(hint);e.setText(value);e.setSingleLine(!label.equals("人设"));e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setBackground(bg(Color.WHITE,12));
        if(secret){e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);e.setTransformationMethod(PasswordTransformationMethod.getInstance());}
        else if(label.contains("地址"))e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        parent.addView(e,new LinearLayout.LayoutParams(-1,-2));gap(parent,12);return e;
    }
    private Spinner spinner(LinearLayout p,String[] labels,int initial){Spinner s=new Spinner(this);ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);s.setSelection(initial);p.addView(s,new LinearLayout.LayoutParams(-1,dp(50)));return s;}
    private void toggle(LinearLayout p,String key,String label,boolean fallback){Switch s=new Switch(this);s.setText(label);s.setTextSize(14);s.setTextColor(INK);s.setPadding(0,dp(12),0,dp(12));s.setChecked(prefs.flag(key,fallback));p.addView(s,new LinearLayout.LayoutParams(-1,-2));s.setOnCheckedChangeListener((b,checked)->{prefs.put(key,checked);if(PetService.running)PetService.command(this,PetService.REFRESH,"");});}
    private void slider(LinearLayout p,String key,String label,int min,int max,int fallback){TextView t=text(label+"  "+prefs.number(key,fallback),14,INK);p.addView(t);SeekBar s=new SeekBar(this);s.setMax(max-min);s.setProgress(prefs.number(key,fallback)-min);p.addView(s,new LinearLayout.LayoutParams(-1,dp(44)));s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar v){}public void onProgressChanged(SeekBar v,int n,boolean user){t.setText(label+"  "+(n+min));}public void onStopTrackingTouch(SeekBar v){prefs.put(key,v.getProgress()+min);if(PetService.running)PetService.command(MainActivity.this,PetService.REFRESH,"");}});}
    private void settings(LinearLayout p){
        title(p,"MAKE IT YOURS · 04","你的陪伴方式","大小、性格和作息，都听你的。");
        section(p,"屏幕上的它");slider(p,"size","宠物画布宽度 · dp",120,420,260);slider(p,"opacity","不透明度 · %",30,100,100);slider(p,"count","宠物数量",1,3,1);
        toggle(p,"wander","自由走动",true);toggle(p,"physics","拖动后甩动与回弹",true);toggle(p,"fixed","固定位置（关闭拖动和走动）",false);toggle(p,"schedule","跟随时间吃饭、休息",true);toggle(p,"whisper","偶尔碎碎念",true);slider(p,"whisper_minutes","碎碎念间隔 · 分钟",1,30,5);
        toggle(p,"memes","对话显示原版表情包",true);toggle(p,"autostart","重启后恢复陪伴",false);
        gap(p,8);fullButton(p,"后台电池设置",false,this::batterySettings);gap(p,8);p.addView(text("如需长期陪伴，请在手机系统里允许后台运行；部分品牌还需开启自启动。",12,MUTED));
        section(p,"名字与性格");EditText name=field(p,"名字","蓝色大肥鱼",prefs.text("pet_name","蓝色大肥鱼"),false);EditText persona=field(p,"人设","你想让它怎么说话？",prefs.persona(),false);persona.setMinLines(3);
        slider(p,"memory_rounds","随请求携带的对话轮数",0,50,5);
        section(p,"对话连接");String[] modes={"offline","ai","dsh"};String mode=prefs.text("mode","offline");Spinner modeSelect=spinner(p,new String[]{"离线陪伴 · 无需密钥","独立 AI · OpenAI 兼容接口","DSH · 使用电脑端服务"},Arrays.asList(modes).indexOf(mode));
        EditText api=field(p,"AI 服务根地址","https://api.deepseek.com",prefs.text("api_url","https://api.deepseek.com"),false);
        EditText model=field(p,"模型名称","deepseek-flash",prefs.text("model","deepseek-flash"),false);
        EditText key=field(p,"API 密钥","留空保留已存密钥","",true);
        p.addView(text("密钥加密保存在本机。切换 AI 服务地址时请重新填写对应密钥。",12,MUTED));
        toggle(p,"ai_whisper","使用 AI 生成碎碎念（消耗 API 额度）",false);
        toggle(p,"balance_auto","每 30 分钟查看余额",false);
        section(p,"连接 DSH");EditText dsh=field(p,"DSH 服务根地址","http://192.168.1.10:7341",prefs.text("dsh_url",""),false);
        EditText token=field(p,"DSH 连接令牌","留空保留已存令牌","",true);EditText petId=field(p,"DSH 宠物 ID","main",prefs.text("dsh_pet","main"),false);
        toggle(p,"bridge","同步 DSH 气泡与工作状态",false);p.addView(text(prefs.text("bridge_status","填入手机可访问的 DSH 桥接地址。电脑需保持运行；电脑的 127.0.0.1 地址不能直接用于手机。"),12,MUTED));
        gap(p,20);
        Runnable save=()->{
            try{
                String newApi=api.getText().toString().trim(),newDsh=dsh.getText().toString().trim();PetApi.validateBase(newApi);if(!newDsh.isEmpty())PetApi.validateBase(newDsh);
                String newKey=key.getText().toString().trim(),newToken=token.getText().toString().trim();
                if(!newApi.equals(prefs.text("api_url","https://api.deepseek.com"))){prefs.secret("api_key",newKey);}else if(!newKey.isEmpty())prefs.secret("api_key",newKey);
                if(!newDsh.equals(prefs.text("dsh_url",""))){prefs.secret("dsh_token",newToken);}else if(!newToken.isEmpty())prefs.secret("dsh_token",newToken);
                prefs.put("pet_name",name.getText().toString().trim().isEmpty()?"蓝色大肥鱼":name.getText().toString().trim());prefs.put("persona",persona.getText().toString());prefs.put("mode",modes[modeSelect.getSelectedItemPosition()]);prefs.put("api_url",newApi);prefs.put("model",model.getText().toString().trim());prefs.put("dsh_url",newDsh);prefs.put("dsh_pet",petId.getText().toString().trim().isEmpty()?"main":petId.getText().toString().trim());
                key.setText("");token.setText("");toast("已保存到这台设备。");
            }catch(Exception e){toast(safeError(e));throw new IllegalArgumentException("配置未保存");}
        };
        fullButton(p,"保存名字与连接配置",true,()->{try{save.run();}catch(IllegalArgumentException ignored){}});gap(p,10);
        fullButton(p,"保存并测试连接",false,()->{try{save.run();background(()->new PetApi(this).testConnection());}catch(IllegalArgumentException ignored){}});gap(p,10);
        fullButton(p,"查看余额",false,()->background(()->new PetApi(this).balance()));
        section(p,"本机数据");fullButton(p,"导出聊天记录",false,()->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"蓝色大肥鱼-聊天记录.json"),40));gap(p,10);
        fullButton(p,"清空本机聊天记录",false,()->new AlertDialog.Builder(this).setTitle("清空聊天记录？").setMessage("仅删除手机内的记录，不会删除 DSH 电脑端记录。").setNegativeButton("保留",null).setPositiveButton("清空",(d,w)->{prefs.put("history","[]");toast("本机聊天记录已清空。");}).show());gap(p,10);
        fullButton(p,"移除已存密钥与令牌",false,()->new AlertDialog.Builder(this).setTitle("移除连接凭据？").setNegativeButton("取消",null).setPositiveButton("移除",(d,w)->{try{prefs.secret("api_key","");prefs.secret("dsh_token","");toast("已移除。");}catch(Exception e){toast("未能移除，请重试。");}}).show());
        section(p,"关于这只鱼");p.addView(text("蓝色大肥鱼 0.1.0\nAndroid 9+ · 基于 dsh-pet 0.3.8\n原作者 PC2005-cloud · MIT\n完整动画 "+catalog.motions.size()+" 段，随 APK 离线提供。",13,MUTED));gap(p,10);
        fullButton(p,"开源说明与隐私",false,()->{
            String license;try{license=AnimationCatalog.read(this,"UPSTREAM-LICENSE.txt");}catch(IOException e){license="MIT · PC2005-cloud";}
            new AlertDialog.Builder(this).setTitle("开源与隐私").setMessage("素材来源：https://github.com/PC2005-cloud/dsh-pet\n\n不读取屏幕、通讯录或通知内容，不申请无障碍权限，不含广告与统计 SDK。聊天只在你选择联网模式后发送至你配置的服务。AI 自动碎碎念需单独开启。\n\n系统安全页面可能隐藏悬浮窗。桌面端插件管理与任意电脑自动化不属于 Android 本地能力。\n\n"+license).setPositiveButton("知道了",null).show();
        });
    }
    private interface Job{String run()throws Exception;}
    private void background(Job job){toast("正在连接……");io.execute(()->{String result;try{result=job.run();}catch(Exception e){result=safeError(e);}String message=result;runOnUiThread(()->{if(!isDestroyed())new AlertDialog.Builder(this).setTitle("连接结果").setMessage(message).setPositiveButton("好",null).show();});});}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==40&&result==RESULT_OK&&data!=null&&data.getData()!=null){try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new IOException();out.write(new PetApi(this).history().toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));toast("聊天记录已导出。");}catch(Exception e){toast("导出失败，请选择可写入的位置。");}}}
}
