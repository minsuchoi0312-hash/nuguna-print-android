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

    private final Context ctx;
    private final String code;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ArrayDeque<Long> fails = new ArrayDeque<>();
    private ServerSocket server;

    public HelperServer(Context ctx, String code) {
        this.ctx = ctx.getApplicationContext();
        this.code = code;
        Net.init(this.ctx);
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
                    pool.execute(() -> handle(client));
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
            try {
                route(req, out);
            } catch (Exception e) {
                send(out, 502, err(String.valueOf(e.getMessage())));
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
            case "/status":
                send(out, 200, Status.check(host, !"0".equals(val(req, "pjl"))));
                return;
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
        JSONObject o = new JSONObject();
        o.put("ok", sent == req.length);
        o.put("bytes", sent);
        send(out, sent == req.length ? 200 : 502, o);
    }

    /** IPP 요청을 프린터 631 로 전달하고 답을 그대로 돌려준다 */
    private void ipp(Req req, OutputStream out, String host) throws Exception {
        if (req.length <= 0 || req.length > MAX_BYTES) {
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
    private synchronized boolean codeOk(String given) {
        long now = System.currentTimeMillis();
        while (!fails.isEmpty() && now - fails.peekFirst() > 60_000) fails.pollFirst();
        if (fails.size() >= 10) return false;
        boolean ok = given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
        if (!ok) fails.addLast(now);
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

    private static final String CORS = "Access-Control-Allow-Origin: *\r\n"
        + "Access-Control-Allow-Private-Network: true\r\n"
        + "Access-Control-Allow-Headers: Content-Type, X-Helper-Code, Access-Control-Request-Private-Network\r\n"
        + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
        + "Access-Control-Max-Age: 600\r\n";

    private static void send(OutputStream out, int status, JSONObject body) throws IOException {
        sendRaw(out, status, "application/json", body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void sendRaw(OutputStream out, int status, String type, byte[] body) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        head.append(CORS);
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
