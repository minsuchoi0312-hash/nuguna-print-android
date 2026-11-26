package com.nuguna.print.helper;

import android.content.SharedPreferences;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.security.SecureRandom;

/** 화면(웹)에 내장 도우미 주소와 연결 코드를 알려 준다. 코드는 기기에 저장해 계속 같은 값을 쓴다. */
@CapacitorPlugin(name = "PhoneHelper")
public class PhoneHelperPlugin extends Plugin {
    private HelperServer server;
    private String code;

    @Override
    public void load() {
        SharedPreferences prefs = getContext().getSharedPreferences("nuguna-helper", 0);
        code = prefs.getString("code", "");
        if (code.length() < 16) {
            byte[] raw = new byte[12];
            new SecureRandom().nextBytes(raw);
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            code = sb.toString();
            prefs.edit().putString("code", code).apply();
        }
        server = new HelperServer(getContext(), code);
        server.start();
    }

    @Override
    protected void handleOnResume() {
        if (server != null) server.start();
    }

    @Override
    protected void handleOnDestroy() {
        if (server != null) server.stop();
    }

    @PluginMethod
    public void info(PluginCall call) {
        if (server != null) server.start();
        JSObject ret = new JSObject();
        ret.put("url", "http://127.0.0.1:" + HelperServer.PORT);
        ret.put("code", code);
        call.resolve(ret);
    }
}
