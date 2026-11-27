package com.nuguna.print.helper;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 앱 전용 네이티브 기능 — 화면(android-main.tsx)이 부른다.
 *
 * 1) USB OTG 인쇄: 안드로이드 WebView에는 WebUSB가 없어서 UsbManager로 같은 일을 한다.
 *    화면은 이 메서드들로 navigator.usb 를 흉내 내므로(src/android-main.tsx) 웹의 USB 인쇄 코드(discover.ts)를 그대로 쓴다.
 * 2) 파일 저장: WebView는 <a download> 를 무시하므로 PDF·텍스트를 '다운로드/NugunaPrint'에 저장한다
 *    (안드로이드 10 미만은 저장 권한 대신 공유 창으로 넘긴다).
 *
 * 와이파이(9100 · IPP · 검색 · 상태)는 이미 내장 도우미(HelperServer, 127.0.0.1:47631)가 맡는다.
 */
@CapacitorPlugin(name = "NugunaNativePrint")
public class NugunaNativePrintPlugin extends Plugin {
    private static final String ACTION_PERMISSION = "com.nuguna.print.USB_PERMISSION";
    private static final int CHUNK = 16 * 1024;
    private static final int OUT_TIMEOUT_MS = 15000;

    private UsbManager usb;
    private final Map<String, Link> links = new HashMap<>();
    private final Map<String, PluginCall> waiting = new HashMap<>();
    private BroadcastReceiver receiver;

    /** 열린 USB 연결 하나: 잡은 인터페이스를 기억해 두고 닫을 때 놓는다 */
    private static final class Link {
        final UsbDevice device;
        final UsbDeviceConnection conn;
        final Set<Integer> claimed = new HashSet<>();
        final Map<Integer, Integer> alt = new HashMap<>();

        Link(UsbDevice device, UsbDeviceConnection conn) {
            this.device = device;
            this.conn = conn;
        }
    }

    /** FileProvider 로 내주는 유일한 폴더(res/xml/file_paths.xml 의 share/) */
    private static final String SHARE_DIR = "share";

    /** 지난 실행에서 공유 창으로 넘긴 임시 파일을 지운다(받는 앱이 이미 읽어 갔을 시점) */
    private void cleanShare() {
        File[] files = new File(getContext().getCacheDir(), SHARE_DIR).listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.delete()) f.deleteOnExit();
        }
    }

    @Override
    public void load() {
        cleanShare();
        usb = (UsbManager) getContext().getSystemService(Context.USB_SERVICE);
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                UsbDevice device = deviceFrom(intent);
                String action = intent.getAction();
                if (device == null || action == null) return;
                if (ACTION_PERMISSION.equals(action)) {
                    PluginCall call = waiting.remove(device.getDeviceName());
                    if (call != null) {
                        JSObject ret = new JSObject();
                        ret.put("granted", usb.hasPermission(device));
                        ret.put("device", describe(device));
                        call.resolve(ret);
                    }
                    return;
                }
                boolean attached = UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action);
                if (!attached) closeLink(device.getDeviceName());
                JSObject ev = new JSObject();
                ev.put("type", attached ? "connect" : "disconnect");
                ev.put("device", describe(device));
                notifyListeners("usb", ev);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        /* 권한 응답은 우리 앱 이름으로 온 PendingIntent, 꽂기 · 뽑기는 시스템 방송 — 둘 다 외부 앱이 보낼 수 없게 NOT_EXPORTED */
        ContextCompat.registerReceiver(getContext(), receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void handleOnDestroy() {
        for (String id : new HashSet<>(links.keySet())) closeLink(id);
        try {
            getContext().unregisterReceiver(receiver);
        } catch (Exception ignored) {
            /* 이미 풀림 */
        }
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice deviceFrom(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private UsbDevice find(String id) {
        if (id == null) return null;
        for (UsbDevice d : usb.getDeviceList().values()) if (id.equals(d.getDeviceName())) return d;
        return null;
    }

    /** WebUSB 장치 모양과 같은 필드로 장치를 적는다 (configurations → interfaces → alternates → endpoints) */
    private JSObject describe(UsbDevice d) {
        JSObject o = new JSObject();
        boolean allowed = usb != null && usb.hasPermission(d);
        o.put("id", d.getDeviceName());
        o.put("vendorId", d.getVendorId());
        o.put("productId", d.getProductId());
        o.put("deviceClass", d.getDeviceClass());
        o.put("permission", allowed);
        o.put("productName", safe(d::getProductName));
        o.put("manufacturerName", safe(d::getManufacturerName));
        /* 일련번호는 권한이 있어야 읽힌다(안드로이드 10+) */
        o.put("serialNumber", allowed ? safe(d::getSerialNumber) : "");
        JSArray configs = new JSArray();
        for (int c = 0; c < d.getConfigurationCount(); c++) {
            UsbConfiguration cfg = d.getConfiguration(c);
            Map<Integer, JSArray> alts = new HashMap<>();
            for (int i = 0; i < cfg.getInterfaceCount(); i++) {
                UsbInterface intf = cfg.getInterface(i);
                JSObject alt = new JSObject();
                alt.put("alternateSetting", intf.getAlternateSetting());
                alt.put("interfaceClass", intf.getInterfaceClass());
                alt.put("interfaceSubclass", intf.getInterfaceSubclass());
                alt.put("interfaceProtocol", intf.getInterfaceProtocol());
                JSArray eps = new JSArray();
                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint ep = intf.getEndpoint(e);
                    JSObject eo = new JSObject();
                    eo.put("endpointNumber", ep.getEndpointNumber());
                    eo.put("direction", ep.getDirection() == UsbConstants.USB_DIR_IN ? "in" : "out");
                    int t = ep.getType();
                    eo.put("type", t == UsbConstants.USB_ENDPOINT_XFER_BULK ? "bulk" : t == UsbConstants.USB_ENDPOINT_XFER_INT ? "interrupt" : t == UsbConstants.USB_ENDPOINT_XFER_ISOC ? "isochronous" : "control");
                    eps.put(eo);
                }
                alt.put("endpoints", eps);
                JSArray list = alts.get(intf.getId());
                if (list == null) {
                    list = new JSArray();
                    alts.put(intf.getId(), list);
                }
                list.put(alt);
            }
            JSArray ifaces = new JSArray();
            for (Map.Entry<Integer, JSArray> entry : alts.entrySet()) {
                JSObject io = new JSObject();
                io.put("interfaceNumber", entry.getKey());
                io.put("alternates", entry.getValue());
                ifaces.put(io);
            }
            JSObject co = new JSObject();
            co.put("configurationValue", cfg.getId());
            co.put("interfaces", ifaces);
            configs.put(co);
        }
        o.put("configurations", configs);
        return o;
    }

    private interface Getter {
        String get();
    }

    private static String safe(Getter g) {
        try {
            String v = g.get();
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    /* ───── USB ───── */

    @PluginMethod
    public void usbList(PluginCall call) {
        JSArray list = new JSArray();
        if (usb != null) for (UsbDevice d : usb.getDeviceList().values()) list.put(describe(d));
        JSObject ret = new JSObject();
        ret.put("devices", list);
        call.resolve(ret);
    }

    @PluginMethod
    public void usbRequestPermission(PluginCall call) {
        UsbDevice d = find(call.getString("id"));
        if (d == null) {
            call.reject("USB 장치가 빠졌어요.");
            return;
        }
        if (usb.hasPermission(d)) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            ret.put("device", describe(d));
            call.resolve(ret);
            return;
        }
        PluginCall old = waiting.put(d.getDeviceName(), call);
        if (old != null) old.reject("다시 요청했어요.");
        Intent intent = new Intent(ACTION_PERMISSION).setPackage(getContext().getPackageName());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(getContext(), 0, intent, flags | PendingIntent.FLAG_UPDATE_CURRENT);
        usb.requestPermission(d, pi);
    }

    @PluginMethod
    public void usbOpen(PluginCall call) {
        String id = call.getString("id");
        UsbDevice d = find(id);
        if (d == null || !usb.hasPermission(d)) {
            call.reject("USB 권한이 없어요.");
            return;
        }
        if (!links.containsKey(id)) {
            UsbDeviceConnection conn = usb.openDevice(d);
            if (conn == null) {
                call.reject("USB 장치를 열지 못했어요.");
                return;
            }
            links.put(id, new Link(d, conn));
        }
        call.resolve();
    }

    @PluginMethod
    public void usbClose(PluginCall call) {
        closeLink(call.getString("id"));
        call.resolve();
    }

    private void closeLink(String id) {
        Link link = id == null ? null : links.remove(id);
        if (link == null) return;
        for (int n : link.claimed) {
            UsbInterface intf = iface(link, n, 0);
            if (intf != null) link.conn.releaseInterface(intf);
        }
        link.conn.close();
    }

    private static UsbInterface iface(Link link, int number, int alt) {
        for (int c = 0; c < link.device.getConfigurationCount(); c++) {
            UsbConfiguration cfg = link.device.getConfiguration(c);
            for (int i = 0; i < cfg.getInterfaceCount(); i++) {
                UsbInterface intf = cfg.getInterface(i);
                if (intf.getId() == number && intf.getAlternateSetting() == alt) return intf;
            }
        }
        return null;
    }

    private Link link(PluginCall call) {
        Link link = links.get(call.getString("id"));
        if (link == null) call.reject("USB 연결이 닫혀 있어요.");
        return link;
    }

    @PluginMethod
    public void usbClaim(PluginCall call) {
        Link link = link(call);
        if (link == null) return;
        int n = call.getInt("iface", 0);
        UsbInterface intf = iface(link, n, 0);
        if (intf == null || !link.conn.claimInterface(intf, true)) {
            call.reject("USB를 다른 앱이 쓰고 있어요.");
            return;
        }
        link.claimed.add(n);
        call.resolve();
    }

    @PluginMethod
    public void usbRelease(PluginCall call) {
        Link link = link(call);
        if (link == null) return;
        int n = call.getInt("iface", 0);
        UsbInterface intf = iface(link, n, 0);
        if (intf != null) link.conn.releaseInterface(intf);
        link.claimed.remove(n);
        call.resolve();
    }

    @PluginMethod
    public void usbSelectAlternate(PluginCall call) {
        Link link = link(call);
        if (link == null) return;
        int n = call.getInt("iface", 0);
        int alt = call.getInt("alt", 0);
        UsbInterface intf = iface(link, n, alt);
        if (intf == null || !link.conn.setInterface(intf)) {
            call.reject("USB 설정을 바꾸지 못했어요.");
            return;
        }
        link.alt.put(n, alt);
        call.resolve();
    }

    /** 장치에서 읽기(IEEE 1284 GET_DEVICE_ID 등). requestType/recipient 는 WebUSB 이름 그대로 */
    @PluginMethod
    public void usbControlIn(PluginCall call) {
        Link link = link(call);
        if (link == null) return;
        String type = call.getString("requestType", "standard");
        String to = call.getString("recipient", "device");
        int bm = 0x80 | ("class".equals(type) ? 0x20 : "vendor".equals(type) ? 0x40 : 0)
            | ("interface".equals(to) ? 1 : "endpoint".equals(to) ? 2 : "other".equals(to) ? 3 : 0);
        int length = Math.max(0, Math.min(4096, call.getInt("length", 0)));
        byte[] buf = new byte[length];
        int n = link.conn.controlTransfer(bm, call.getInt("request", 0), call.getInt("value", 0), call.getInt("index", 0), buf, length, 5000);
        JSObject ret = new JSObject();
        if (n < 0) {
            ret.put("status", "stall");
            ret.put("data", "");
        } else {
            ret.put("status", "ok");
            ret.put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP));
        }
        call.resolve(ret);
    }

    private UsbEndpoint outEndpoint(Link link, int number) {
        for (int n : link.claimed) {
            Integer alt = link.alt.get(n);
            UsbInterface intf = iface(link, n, alt == null ? 0 : alt);
            if (intf == null) continue;
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT && ep.getEndpointNumber() == number) return ep;
            }
        }
        return null;
    }

    /** 엔드포인트 멈춤 풀기 (CLEAR_FEATURE ENDPOINT_HALT) */
    @PluginMethod
    public void usbClearHalt(PluginCall call) {
        Link link = link(call);
        if (link == null) return;
        int number = call.getInt("endpoint", 0);
        int address = ("in".equals(call.getString("direction")) ? 0x80 : 0) | (number & 0x0f);
        int n = link.conn.controlTransfer(0x02, 0x01, 0, address, null, 0, 2000);
        if (n < 0) call.reject("USB 멈춤을 풀지 못했어요.");
        else call.resolve();
    }

    /** 복사기로 보내기 (Bulk OUT). 데이터는 base64, 한 번에 16KB씩 */
    @PluginMethod
    public void usbTransferOut(PluginCall call) {
        Link link = link(call);
        if (link == null) return;
        UsbEndpoint ep = outEndpoint(link, call.getInt("endpoint", 0));
        if (ep == null) {
            call.reject("USB 보내는 길을 찾지 못했어요.");
            return;
        }
        byte[] data;
        try {
            data = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            call.reject("보낼 데이터가 깨졌어요.");
            return;
        }
        int sent = 0;
        while (sent < data.length) {
            int len = Math.min(CHUNK, data.length - sent);
            int n = link.conn.bulkTransfer(ep, data, sent, len, OUT_TIMEOUT_MS);
            if (n <= 0) break;
            sent += n;
        }
        JSObject ret = new JSObject();
        ret.put("status", sent > 0 || data.length == 0 ? "ok" : "stall");
        ret.put("bytesWritten", sent);
        call.resolve(ret);
    }

    /* ───── QR 찍기 · 링크 공유 · 연결 링크 ───── */

    /** 구글 Play 서비스 코드 스캐너로 QR 하나 읽기. 카메라 화면은 Play 서비스가 띄우고 앱은 글자만 받는다(카메라 권한 없음).
     *  { text } · 닫으면 { canceled: true } · Play 서비스가 없거나 모듈을 못 받으면 reject */
    @PluginMethod
    public void scanQr(PluginCall call) {
        if (getActivity() == null) {
            call.reject("no activity");
            return;
        }
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build();
        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(getActivity(), options);
        scanner.startScan()
            .addOnSuccessListener(code -> {
                JSObject ret = new JSObject();
                String text = code.getRawValue();
                ret.put("text", text == null ? "" : (text.length() > 4000 ? text.substring(0, 4000) : text));
                call.resolve(ret);
            })
            .addOnCanceledListener(() -> {
                JSObject ret = new JSObject();
                ret.put("canceled", true);
                call.resolve(ret);
            })
            .addOnFailureListener(e -> call.reject("scan failed"));
    }

    /** 안드로이드 공유 창으로 글(링크) 보내기 — 카톡 · 문자 · 메일 등 */
    @PluginMethod
    public void shareText(PluginCall call) {
        String text = call.getString("text", "");
        String title = call.getString("title", "");
        if (text == null || text.isEmpty() || text.length() > 4000) {
            call.reject("bad text");
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        if (title != null && !title.isEmpty()) send.putExtra(Intent.EXTRA_SUBJECT, title);
        Intent chooser = Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(chooser);
        call.resolve();
    }

    /** 앱을 연 프린터 연결 링크(nugunaprint://connect?…)를 한 번 꺼내 간다. 없으면 { link: "" } */
    @PluginMethod
    public void takeLink(PluginCall call) {
        String link = LinkInbox.takePending();
        JSObject ret = new JSObject();
        ret.put("link", link == null ? "" : link);
        call.resolve(ret);
    }

    /* ───── 파일 저장 ───── */

    /** PDF · 텍스트를 '다운로드/NugunaPrint'에 저장 (안드로이드 10+). 그보다 오래된 폰은 공유 창으로 넘긴다. */
    @PluginMethod
    public void saveFile(PluginCall call) {
        String name = cleanName(call.getString("name", "nuguna-print"));
        String mime = call.getString("mime", "application/octet-stream");
        byte[] data;
        try {
            data = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            call.reject("저장할 데이터가 깨졌어요.");
            return;
        }
        try {
            JSObject ret = new JSObject();
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = getContext().getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NugunaPrint");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("insert");
                try (OutputStream out = cr.openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("open");
                    out.write(data);
                }
                v.clear();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, v, null, null);
                ret.put("saved", true);
                ret.put("where", "Download/NugunaPrint/" + name);
                toast(getContext().getString(com.nuguna.print.R.string.saved_to, "Download/NugunaPrint"));
            } else {
                File dir = new File(getContext().getCacheDir(), SHARE_DIR);
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdir");
                File file = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(data);
                }
                Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
                Intent send = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(chooser);
                ret.put("saved", false);
                ret.put("shared", true);
            }
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("파일을 저장하지 못했어요.");
        }
    }

    /** 파일 이름: 경로 문자 · 제어 문자를 빼고 길이를 줄인다 */
    private static String cleanName(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (s.isEmpty() || s.startsWith(".")) s = "nuguna-print" + s;
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private void toast(String text) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show());
    }
}
