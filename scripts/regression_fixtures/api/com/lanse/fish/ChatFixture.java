package com.lanse.fish;
import android.content.Context;
import com.sun.net.httpserver.*;
import org.json.*;
import java.net.*; import java.nio.charset.StandardCharsets; import java.util.*;

public final class ChatFixture {
  static String scenario="normal";
  static final List<String> requests=new ArrayList<>();
  static JSONObject lastBody;
  static String lastErrorBody;
  public static void main(String[] args)throws Exception {
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",x->{
      String method=x.getRequestMethod(),path=x.getRequestURI().getPath();
      requests.add(method+" "+path);
      byte[] bytes=x.getRequestBody().readAllBytes();
      if(bytes.length>0)lastBody=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
      int status=200;String response,ctype="application/json";
      if(path.equals("/v1/models"))response="{\"data\":[{\"id\":\"fixture-model\"}]}";
      else if(!path.equals("/v1/chat/completions")){status=404;response="{\"error\":{\"message\":\"fixture route missing\"}}";}
      else if(scenario.equals("echoCredentialJson")){
        status=400;response=new JSONObject().put("error",new JSONObject().put("code","fixture_error")
          .put("message","request auth: "+x.getRequestHeaders().getFirst("Authorization")+"; input: fixture question")).toString();
      }
      else if(scenario.equals("echoCredentialText")){
        status=400;ctype="text/plain";response="request auth: "+x.getRequestHeaders().getFirst("Authorization")+"; input: fixture question";
      }
      else if(scenario.equals("echoMultiline")||scenario.equals("echoShort")||scenario.equals("echoRepeatedSpace")){
        String input=lastBody.getJSONArray("messages").getJSONObject(lastBody.getJSONArray("messages").length()-1).getString("content");
        status=400;response=new JSONObject().put("error",new JSONObject().put("code","fixture_error").put("message","rejected input: "+input)).toString();
      }
      else if(scenario.equals("chat400")){status=400;response="{\"error\":{\"code\":\"unsupported_parameter\",\"message\":\"max_tokens is not supported; use max_completion_tokens\"}}";lastErrorBody=response;}
      else if(scenario.equals("reasoningOnly"))response="{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"\",\"reasoning_content\":\"fixture reasoning\"}}]}";
      else if(scenario.equals("contentArray"))response="{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"fixture text\"}]}}]}";
      else if(scenario.equals("sse")){ctype="text/event-stream";response="data: {\"choices\":[{\"delta\":{\"content\":\"fixture text\"}}]}\n\ndata: [DONE]\n\n";}
      else response="{\"choices\":[{\"message\":{\"content\":\"fixture answer\"}}]}";
      byte[] res=response.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",ctype);x.sendResponseHeaders(status,res.length);x.getResponseBody().write(res);x.close();
    });
    server.start();
    try {
      String base="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
      PetApi api=new PetApi(new Context()); JSONArray results=new JSONArray();
      for(String s:new String[]{"normal","chat400","fullEndpoint","reasoningOnly","contentArray","sse","blankModel","offline","echoCredentialJson","echoCredentialText","echoMultiline","echoShort","echoRepeatedSpace"}){
        scenario=s;requests.clear();lastBody=null;Prefs.values.clear();
        Prefs.values.put("mode",s.equals("offline")?"offline":"ai");
        Prefs.values.put("api_url",s.equals("fullEndpoint")?base+"/chat/completions":base);
        Prefs.values.put("model",s.equals("blankModel")?"":"fixture-model");
        Prefs.values.put("api_key","fixture-only-not-a-real-key");Prefs.values.put("memes",false);
        JSONObject result=new JSONObject().put("scenario",s);
        try {result.put("connection",api.testConnection());}catch(Exception e){result.put("connectionError",e.getClass().getSimpleName()+": "+e.getMessage());}
        String question=s.equals("echoMultiline")?"fixture private line one\nfixture private line two":s.equals("echoShort")?"甲乙丙":s.equals("echoRepeatedSpace")?"fixture  private  value":"fixture question";
        try {result.put("reply",api.chat(question));}catch(Exception e){result.put("chatError",e.getClass().getSimpleName()+": "+e.getMessage());}
        if(s.startsWith("echoCredential")){
          String detail=result.optString("chatError");result.remove("chatError");
          result.put("fixture_credential_returned",detail.contains("fixture-only-not-a-real-key"));
          result.put("fixture_request_text_returned",detail.contains("fixture question"));
          if(detail.contains("fixture-only-not-a-real-key"))throw new AssertionError("credential leaked");
          if(detail.contains("fixture question"))throw new AssertionError("request text leaked");
        }
        if(s.equals("echoMultiline")||s.equals("echoShort")||s.equals("echoRepeatedSpace")){
          String detail=result.optString("chatError");result.remove("chatError");
          result.put("normalized_request_text_returned",detail.contains(question.replaceAll("\\s+"," ")));
          result.put("request_hidden_marker_present",detail.contains("[请求内容已隐藏]"));
        }
        result.put("requestPaths",new JSONArray(requests));
        if(lastBody!=null)result.put("sentModel",lastBody.optString("model")).put("sentStream",lastBody.optBoolean("stream")).put("maxTokens",lastBody.optInt("max_tokens"));
        result.put("savedMessages",api.history().length());results.put(result);
      }
      System.out.println(results.toString(2));
    }finally{server.stop(0);}
  }
}
