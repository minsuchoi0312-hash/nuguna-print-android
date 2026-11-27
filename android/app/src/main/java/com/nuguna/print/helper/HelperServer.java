package com.nuguna.print.helper;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 폰 안의 "PC 도우미" — PC용 raw-print-helper.mjs 와 같은 주소 · 같은 응답.
 * 127.0.0.1 에서만 듣고, 연결 코드(앱만 앎)가 없으면 인쇄 · 검색을 거절한다.
 *   GET  /ping /info /discover /status /probe /wake
 *   POST /print?host=&port=9100   → 프린터 9100 으로 그대로 흘려 보낸다
 *   POST /ipp?host=&path=/ipp/print → 프린터 631 로 전달 (WebView의 CORS · 바이너리 손상 우회)
 */
public final class HelperServer {
    public static final int PORT = 47631;
    private static final String TAG = "NugunaHelper";
    private static final long MAX_BYTES = 120L * 1024 * 1024;
    private static final long MAX_IPP_BYTES = 48L * 1024 * 1024;
    /* 공유 파일은 통째로 메모리에 담아 넘기므로 낮게 — 120MB를 담으면 앱이 메모리 부족으로 꺼질 수 있다 */
    private static final long MAX_SHARED_BYTES = 48L * 1024 * 1024;
    /* 동시에 처리하는 요청 수 상한 — 다른 앱이 연결을 잔뜩 열어도 앱이 멈추지 않게 (넘치면 바로 끊는다) */
    private static final int MAX_CLIENTS = 24;

    private final Context ctx;
    private final String code;
    /* 서버 루프 · 포트 재시도용 */
    private final ExecutorService pool = Executors.newCachedThreadPool();
    /* 요청 처리용 — 크기가 정해진 풀 */
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, MAX_CLIENTS, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
    private final ArrayDeque<Long> fails = new ArrayDeque<>();
    private ServerSocket server;

    public HelperServer(Context ctx, String code) {
        this.ctx = ctx.getApplicationContext();
        this.code = code;
        Net.init(this.ctx);
        NativePdf.cleanStale(this.ctx);
    }

    private volatile boolean retrying;

    public synchronized void start() {
        if (server != null && !server.isClosed()) return;
        if (!bind()) {
            /* 앱을 닫았다 바로 열면 이전 프로세스의 포트가 잠깐 남아 있다 → 15초 동안 다시 시도 */
            if (retrying) return;
            retrying = true;
            pool.execute(() -> {
                try {
                    for (int i = 0; i < 30; i++) {
                        Thread.sleep(500);
                        synchronized (HelperServer.this) {
                            if (server != null && !server.isClosed()) return;
                            if (bind()) {
                                serve(server);
                                return;
                            }
                        }
                    }
                    Log.w(TAG, "helper port still busy");
                } catch (InterruptedException ignored) {
                } finally {
                    retrying = false;
                }
            });
            return;
        }
        serve(server);
    }

    private boolean bind() {
        ServerSocket s = null;
        try {
            s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 16);
            server = s;
            return true;
        } catch (IOException e) {
            Net.closeQuietly(s);
            Log.w(TAG, "helper port busy: " + e.getMessage());
            return false;
        }
    }

    private void serve(final ServerSocket s) {
        pool.execute(() -> {
            while (!s.isClosed()) {
                try {
                    final Socket client = s.accept();
                    try {
                        workers.execute(() -> handle(client));
                    } catch (RejectedExecutionException full) {
                        Net.closeQuietly(client);
                    }
                } catch (IOException e) {
                    if (s.isClosed()) return;
                }
            }
        });
    }

    public synchronized void stop() {
        Net.closeQuietly(server);
        server = null;
    }

    /* ───────── 요청 처리 ───────── */
    private static final class Req {
        String method = "", path = "";
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        long length;
        InputStream body;
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(60_000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            Req req = readHead(in);
            if (req == null) return;
            origin.set(req.headers.get("origin"));
            try {
                route(req, out);
            } catch (Exception e) {
                /* 내부 예외 문구는 밖으로 내보내지 않는다 */
                Log.w(TAG, "request failed: " + e.getClass().getSimpleName()); // 주소 · 파일 이름이 로그에 남지 않게
                send(out, 502, err("helper error"));
            }
        } catch (Exception ignored) {
        } finally {
            Net.closeQuietly(client);
        }
    }

    private static Req readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (head.size() < 16384) {
            int c = in.read();
            if (c < 0) return null;
            head.write(c);
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) break;
        }
        if (state != 4) return null;
        String[] lines = head.toString("ISO-8859-1").split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 2) return null;
        Req r = new Req();
        r.method = first[0].toUpperCase(Locale.US);
        String target = first[1];
        int q = target.indexOf('?');
        r.path = q < 0 ? target : target.substring(0, q);
        if (q >= 0) {
            for (String pair : target.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                r.query.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            }
        }
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) r.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US), lines[i].substring(colon + 1).trim());
        }
        try {
            r.length = Long.parseLong(r.headers.containsKey("content-length") ? r.headers.get("content-length") : "0");
        } catch (NumberFormatException e) {
            r.length = 0;
        }
        r.body = in;
        return r;
    }

    private void route(Req req, OutputStream out) throws Exception {
        if (req.method.equals("OPTIONS")) {
            send(out, 204, null);
            return;
        }
        if (req.path.equals("/ping")) {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("name", "nuguna-print-helper");
            o.put("version", 3);
            o.put("needsCode", true);
            o.put("discover", true);
            o.put("phone", true);
            send(out, 200, o);
            return;
        }
        if (!codeOk(req.headers.get("x-helper-code"))) {
            send(out, fails.size() >= 10 ? 429 : 401, err("code required"));
            return;
        }
        switch (req.path) {
            case "/check": {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                send(out, 200, o);
                return;
            }
            case "/info": {
                send(out, 200, info());
                return;
            }
            case "/progress": {
                send(out, 200, Discovery.progress());
                return;
            }
            case "/shared": {
                send(out, 200, SharedInbox.list());
                return;
            }
            case "/shared/file": {
                sharedFile(req, out);
                return;
            }
            /* 내장 PDF 엔진(pdfium)으로 쪽 그리기: 글꼴이 안 들어간 한글 PDF도 제대로 */
            case "/pdf/open": {
                if (!req.method.equals("POST")) break;
                if (req.length <= 0 || req.length > NativePdf.MAX_BYTES) {
                    send(out, req.length <= 0 ? 400 : 413, err(req.length <= 0 ? "empty" : "payload too large"));
                    return;
                }
                JSONObject opened = NativePdf.open(ctx, req.body, req.length);
                if (opened == null) send(out, 422, err("cannot open pdf"));
                else send(out, 200, opened);
                return;
            }
            case "/pdf/page": {
                int index, width, height;
                try {
                    index = Integer.parseInt(val(req, "i"));
                    width = Integer.parseInt(val(req, "w"));
                    height = Integer.parseInt(val(req, "h"));
                } catch (NumberFormatException e) {
                    send(out, 400, err("bad size"));
                    return;
                }
                byte[] png = NativePdf.page(val(req, "id"), index, width, height);
                if (png == null) send(out, 404, err("no page"));
                else sendRaw(out, 200, "image/png", png);
                return;
            }
            case "/pdf/close": {
                NativePdf.close(val(req, "id"));
                JSONObject o = new JSONObject();
                o.put("ok", true);
                send(out, 200, o);
                return;
            }
            case "/discover": {
                String subnet = val(req, "subnet").trim();
                if (!subnet.isEmpty() && (!subnet.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$") || !Net.isPrivate(subnet + ".1"))) {
                    send(out, 400, err("subnet not allowed"));
                    return;
                }
                send(out, 200, Discovery.discover(ctx, subnet, !"0".equals(val(req, "pjl"))));
                return;
            }
            default:
                break;
        }
        String host = val(req, "host").isEmpty() ? val(req, "ip") : val(req, "host");
        int port = 9100;
        try {
            if (!val(req, "port").isEmpty()) port = Integer.parseInt(val(req, "port"));
        } catch (NumberFormatException ignored) {
        }
        boolean ippPath = req.path.equals("/ipp");
        if (!Net.isPrivate(host) || (!ippPath && (port < 9100 || port > 9102) && port != Bjnp.PORT)) {
            send(out, 400, err("target not allowed"));
            return;
        }
        switch (req.path) {
            case "/status": {
                JSONObject st = Status.check(host, !"0".equals(val(req, "pjl")));
                /* 앱에서 연결한 9100 프린터는 시스템 인쇄(설정 → 인쇄 → 누구나프린트)에도 바로 보이게 */
                JSONObject caps = st.optJSONObject("capabilities");
                if (caps != null && caps.optBoolean("raw9100")) com.nuguna.print.printservice.KnownPrinters.remember(ctx, host, st.optString("model"));
                send(out, 200, st);
                return;
            }
            case "/probe": {
                long t = System.currentTimeMillis();
                boolean open = Net.portOpen(host, port, 800);
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("host", host);
                o.put("port", port);
                o.put("open", open);
                o.put("ms", open ? System.currentTimeMillis() - t : JSONObject.NULL);
                send(out, 200, o);
                return;
            }
            case "/wake": {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("open", Net.portOpen(host, port, 1500));
                send(out, 200, o);
                return;
            }
            case "/print":
                if (!req.method.equals("POST")) break;
                print(req, out, host, port);
                return;
            case "/ipp":
                if (!req.method.equals("POST")) break;
                ipp(req, out, host);
                return;
            default:
                break;
        }
        send(out, 404, err("not found"));
    }

    /** 받은 만큼 바로 프린터 9100 으로 흘려 보낸다 (큰 작업도 메모리에 다 담지 않음) */
    private void print(Req req, OutputStream out, String host, int port) throws Exception {
        if (req.length <= 0) {
            send(out, 400, err("empty"));
            return;
        }
        if (req.length > MAX_BYTES) {
            JSONObject o = err("payload too large");
            o.put("maxBytes", MAX_BYTES);
            send(out, 413, o);
            return;
        }
        /* 캐논 PIXMA: 9100 대신 BJNP(8611) */
        if (port == Bjnp.PORT) {
            long sentBjnp;
            try {
                sentBjnp = Bjnp.print(host, req.body, req.length, "nuguna");
            } catch (IOException e) {
                send(out, 502, err("printer not reachable"));
                return;
            }
            JSONObject done = new JSONObject();
            done.put("ok", sentBjnp == req.length);
            done.put("bytes", sentBjnp);
            send(out, sentBjnp == req.length ? 200 : 502, done);
            return;
        }
        Socket printer;
        try {
            printer = Net.connect(host, port, 4000);
        } catch (IOException e) {
            send(out, 502, err("printer not reachable"));
            return;
        }
        long sent = 0;
        try {
            printer.setSoTimeout(60_000);
            OutputStream po = printer.getOutputStream();
            byte[] buf = new byte[65536];
            while (sent < req.length) {
                int n = req.body.read(buf, 0, (int) Math.min(buf.length, req.length - sent));
                if (n < 0) break;
                po.write(buf, 0, n);
                sent += n;
            }
            po.flush();
            printer.shutdownOutput();
        } finally {
            Net.closeQuietly(printer);
        }
        if (sent == req.length) com.nuguna.print.printservice.KnownPrinters.remember(ctx, host, "");
        JSONObject o = new JSONObject();
        o.put("ok", sent == req.length);
        o.put("bytes", sent);
        send(out, sent == req.length ? 200 : 502, o);
    }

    /** 공유로 받은 파일 하나를 그대로 흘려 보낸다 (크기 상한 MAX_SHARED_BYTES) */
    private void sharedFile(Req req, OutputStream out) throws Exception {
        int b, i;
        try {
            b = Integer.parseInt(val(req, "batch"));
            i = Integer.parseInt(val(req, "i"));
        } catch (NumberFormatException e) {
            send(out, 400, err("bad index"));
            return;
        }
        SharedInbox.Item item = SharedInbox.get(b, i);
        if (item == null) {
            send(out, 404, err("not found"));
            return;
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = ctx.getContentResolver().openInputStream(item.uri)) {
            if (in == null) throw new IOException("no stream");
            byte[] chunk = new byte[65536];
            int n;
            while ((n = in.read(chunk)) > 0) {
                if (buf.size() + n > MAX_SHARED_BYTES) {
                    send(out, 413, err("payload too large"));
                    return;
                }
                buf.write(chunk, 0, n);
            }
        } catch (SecurityException | IOException e) {
            send(out, 410, err("file not readable"));
            return;
        }
        sendRaw(out, 200, item.mime, buf.toByteArray());
    }

    /** IPP 요청을 프린터 631 로 전달하고 답을 그대로 돌려준다 */
    private void ipp(Req req, OutputStream out, String host) throws Exception {
        /* IPP 는 답을 받으려고 통째로 메모리에 담으니 9100 보다 낮게 제한 (메모리 부족으로 앱이 꺼지지 않게) */
        if (req.length <= 0 || req.length > MAX_IPP_BYTES) {
            send(out, req.length <= 0 ? 400 : 413, err(req.length <= 0 ? "empty" : "payload too large"));
            return;
        }
        String path = val(req, "path").isEmpty() ? "/ipp/print" : val(req, "path");
        if (!path.matches("^/[A-Za-z0-9/_.-]{0,60}$")) {
            send(out, 400, err("bad path"));
            return;
        }
        byte[] body = new byte[(int) req.length];
        int got = 0;
        while (got < body.length) {
            int n = req.body.read(body, got, body.length - got);
            if (n < 0) break;
            got += n;
        }
        Probe.HttpReply reply;
        try {
            reply = Probe.http(host, 631, "POST", path, "application/ipp", body, 120_000, 4 << 20);
        } catch (IOException e) {
            send(out, 502, err("printer not reachable"));
            return;
        }
        sendRaw(out, reply.status, "application/ipp", reply.body);
    }

    private JSONObject info() throws Exception {
        List<Net.Local> locals = Net.locals();
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("name", "nuguna-print-helper");
        o.put("phone", true);
        o.put("version", 3);
        o.put("recommended", "http://127.0.0.1:" + PORT);
        JSONArray addresses = new JSONArray();
        JSONArray subnets = new JSONArray();
        for (Net.Local l : locals) {
            JSONObject a = new JSONObject();
            a.put("iface", l.iface);
            a.put("address", l.address);
            a.put("url", "http://127.0.0.1:" + PORT);
            addresses.put(a);
            subnets.put(l.subnet24());
        }
        o.put("addresses", addresses);
        o.put("subnets", subnets);
        return o;
    }

    /* ───────── 보안 · 응답 ───────── */
    /*
     * 코드는 96비트 난수라 맞혀 볼 수 없다. 틀린 시도가 많아도 맞는 코드는 늘 통과시킨다
     * (예전처럼 막으면 폰의 다른 앱 · 웹페이지가 틀린 코드만 보내 앱 인쇄를 멈출 수 있었다).
     */
    private synchronized boolean codeOk(String given) {
        long now = System.currentTimeMillis();
        while (!fails.isEmpty() && now - fails.peekFirst() > 60_000) fails.pollFirst();
        boolean ok = given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
        if (!ok && fails.size() < 100) fails.addLast(now);
        return ok;
    }

    private static String val(Req r, String key) {
        String v = r.query.get(key);
        return v == null ? "" : v;
    }

    private static JSONObject err(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", message);
        } catch (Exception ignored) {
        }
        return o;
    }

    /*
     * 응답은 앱 화면(WebView, http://localhost)만 읽게 한다. 폰 브라우저의 다른 웹사이트는
     * 요청을 보내도 답을 읽지 못한다. (Origin 없는 요청은 연결 코드로 막는다)
     */
    private static final ThreadLocal<String> origin = new ThreadLocal<>();
    private static final java.util.Set<String> APP_ORIGINS = new java.util.HashSet<>(java.util.Arrays.asList(
        "http://localhost", "https://localhost", "capacitor://localhost"));

    private static String allowOrigin() {
        String o = origin.get();
        return "Access-Control-Allow-Origin: " + (o != null && APP_ORIGINS.contains(o) ? o : "http://localhost") + "\r\nVary: Origin\r\n";
    }

    private static final String CORS = "Access-Control-Allow-Private-Network: true\r\n"
        + "Access-Control-Allow-Headers: Content-Type, X-Helper-Code, Access-Control-Request-Private-Network\r\n"
        + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
        + "Access-Control-Max-Age: 600\r\n";

    private static void send(OutputStream out, int status, JSONObject body) throws IOException {
        sendRaw(out, status, "application/json", body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void sendRaw(OutputStream out, int status, String type, byte[] body) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        head.append(allowOrigin()).append(CORS);
        if (body != null) {
            head.append("Content-Type: ").append(type).append("\r\n");
            head.append("Content-Length: ").append(body.length).append("\r\n");
        } else head.append("Content-Length: 0\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body != null) out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            default: return status >= 500 ? "Bad Gateway" : "Status";
        }
    }
}
