package com.nuguna.print.helper;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 다른 앱의 '공유'로 받은 파일(사진 · PDF · 글)을 잠깐 들고 있는 곳.
 * MainActivity 가 넣고, 내장 도우미의 /shared · /shared/file 로 화면이 꺼내 간다.
 * 파일은 복사하지 않고 content:// 주소만 기억한다(공유한 앱이 준 읽기 권한으로 연다).
 */
public final class SharedInbox {
    private SharedInbox() {}

    static final int MAX_FILES = 20;

    static final class Item {
        final Uri uri;
        final String name;
        final String mime;
        final long size;

        Item(Uri uri, String name, String mime, long size) {
            this.uri = uri;
            this.name = name;
            this.mime = mime;
            this.size = size;
        }
    }

    private static final List<Item> items = new ArrayList<>();
    private static int batch = 0;

    /** 공유 인텐트면 받아 두고 true. 아니면 false */
    @SuppressWarnings("deprecation")
    public static synchronized boolean take(Context ctx, Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri one = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (one != null) uris.add(one);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) uris.addAll(many);
        } else {
            return false;
        }
        ContentResolver cr = ctx.getContentResolver();
        List<Item> next = new ArrayList<>();
        for (Uri u : uris) {
            if (next.size() >= MAX_FILES) break;
            /* 다른 앱의 content:// 만 받는다 (file:// 로 우리 앱 내부 파일을 읽히는 것 방지) */
            if (u == null || !ContentResolver.SCHEME_CONTENT.equals(u.getScheme())) continue;
            String mime = cr.getType(u);
            if (mime == null) mime = intent.getType() == null ? "" : intent.getType();
            String name = "shared";
            long size = -1;
            try (Cursor c = cr.query(u, new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int si = c.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                }
            } catch (Exception ignored) {
            }
            if (!(mime.startsWith("image/") || mime.equals("application/pdf") || mime.equals("text/plain"))) continue;
            next.add(new Item(u, Net.clean(name, 120), mime, size));
        }
        if (next.isEmpty()) return false;
        items.clear();
        items.addAll(next);
        batch++;
        return true;
    }

    static synchronized JSONObject list() throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("batch", batch);
        JSONArray files = new JSONArray();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            JSONObject f = new JSONObject();
            f.put("i", i);
            f.put("name", it.name);
            f.put("mime", it.mime);
            f.put("size", it.size);
            files.put(f);
        }
        o.put("files", files);
        return o;
    }

    static synchronized Item get(int wantBatch, int i) {
        if (wantBatch != batch || i < 0 || i >= items.size()) return null;
        return items.get(i);
    }
}
