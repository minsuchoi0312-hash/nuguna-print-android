package com.nuguna.print.helper;

import android.content.Intent;
import android.net.Uri;

/**
 * 프린터 연결 링크(nugunaprint://connect?ip=…&name=…)를 화면이 가져갈 때까지 들고 있는 곳.
 * MainActivity 가 넣고, NugunaNativePrint.takeLink 로 화면이 한 번 꺼내 간다.
 */
public final class LinkInbox {
    private LinkInbox() {}

    private static String pending = null;

    /** 연결 링크 인텐트면 받아 두고 true */
    public static synchronized boolean take(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return false;
        Uri u = intent.getData();
        if (u == null || !"nugunaprint".equals(u.getScheme()) || !"connect".equals(u.getHost())) return false;
        String s = u.toString();
        if (s.length() > 2000) return false;
        pending = s;
        return true;
    }

    static synchronized String takePending() {
        String s = pending;
        pending = null;
        return s;
    }
}
