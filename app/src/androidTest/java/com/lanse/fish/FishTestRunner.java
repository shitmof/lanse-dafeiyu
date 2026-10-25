package com.lanse.fish;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Dependency-free device acceptance tests; always use a dedicated test emulator. */
public final class FishTestRunner extends Instrumentation {
    private int checks=0;
    private StringBuilder report=new StringBuilder();
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private void log(String s){report.append(s).append('\n');Bundle b=new Bundle();b.putString("stream",s+"\n");sendStatus(0,b);}
    @Override public void onStart(){
        Bundle result=new Bundle();Context c=getTargetContext();Prefs prefs=new Prefs(c);
        try{
            AnimationCatalog catalog=new AnimationCatalog(c);check(catalog.motions.size()==106,"Complete 0.3.8 animation count");
            for(AnimationCatalog.Motion m:catalog.motions){
                Drawable d=ImageDecoder.decodeDrawable(ImageDecoder.createSource(c.getAssets(),m.file()));
                check(d instanceof AnimatedImageDrawable,"Must animate: "+m.name());check(d.getIntrinsicWidth()==480&&d.getIntrinsicHeight()==270,"Mobile canvas: "+m.name());
                Bitmap frame=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(frame);d.setBounds(0,0,640,360);d.draw(canvas);
                boolean transparent=false,visible=false;for(int y=0;y<360;y+=8)for(int x=0;x<640;x+=8){int a=Color.alpha(frame.getPixel(x,y));transparent|=a==0;visible|=a>200;}
                check(transparent&&visible,"Alpha and visible pixels: "+m.name());((AnimatedImageDrawable)d).stop();frame.recycle();
            }
            log("PASS: all 106 original animations decode, animate, preserve transparent background, and contain visible pixels.");
            check(catalog.scheduled(2,new Random()).name().contains("小憩"),"Night schedule");check(catalog.scheduled(12,new Random()).name().contains("午餐"),"Lunch schedule");
            ArrayDeque<AnimationCatalog.Motion> bag=new ArrayDeque<>();ArrayDeque<String> recent=new ArrayDeque<>();Set<String> seen=new HashSet<>();String current=null;
            for(int i=0;i<catalog.autoPool().size();i++){AnimationCatalog.Motion m=catalog.nextAuto(new Random(i),recent,current,bag);check(seen.add(m.id()),"No repeated ordinary animation in one complete cycle");current=m.id();recent.add(m.id());if(recent.size()>6)recent.removeFirst();}
            check(seen.size()==catalog.autoPool().size(),"Complete automatic animation coverage");
            check(PetApi.parseHistory("[{\"role\":\"user\",\"content\":\"旧版导出\"}]").length()==1,"Import previous exported chat");
            boolean badImport=false;try{PetApi.parseHistory("[{\"role\":\"system\",\"content\":\"invalid\"}]");}catch(Exception e){badImport=true;}check(badImport,"Import cannot insert system instructions");
            prefs.secret("test_secret","test-value-not-a-real-credential");check(prefs.secret("test_secret").equals("test-value-not-a-real-credential"),"KeyStore roundtrip");check(!prefs.text("test_secret","").contains("test-value"),"No cleartext credential");prefs.secret("test_secret","");
            boolean rejected=false;try{PetApi.validateBase("http://public.example.com");}catch(Exception e){rejected=true;}check(rejected,"Block public cleartext");
            rejected=false;try{PetApi.validateBase("https://user:password@example.com");}catch(Exception e){rejected=true;}check(rejected,"Block URL embedded credentials");PetApi.validateBase("http://192.168.1.3:7341");
            log("PASS: Android Keystore encryption, unsafe URL rejection, local bridge address validation.");
            prefs.put("mode","offline");prefs.put("history","[]");String offline=new PetApi(c).chat("你好");check(offline.contains("饭碗"),"Offline response");check(new PetApi(c).history().length()==2,"Conversation persistence");
            try(MockServer mock=new MockServer()){
                prefs.put("mode","ai");prefs.put("api_url","http://127.0.0.1:"+mock.port());prefs.put("model","fixture-model");prefs.secret("api_key","fixture-key");
                String reply=new PetApi(c).chat("测试联网对话");check(reply.equals("这是测试服务器的回复。"),"Real HTTP chat roundtrip");check(mock.lastBody.contains("测试联网对话"),"Request carries text");check(mock.lastBody.contains("system"),"Persona passed");check(mock.auth.equals("Bearer fixture-key"),"Authorization header");
                check(new PetApi(c).testConnection().contains("不代表聊天可用"),"Models check is not chat proof");
                prefs.put("token_parameter","max_completion_tokens");new PetApi(c).testChat("长度兼容测试");
                check(new JSONObject(mock.lastBody).has("max_completion_tokens")&&!new JSONObject(mock.lastBody).has("max_tokens"),"Alternate output-limit field");prefs.put("token_parameter","max_tokens");
                prefs.put("dsh_url","http://127.0.0.1:"+mock.port());prefs.put("mode","dsh");check(new PetApi(c).state().has("sections"),"DSH state contract");check(new PetApi(c).chat("来自手机").equals("DSH fixture reply"),"DSH chat contract");
                mock.fail=true;int before=new PetApi(c).history().length();boolean failed=false;try{new PetApi(c).chat("should fail");}catch(IOException e){failed=true;}check(failed,"401 surfaces as failure");check(new PetApi(c).history().length()==before,"Failed request does not persist a fake answer");
            }
            log("PASS: actual local HTTP requests for AI chat/models and DSH chat/state; authentication failure has no fabricated result.");
            prefs.put("mode","offline");prefs.put("api_url","https://api.deepseek.com");prefs.put("model","deepseek-flash");prefs.secret("api_key","");prefs.put("dsh_url","");prefs.put("history","[]");prefs.put("wander",false);
            Intent launch=new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);Activity activity=startActivitySync(launch);waitForIdleSync();
            PetView[] early=new PetView[1];runOnMainSync(()->{early[0]=new PetView(activity);early[0].play(catalog.match("吃白饭"));});SystemClock.sleep(400);
            runOnMainSync(()->activity.addContentView(early[0],new android.view.ViewGroup.LayoutParams(390,219)));SystemClock.sleep(400);check(early[0].getDrawable() instanceof AnimatedImageDrawable,"Decode finishing before window attachment is still displayed");
            int[] finished={0};
            // stop() queues an end event. Restart must ignore that old event;
            // detach must not clear Android's list before that event executes.
            runOnMainSync(()->early[0].play(catalog.match("吃白饭"),()->finished[0]++));
            waitForIdleSync();check(finished[0]==0,"Restart ignores queued stop completion");
            runOnMainSync(()->early[0].pause(true));waitForIdleSync();
            check(finished[0]==0,"Pausing is not natural animation completion");
            runOnMainSync(()->{early[0].pause(false);((AnimatedImageDrawable)early[0].getDrawable()).stop();((android.view.ViewGroup)early[0].getParent()).removeView(early[0]);});
            waitForIdleSync();check(finished[0]==0,"Queued end after detach neither crashes nor completes replaced clip");
            log("PASS: animated drawable restart, pause and queued-end detach regression.");
            runOnMainSync(()->PetService.command(c,PetService.REFRESH,""));SystemClock.sleep(1800);check(PetService.running,"Overlay foreground service starts");
            AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;getUiAutomation().setServiceInfo(info);
            getUiAutomation().executeShellCommand("input keyevent 3").close();SystemClock.sleep(700);check(PetService.running,"Service survives Home");check(overlayCount()==1,"Exactly one visible overlay; no pending-window leak");
            prefs.put("count",3);runOnMainSync(()->PetService.command(c,PetService.REFRESH,""));SystemClock.sleep(700);check(overlayCount()==3,"Three-pet setting creates exactly three windows");
            prefs.put("count",1);runOnMainSync(()->PetService.command(c,PetService.REFRESH,""));SystemClock.sleep(700);check(overlayCount()==1,"Rebuilding removes all old windows");
            runOnMainSync(()->PetService.command(c,PetService.PLAY,catalog.match("吃白饭").id()));SystemClock.sleep(500);check(PetService.running,"Manual animation while backgrounded");
            runOnMainSync(()->PetService.command(c,PetService.STOP,""));SystemClock.sleep(500);check(!PetService.running,"Explicit stop removes service");check(!prefs.flag("enabled",true),"Stop persists opt-out");check(overlayCount()==0,"Stop removes every overlay window");
            prefs.put("wander",true);runOnMainSync(activity::finish);
            log("PASS: user-started overlay service, Home/background operation, manual animation, explicit persisted stop.");
            result.putString("stream","\nOK: "+checks+" checks\n"+report);finish(Activity.RESULT_OK,result);
        }catch(Throwable t){result.putString("stream","\nFAIL after "+checks+" checks: "+t+"\n"+android.util.Log.getStackTraceString(t));finish(Activity.RESULT_CANCELED,result);}
    }
    private int overlayCount(){int count=0;for(AccessibilityWindowInfo window:getUiAutomation().getWindows()){AccessibilityNodeInfo root=window.getRoot();if(window.getType()==AccessibilityWindowInfo.TYPE_SYSTEM&&root!=null&&"com.lanse.fish".contentEquals(root.getPackageName()))count++;}return count;}
    private static final class MockServer implements AutoCloseable{
        final ServerSocket server;final Thread thread;volatile boolean fail,closed;volatile String lastBody="",auth="";
        MockServer()throws IOException{server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));thread=new Thread(()->{while(!closed)try(Socket socket=server.accept()){
            socket.setSoTimeout(5000);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));String request=reader.readLine(),line;int length=0;auth="";
            while((line=reader.readLine())!=null&&!line.isEmpty()){if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());if(line.toLowerCase(Locale.ROOT).startsWith("authorization:"))auth=line.substring(14).trim();}
            // Read byte length directly through a character stream safely for these UTF-8 requests.
            StringBuilder body=new StringBuilder();while(length>0){int ch=reader.read();if(ch<0)break;body.append((char)ch);length-=String.valueOf((char)ch).getBytes(StandardCharsets.UTF_8).length;}lastBody=body.toString();
            String json=request.contains("/models")?"{\"data\":[{\"id\":\"fixture-model\"}]}":request.contains("/state")?"{\"counter\":1,\"pets\":{\"main\":{}},\"sections\":{}}":request.contains("/dsh-pet-7340/chat")?"{\"ok\":true,\"text\":\"DSH fixture reply\"}":"{\"choices\":[{\"message\":{\"content\":\"这是测试服务器的回复。\"}}]}";
            byte[] bytes=json.getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+(fail?"401 Unauthorized":"200 OK")+"\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
        }catch(Exception e){if(!closed)throw new RuntimeException(e);}},"fish-test-http");thread.start();}
        int port(){return server.getLocalPort();}
        public void close()throws IOException{closed=true;server.close();}
    }
}
