package com.nuguna.print.printservice;

import android.content.Context;
import android.content.SharedPreferences;

import com.nuguna.print.helper.Net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 시스템 인쇄(설정 → 인쇄 → 누구나프린트)에 바로 보여 줄 프린터 목록.
 * 앱에서 실제로 9100 인쇄에 성공한 주소와, 인쇄 서비스 검색에서 찾은 주소를 기억한다(최대 12개, 최근 것부터).
 * 기기 밖으로 보내지 않고, 백업도 막혀 있다(allowBackup=false).
 */
public final class KnownPrinters {
    private KnownPrinters() {}

    private static final String PREFS = "nuguna-printservice";
    private static final String KEY = "printers";
    private static final int MAX = 12;

    public static final class Item {
        public final String host;
        public final String name;

        Item(String host, String name) {
            this.host = host;
            this.name = name;
        }
    }

    public static synchronized List<Item> list(Context ctx) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String host = o.optString("host");
                if (!Net.isIPv4(host) || !Net.isPrivate(host)) continue;
                out.add(new Item(host, Net.clean(o.optString("name"), 80)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 맨 앞으로 올린다. name 이 비어 있으면 예전 이름을 그대로 둔다 */
    public static synchronized void remember(Context ctx, String host, String name) {
        if (ctx == null || !Net.isIPv4(host) || !Net.isPrivate(host)) return;
        String keep = name == null ? "" : Net.clean(name, 80);
        List<Item> old = list(ctx);
        JSONArray arr = new JSONArray();
        try {
            for (Item it : old) if (it.host.equals(host) && keep.isEmpty()) keep = it.name;
            arr.put(new JSONObject().put("host", host).put("name", keep));
            for (Item it : old) {
                if (arr.length() >= MAX) break;
                if (!it.host.equals(host)) arr.put(new JSONObject().put("host", it.host).put("name", it.name));
            }
        } catch (Exception ignored) {
            return;
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
