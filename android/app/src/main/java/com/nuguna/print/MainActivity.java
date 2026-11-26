package com.nuguna.print;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;
import com.nuguna.print.helper.PhoneHelperPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        /* 폰 안의 프린터 도우미(같은 와이파이 검색 · 9100 전송) */
        registerPlugin(PhoneHelperPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
