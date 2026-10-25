package com.lanse.fish;
import android.content.Context; import java.util.*;
public final class Prefs {
  public static final Map<String,Object> values=new HashMap<>();
  public Prefs(Context c){}
  public String text(String k,String d){return (String)values.getOrDefault(k,d);}
  public int number(String k,int d){return (Integer)values.getOrDefault(k,d);}
  public boolean flag(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}
  public void put(String k,String v){values.put(k,v);} public void put(String k,int v){values.put(k,v);}
  public void put(String k,boolean v){values.put(k,v);} public String persona(){return "Fixture persona";}
  public String secret(String k){return text(k,"");}
}
