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
    /** 한 파일 상한 — 도우미가 흘려 보낼 때도 같은 값으로 다시 막는다(HelperServer.MAX_SHARED_BYTES) */
    static final long MAX_BYTES = 48L * 1024 * 1024;

    /** 표시 · 저장용 파일 이름: 경로(/ \ ..)와 제어 문자를 지우고 줄인다 */
    static String safeName(String raw) {
        String s = Net.clean(raw == null ? "" : raw, 200).replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\.{2,}", ".");
        s = s.replaceAll("^[.\\s]+", "").trim();
        if (s.isEmpty()) s = "shared";
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    /* 받는 파일 종류: 사진(jpeg · png · webp · gif · heic · bmp) · PDF · 글. SVG처럼 스크립트를 품을 수 있는 형식은 뺀다 */
    static final java.util.regex.Pattern ALLOWED_MIME = java.util.regex.Pattern.compile(
        "^(image/(jpeg|jpg|png|webp|gif|heic|heif|bmp)|application/pdf|text/plain)$");

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
            /* 우리 앱 자신의 파일 주소(FileProvider 등)는 받지 않는다 — 다른 앱이 공유로 우리 내부 파일을 열게 하는 것 방지 */
            String authority = u.getAuthority();
            if (authority == null || authority.startsWith(ctx.getPackageName())) continue;
            String mime = cr.getType(u);
            if (mime == null) mime = intent.getType() == null ? "" : intent.getType();
            /* 파일 종류는 공유한 앱이 알려 주는 값이라 그대로 믿지 않는다: 소문자 정규화 + 허용 목록만 (응답 헤더에 들어간다) */
            int semi = mime.indexOf(';');
            mime = (semi >= 0 ? mime.substring(0, semi) : mime).trim().toLowerCase(java.util.Locale.US);
            if (!ALLOWED_MIME.matcher(mime).matches()) continue;
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
            /* 크기를 알려 준 파일이 상한보다 크면 처음부터 받지 않는다(모르는 크기는 보낼 때 상한으로 막는다) */
            if (size > MAX_BYTES) continue;
            next.add(new Item(u, safeName(name), mime, size));
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
