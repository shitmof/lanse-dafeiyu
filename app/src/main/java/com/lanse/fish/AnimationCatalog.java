package com.lanse.fish;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class AnimationCatalog {
    public record Motion(String id, String name, String category, String file) {}
    public final List<Motion> motions = new ArrayList<>();
    public final JSONObject config;
    public AnimationCatalog(Context c) throws Exception {
        JSONObject catalog = new JSONObject(read(c, "catalog.json"));
        JSONArray list = catalog.getJSONArray("animations");
        for (int i=0; i<list.length(); i++) { JSONObject o=list.getJSONObject(i); motions.add(new Motion(o.getString("id"),o.getString("name"),o.getString("category"),o.getString("file"))); }
        config = new JSONObject(read(c, "original-config.json"));
    }
    public static String read(Context c, String path) throws IOException { try(InputStream in=c.getAssets().open(path)) { return PetApi.readLimited(in); } }
    public Motion find(String name) { for (Motion m: motions) if(m.id().equals(name) || m.name().equals(name)) return m; return motions.get(0); }
    public Motion match(String fragment) { for (Motion m: motions) if(m.name().contains(fragment)) return m; return find("吃白饭"); }
    public Motion random(Random r) {
        JSONObject animations=config.optJSONObject("animations");JSONArray categories=animations==null?null:animations.optJSONArray("categories");
        if(categories==null)return motions.get(r.nextInt(motions.size()));int total=10;
        for(int i=0;i<categories.length();i++)total+=categories.optJSONObject(i).optInt("weight",1);
        int choice=r.nextInt(total);if(choice<10)return match("待机");choice-=10;
        for(int i=0;i<categories.length();i++){JSONObject category=categories.optJSONObject(i);choice-=category.optInt("weight",1);if(choice<0){JSONArray actions=category.optJSONArray("actions");return find(actions.optString(r.nextInt(actions.length())));}}
        return match("吃白饭");
    }
    public Motion scheduled(int hour, Random r) {
        if (hour>=23 || hour<6) return match("小憩");
        if (hour==7 || hour==8) return match("吃早餐");
        if (hour==12) return match("吃午餐");
        if (hour==18 || hour==19) return match("吃晚餐");
        return random(r);
    }
}
