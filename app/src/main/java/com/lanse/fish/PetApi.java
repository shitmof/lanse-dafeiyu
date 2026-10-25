package com.lanse.fish;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public final class PetApi {
    private final Prefs prefs;
    private final Context context;
    public PetApi(Context c){context=c.getApplicationContext();prefs=new Prefs(c);}
    public static URI validateBase(String input) throws Exception {
        URI uri=new URI(input.trim());String host=uri.getHost();
        if(host==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IOException("地址格式不正确，请填写不含密钥的服务根地址。");
        String path=uri.getPath()==null?"":uri.getPath().replaceAll("/+$","");
        if(path.endsWith("/chat/completions")||path.endsWith("/completions"))throw new IOException("请填写服务根地址（例如 https://api.deepseek.com 或 https://服务地址/v1），不要包含 /chat/completions。");
        boolean local=host.equals("localhost")||host.equals("127.0.0.1")||host.equals("[::1]")||host.matches("10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")||host.matches("192\\.168\\.\\d{1,3}\\.\\d{1,3}")||host.matches("172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}");
        if(!"https".equals(uri.getScheme())&&!("http".equals(uri.getScheme())&&local))throw new IOException("公网服务需使用 HTTPS；HTTP 仅支持本机和局域网 IP。");
        return uri;
    }
    private String endpoint(String base,String path)throws Exception {
        validateBase(base);
        String clean=base.replaceAll("/+$","");
        if(clean.endsWith("/chat/completions")||clean.endsWith("/completions"))throw new IOException("服务根地址不要包含 /chat/completions，请删除后再保存。");
        return clean+path;
    }
    private String httpError(HttpURLConnection conn,int status,String token,JSONObject body){
        String raw="";
        try(InputStream err=conn.getErrorStream()){if(err!=null)raw=readLimited(err);}catch(Exception ignored){}
        String base=switch(status){case 401,403->"认证失败，请检查密钥或连接令牌。";case 429->"请求过于频繁或额度不足，请稍后再试。";case 404->"服务接口不存在，请检查服务根地址和模型名。";default->"服务暂不可用（HTTP "+status+"）。";};
        JSONObject json=null;
        try{json=new JSONObject(raw);}catch(Exception ignored){}
        if(json==null)return base+" 服务返回非 JSON 错误（正文已隐藏）。";
        String code="",message="";
        JSONObject error=json.optJSONObject("error");
        if(error!=null){code=error.optString("code","");message=error.optString("message","");}
        if(message.isEmpty())message=json.optString("message","");
        if(code.isEmpty())code=json.optString("code","");
        String safe=sanitizeError((code.isEmpty()?"":code+": ")+message,token,body);
        if(safe.isEmpty())safe="服务返回错误（详情已隐藏）";
        return base+" 服务返回："+safe;
    }
    private void requestValues(Object value,List<String> out){
        if(value instanceof String){String s=(String)value;if(s.length()>=4)out.add(s);return;}
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;Iterator<String> it=o.keys();while(it.hasNext())requestValues(o.opt(it.next()),out);return;}
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)requestValues(a.opt(i),out);}
    }
    private String sanitizeError(String input,String token,JSONObject body){
        if(input==null)return "";
        String safe=input.replaceAll("\\s+"," ").trim();
        if(token!=null&&!token.isEmpty())safe=safe.replace(token,"[凭据已隐藏]");
        safe=safe.replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+","Bearer [凭据已隐藏]");
        safe=safe.replaceAll("(?i)sk-[A-Za-z0-9_-]{8,}","[凭据已隐藏]");
        safe=safe.replaceAll("(?i)\\b(api[_-]?key|access[_-]?token|auth(?:orization)?|token|password|secret)\\b\\s*[:=]\\s*[^\\s,;]+","$1=[已隐藏]");
        List<String> values=new ArrayList<>();requestValues(body,values);
        for(String value:values){if(value.length()>=4)safe=safe.replace(value,"[请求内容已隐藏]");}
        if(safe.length()>300)safe=safe.substring(0,300)+"…";
        return safe;
    }
    private JSONObject request(String method,String url,String token,JSONObject body)throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(12000);conn.setReadTimeout(60000);conn.setRequestMethod(method);
        conn.setRequestProperty("Accept","application/json");
        if(!token.isEmpty())conn.setRequestProperty("Authorization","Bearer "+token);
        try{
            if(body!=null){conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream out=conn.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=conn.getResponseCode();
            if(status<200||status>=300)throw new IOException(httpError(conn,status,token,body));
            String contentType=conn.getContentType();
            if(contentType!=null&&contentType.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream"))throw new IOException("服务返回了流式响应；当前版本只支持非流式聊天，请改用非流式接口。");
            try(InputStream in=conn.getInputStream()){
                String raw=readLimited(in);
                try{return new JSONObject(raw);}
                catch(JSONException e){throw new IOException("服务返回了非 JSON 响应，请检查服务根地址和接口协议。");}
            }
        }catch(SocketTimeoutException e){throw new IOException("连接超时，请检查网络或稍后重试。");}
        catch(UnknownHostException e){throw new IOException("找不到服务器，请检查地址和网络。");}
        finally{conn.disconnect();}
    }
    static String readLimited(InputStream in)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=in.read(buffer))!=-1){if(out.size()+n>2097152)throw new IOException("服务响应过大。");out.write(buffer,0,n);}
        return out.toString("UTF-8");
    }
    public JSONArray history(){try{return new JSONArray(prefs.text("history","[]"));}catch(JSONException e){return new JSONArray();}}
    public static JSONObject message(String role,String text)throws JSONException{return new JSONObject().put("role",role).put("content",text);}
    public synchronized void saveRound(String user,String assistant)throws Exception{
        JSONArray old=history(), list=new JSONArray();int start=Math.max(0,old.length()-198);
        for(int i=start;i<old.length();i++)list.put(old.get(i));list.put(message("user",user));list.put(message("assistant",assistant));prefs.put("history",list.toString());
    }
    public String chat(String text)throws Exception {
        if(text.trim().isEmpty())throw new IOException("先写一句话吧。");
        if(text.length()>8000)throw new IOException("单条消息请控制在 8000 字以内。");
        String mode=prefs.text("mode","offline"),reply;
        if(mode.equals("offline"))reply=offline(text);
        else if(mode.equals("dsh")){JSONObject r=dsh("POST","/chat?pet="+petId(),new JSONObject().put("text",text));reply=replyText(r);}
        else reply=completion(text,true);
        saveRound(text,reply);return reply;
    }
    private String offline(String text){
        if(text.contains("饭")||text.contains("吃"))return "那就先好好吃饭吧，我的碗已经准备好了！";
        if(text.contains("累")||text.contains("困"))return "辛苦啦。先歇一会儿，我安静陪着你。";
        if(text.contains("你好")||text.contains("在吗"))return "在呀！蓝色大肥鱼抱着饭碗来报到。";
        return "我在听，也会陪着你。现在是离线陪伴模式，去设置连接 AI 后，我们就能自由聊天啦。";
    }
    private String completion(String text,boolean memory)throws Exception{
        String base=prefs.text("api_url","https://api.deepseek.com");String key=prefs.secret("api_key");
        if(key.isEmpty())throw new IOException("请先在设置里保存 API 密钥。");
        JSONArray messages=new JSONArray();String system=prefs.persona()+"\n你的名字是"+prefs.text("pet_name","蓝色大肥鱼")+"。当前手机时间："+new SimpleDateFormat("yyyy-MM-dd E HH:mm",Locale.CHINA).format(new Date());
        if(prefs.flag("memes",true)){
            JSONObject memes=new AnimationCatalog(context).config.optJSONObject("memes");
            if(memes!=null){List<String> names=new ArrayList<>();Iterator<String> it=memes.keys();while(it.hasNext()&&names.size()<10)names.add(it.next());system+="\n需要时可以在回复末尾加一个表情标记 [表情:名称]，可选名称："+String.join("、",names)+"。不用每次都加。";}
        }
        messages.put(message("system",system));
        if(memory){JSONArray h=history();int rounds=Math.max(0,Math.min(50,prefs.number("memory_rounds",5)));for(int i=Math.max(0,h.length()-2*rounds);i<h.length();i++)messages.put(h.get(i));}
        messages.put(message("user",text));
        JSONObject body=new JSONObject().put("model",prefs.text("model","deepseek-flash")).put("messages",messages).put("stream",false).put("max_tokens",1024);
        if(new URI(base).getHost().equals("api.deepseek.com"))body.put("thinking",new JSONObject().put("type","disabled"));
        JSONObject r=request("POST",endpoint(base,"/chat/completions"),key,body);
        try{
            JSONObject choice=r.getJSONArray("choices").getJSONObject(0);
            JSONObject message=choice.getJSONObject("message");
            String reply=contentText(message);
            if(reply.isEmpty()){
                String finish=choice.optString("finish_reason","");
                throw new IOException("模型没有返回正文"+(finish.isEmpty()?"":"（finish_reason="+finish+"）")+"，请换一个模型或重试。");
            }
            return reply;
        }catch(JSONException e){throw new IOException("聊天响应格式不兼容，请检查服务商接口是否为非流式 Chat Completions。");}
    }
    private String contentText(JSONObject message){
        Object content=message.opt("content");
        if(content instanceof String)return ((String)content).trim();
        if(content instanceof JSONArray){
            StringBuilder b=new StringBuilder();
            JSONArray parts=(JSONArray)content;
            for(int i=0;i<parts.length();i++){
                JSONObject part=parts.optJSONObject(i);if(part==null)continue;
                String text=part.optString("text","");
                if(!text.isEmpty())b.append(text);
            }
            return b.toString().trim();
        }
        return "";
    }
    public String whisper()throws Exception{
        if(prefs.text("mode","offline").equals("dsh"))return replyText(dsh("POST","/whisper?pet="+petId(),new JSONObject()));
        return completion("请自然地碎碎念一句，20字以内。",false);
    }
    public String balance()throws Exception{
        if(prefs.text("mode","offline").equals("offline"))throw new IOException("余额需要连接服务，请先在设置中配置。");
        if(prefs.text("mode","offline").equals("dsh")){
            JSONObject r=dsh("POST","/balance",new JSONObject());
            if(!r.optBoolean("ok",true))throw new IOException("DSH 的余额服务暂不可用。");
            String text=r.optString("text","");if(!text.isEmpty())return stamp(text);
            JSONObject sections=state().optJSONObject("sections"),leaf=sections==null?null:sections.optJSONObject("balance"),data=leaf==null?null:leaf.optJSONObject("data");
            if(data==null)throw new IOException("DSH 已接收请求，但还没有返回余额数据，请稍后再试。");
            return stamp(formatBalance(data));
        }
        String base=prefs.text("api_url","https://api.deepseek.com");
        if(!new URI(base).getHost().equals("api.deepseek.com"))throw new IOException("独立模式余额查询支持 DeepSeek 官方 API；其他服务可通过 DSH 同步。");
        JSONObject r=request("GET","https://api.deepseek.com/user/balance",prefs.secret("api_key"),null);
        JSONArray info=r.getJSONArray("balance_infos");StringBuilder result=new StringBuilder("当前余额");
        double total=0;for(int i=0;i<info.length();i++){JSONObject b=info.getJSONObject(i);result.append("\n").append(b.getString("total_balance")).append(" ").append(b.getString("currency"));total+=b.optDouble("total_balance",0);}
        prefs.put("balance_motion",total<=0?5:2);
        prefs.put("balance",result.toString());return stamp(result.toString());
    }
    private String stamp(String text){
        String at=new SimpleDateFormat("HH:mm",Locale.CHINA).format(new Date());
        prefs.put("balance_at",at);
        return text+"\n更新于 "+at;
    }
    public String testConnection()throws Exception{
        if(prefs.text("mode","offline").equals("offline"))return "离线模式已就绪，不需要网络。";
        if(prefs.text("mode","offline").equals("dsh")){JSONObject s=state();if(!s.has("pets")||!s.has("sections"))throw new IOException("该地址不是兼容的 DSH 桌宠接口。");return "DSH 地址检查通过。";}
        JSONObject r=request("GET",endpoint(prefs.text("api_url","https://api.deepseek.com"),"/models"),prefs.secret("api_key"),null);
        JSONArray data=r.optJSONArray("data");if(data==null)throw new IOException("模型列表格式不兼容。");
        String desired=prefs.text("model","deepseek-flash");boolean found=false;for(int i=0;i<data.length();i++)if(desired.equals(data.getJSONObject(i).optString("id")))found=true;
        return found?"地址与模型列表检查通过；这不代表聊天可用，请用“发送真实测试消息”验证。":"地址检查通过，但模型列表中未找到所填模型，请核对名称。";
    }

    /** One explicit, user-triggered chat request; never called automatically. */
    public String testChat(String text)throws Exception{
        String mode=prefs.text("mode","offline");
        if(mode.equals("offline"))throw new IOException("当前是离线陪伴模式，请先选择联网对话。");
        if(mode.equals("dsh"))return replyText(dsh("POST","/chat?pet="+petId(),new JSONObject().put("text",text)));
        return completion(text,false);
    }
    public JSONObject state()throws Exception{return dsh("GET","/state",null);}
    private String petId()throws Exception{return URLEncoder.encode(prefs.text("dsh_pet","main"),"UTF-8");}
    private JSONObject dsh(String method,String path,JSONObject body)throws Exception{
        String base=prefs.text("dsh_url","");if(base.isEmpty())throw new IOException("请先填写 DSH 连接地址。");
        return request(method,endpoint(base,"/dsh-pet-7340"+path),prefs.secret("dsh_token"),body);
    }
    private String replyText(JSONObject result)throws Exception{if(!result.optBoolean("ok",true)||result.optString("text").isEmpty())throw new IOException("DSH 未返回回复，请检查电脑上的模型配置。");return result.getString("text");}
    public String formatBalance(JSONObject raw)throws IOException{
        if(!raw.optBoolean("ok"))throw new IOException("余额暂不可用，请检查 DSH 的余额服务配置。");
        JSONObject d=raw.optJSONObject("data");if(d==null)throw new IOException("没有可用的余额数据。");
        String shape=raw.optString("shape"),result;double percent=-1;
        if(shape.equals("money")){JSONArray wallets=d.optJSONArray("wallets");if(wallets==null||wallets.length()==0)throw new IOException("没有可用的钱包数据。");StringBuilder b=new StringBuilder("当前余额");
            for(int i=0;i<wallets.length();i++){JSONObject wallet=wallets.optJSONObject(i);if(wallet==null)continue;b.append("\n").append(wallet.optString("total")).append(" ").append(wallet.optString("currency"));double full=wallet.optDouble("fullBalance",0),remaining=wallet.optDouble("total",0);if(full>0)percent=Math.max(percent,100*(1-remaining/full));else if(remaining<=0)percent=100;}result=b.toString();
        }else if(shape.equals("points")){result="当前余额 "+d.optString("total")+" "+d.optString("unit","积分");double full=d.optDouble("full",0);if(full>0)percent=100*(1-d.optDouble("total",0)/full);
        }else if(shape.equals("windows")){StringBuilder b=new StringBuilder("额度用量");String[] keys={"rolling","weekly","monthly"},labels={d.optString("rollingLabel","滚动"),"每周","每月"};for(int i=0;i<keys.length;i++){if(d.has(keys[i])){double v=d.optDouble(keys[i],0);b.append("\n").append(labels[i]).append("已用 ").append(Math.round(v)).append('%');percent=Math.max(percent,v);}}result=b.toString();
        }else throw new IOException("此余额格式暂不支持。");
        prefs.put("balance_motion",percent<0?2:percent>=100?5:Math.min(4,(int)Math.max(0,percent)/20));prefs.put("balance",result);return stamp(result);
    }
}
