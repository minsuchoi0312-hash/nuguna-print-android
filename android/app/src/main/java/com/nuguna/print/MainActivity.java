package com.nuguna.print;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;
import com.nuguna.print.helper.LinkInbox;
import com.nuguna.print.helper.NugunaNativePrintPlugin;
import com.nuguna.print.helper.PhoneHelperPlugin;
import com.nuguna.print.helper.SharedInbox;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        /* 폰 안의 프린터 도우미(같은 와이파이 검색 · 9100 전송) */
        registerPlugin(PhoneHelperPlugin.class);
        /* USB OTG 인쇄 · 파일 저장 (화면이 navigator.usb · <a download> 대신 쓴다) */
        registerPlugin(NugunaNativePrintPlugin.class);
        super.onCreate(savedInstanceState);

        /* 뒤로가기: 화면(window.__nugunaBack)에 먼저 묻는다 — 열린 창 닫기 → 이전 화면 → 홈에서는 두 번 눌러 종료.
           화면이 false(또는 답 없음)면 안드로이드 기본 동작으로 앱을 닫는다. */
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView web = getBridge() == null ? null : getBridge().getWebView();
                if (web == null) {
                    systemBack(this);
                    return;
                }
                web.evaluateJavascript(
                    "(function(){try{return !!(window.__nugunaBack && window.__nugunaBack());}catch(e){return false;}})()",
                    value -> {
                        if (!"true".equals(value)) systemBack(this);
                    });
            }
        });

        /* 다른 앱에서 '공유 → 누구나프린트'로 열렸으면 파일을 받아 둔다 (화면이 뜨면 스스로 가져간다) */
        SharedInbox.take(this, getIntent());
        /* 프린터 연결 링크로 열렸으면 받아 둔다 (화면이 뜨면 takeLink 로 가져간다) */
        LinkInbox.take(getIntent());
    }

    /** 앱이 이미 켜져 있을 때 공유 · 연결 링크로 다시 열림 → 받아 두고 화면에 알린다 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        WebView web = getBridge() == null ? null : getBridge().getWebView();
        if (LinkInbox.take(intent)) {
            if (web != null) web.evaluateJavascript("(function(){try{window.__nugunaLink && window.__nugunaLink();}catch(e){}})()", null);
            return;
        }
        if (!SharedInbox.take(this, intent)) return;
        if (web != null) web.evaluateJavascript("(function(){try{window.__nugunaShared && window.__nugunaShared();}catch(e){}})()", null);
    }

    /** 이 콜백을 잠깐 끄고 기본 뒤로가기(앱 닫기 · 뒤로 보내기)를 실행 */
    private void systemBack(OnBackPressedCallback callback) {
        callback.setEnabled(false);
        getOnBackPressedDispatcher().onBackPressed();
        callback.setEnabled(true);
    }
}
