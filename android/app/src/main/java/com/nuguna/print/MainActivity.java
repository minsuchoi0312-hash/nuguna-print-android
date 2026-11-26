package com.nuguna.print;

import android.os.Bundle;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;
import com.nuguna.print.helper.PhoneHelperPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        /* 폰 안의 프린터 도우미(같은 와이파이 검색 · 9100 전송) */
        registerPlugin(PhoneHelperPlugin.class);
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
    }

    /** 이 콜백을 잠깐 끄고 기본 뒤로가기(앱 닫기 · 뒤로 보내기)를 실행 */
    private void systemBack(OnBackPressedCallback callback) {
        callback.setEnabled(false);
        getOnBackPressedDispatcher().onBackPressed();
        callback.setEnabled(true);
    }
}
