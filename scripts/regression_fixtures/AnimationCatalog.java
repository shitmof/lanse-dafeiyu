package com.lanse.fish;
import java.util.*;import java.nio.file.*;import org.json.*;
public class AnimationCatalog {
  public record Motion(String id,String name,String category,String file,float x0,float y0,float x1,float y1){}
  final List<Motion> all=new ArrayList<>();int nextCalls;
  AnimationCatalog(String path)throws Exception{
    JSONArray a=new JSONObject(Files.readString(Path.of(path))).getJSONArray("animations");
    for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);JSONArray b=o.getJSONArray("visual");
      all.add(new Motion(o.getString("id"),o.getString("name"),o.getString("category"),o.getString("file"),
        (float)b.getDouble(0),(float)b.getDouble(1),(float)b.getDouble(2),(float)b.getDouble(3)));}
  }
  Motion match(String key){for(Motion m:all)if(m.name().contains(key))return m;return all.get(0);}
  Motion nextAuto(Random r,ArrayDeque<String> recent,String current,ArrayDeque<Motion> bag){nextCalls++;return all.get(0);}
  Motion nextInCategory(String cat,Random r,String current){return all.get(0);}
  Motion nextClick(Random r,String current){return match("点击回应");}
  Motion scheduled(int hour,Random r){return null;}
  Motion workMotion(String state){return match("工作状态-忙碌点按");}
}
