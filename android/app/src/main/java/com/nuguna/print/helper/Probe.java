package com.nuguna.print.helper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 인쇄하지 않는 조회: IPP Get-Printer-Attributes, PJL INFO, 내장 웹페이지 제목, 그리고 IPP 전달용 작은 HTTP.
 * 안드로이드는 평문 HTTP(HttpURLConnection)를 막으므로 소켓으로 직접 HTTP/1.1을 말한다.
 */
public final class Probe {
    private Probe() {}

    /* ───────── 아주 작은 HTTP/1.1 클라이언트 ───────── */
    public static final class HttpReply {
        public final int status;
        public final String contentType;
        public final byte[] body;

        HttpReply(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }

    public static HttpReply http(String host, int port, String method, String path, String contentType, byte[] body, int timeoutMs, int limit) throws IOException {
        Socket socket = Net.connect(host, port, Math.min(timeoutMs, 4000));
        try {
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(host).append(':').append(port).append("\r\n");
            head.append("Connection: close\r\n");
            head.append("User-Agent: nuguna-print\r\n");
            if (contentType != null) head.append("Content-Type: ").append(contentType).append("\r\n");
            if (body != null) head.append("Content-Length: ").append(body.length).append("\r\n");
            head.append("\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) {
                for (int off = 0; off < body.length; off += 65536) out.write(body, off, Math.min(65536, body.length - off));
            }
            out.flush();
            byte[] raw = Net.readAll(socket.getInputStream(), limit);
            return parseHttp(raw);
        } finally {
            Net.closeQuietly(socket);
        }
    }

    static HttpReply parseHttp(byte[] raw) throws IOException {
        int split = -1;
        for (int i = 0; i + 3 < raw.length; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                split = i;
                break;
            }
        }
        if (split < 0) throw new IOException("bad http reply");
        String head = new String(raw, 0, split, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n");
        String[] first = lines[0].split(" ");
        int status = first.length > 1 ? Integer.parseInt(first[1].trim()) : 0;
        String type = "";
        boolean chunked = false;
        for (String line : lines) {
            String low = line.toLowerCase(Locale.US);
            if (low.startsWith("content-type:")) type = line.substring(13).trim();
            if (low.startsWith("transfer-encoding:") && low.contains("chunked")) chunked = true;
        }
        int start = split + 4;
        byte[] body = new byte[raw.length - start];
        System.arraycopy(raw, start, body, 0, body.length);
        if (chunked) body = dechunk(body);
        return new HttpReply(status, type, body);
    }

    static byte[] dechunk(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < in.length) {
            int eol = pos;
            while (eol + 1 < in.length && !(in[eol] == '\r' && in[eol + 1] == '\n')) eol++;
            String sizeText = new String(in, pos, eol - pos, StandardCharsets.ISO_8859_1).split(";")[0].trim();
            int size;
            try {
                size = Integer.parseInt(sizeText, 16);
            } catch (NumberFormatException e) {
                break;
            }
            pos = eol + 2;
            if (size <= 0 || pos + size > in.length) break;
            out.write(in, pos, size);
            pos += size + 2;
        }
        return out.toByteArray();
    }

    /* ───────── IPP ───────── */
    public static final String[] STATUS_ATTRS = {
        "printer-state", "printer-state-reasons", "marker-names", "marker-levels", "marker-colors", "marker-types",
        "sides-supported", "color-supported", "document-format-supported", "media-ready", "printer-make-and-model"
    };

    private static void attr(ByteArrayOutputStream out, int tag, String name, String value) {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        out.write(tag);
        out.write((n.length >> 8) & 0xff);
        out.write(n.length & 0xff);
        out.write(n, 0, n.length);
        out.write((v.length >> 8) & 0xff);
        out.write(v.length & 0xff);
        out.write(v, 0, v.length);
    }

    public static byte[] ippAttrRequest(String host, String path, String[] attrs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {0x01, 0x01, 0x00, 0x0b, 0, 0, 0, 1, 0x01}, 0, 9);
        attr(out, 0x47, "attributes-charset", "utf-8");
        attr(out, 0x48, "attributes-natural-language", "en");
        attr(out, 0x45, "printer-uri", "ipp://" + host + ":631" + path);
        for (int i = 0; i < attrs.length; i++) attr(out, 0x44, i == 0 ? "requested-attributes" : "", attrs[i]);
        out.write(0x03);
        return out.toByteArray();
    }

    /** IPP 응답 → 이름: 값 목록 (정수 · enum · 불리언 · 문자열). IPP 상태가 오류면 null */
    public static Map<String, List<Object>> parseIpp(byte[] buf) {
        if (buf == null || buf.length < 9) return null;
        int status = ((buf[2] & 0xff) << 8) | (buf[3] & 0xff);
        if (status >= 0x0400) return null;
        Map<String, List<Object>> attrs = new LinkedHashMap<>();
        int pos = 8;
        String last = "";
        while (pos < buf.length) {
            int tag = buf[pos++] & 0xff;
            if (tag == 0x03) break;
            if (tag <= 0x0f) continue;
            if (pos + 2 > buf.length) break;
            int nlen = ((buf[pos] & 0xff) << 8) | (buf[pos + 1] & 0xff);
            pos += 2;
            if (pos + nlen > buf.length) break;
            String name = nlen > 0 ? new String(buf, pos, nlen, StandardCharsets.UTF_8) : last;
            pos += nlen;
            if (pos + 2 > buf.length) break;
            int vlen = ((buf[pos] & 0xff) << 8) | (buf[pos + 1] & 0xff);
            pos += 2;
            if (pos + vlen > buf.length) break;
            Object value;
            if ((tag == 0x21 || tag == 0x23) && vlen == 4) {
                value = ((buf[pos] & 0xff) << 24) | ((buf[pos + 1] & 0xff) << 16) | ((buf[pos + 2] & 0xff) << 8) | (buf[pos + 3] & 0xff);
            } else if (tag == 0x22 && vlen == 1) {
                value = buf[pos] == 1;
            } else {
                value = new String(buf, pos, vlen, StandardCharsets.UTF_8);
            }
            pos += vlen;
            last = name;
            List<Object> list = attrs.get(name);
            if (list == null) {
                list = new ArrayList<>();
                attrs.put(name, list);
            }
            list.add(value);
        }
        return attrs;
    }

    /** /ipp/print → /ipp → / 순서로 Get-Printer-Attributes */
    public static Map<String, List<Object>> ippAttrs(String host, String[] names, int timeoutMs) {
        for (String path : new String[] {"/ipp/print", "/ipp", "/"}) {
            try {
                HttpReply reply = http(host, 631, "POST", path, "application/ipp", ippAttrRequest(host, path, names), timeoutMs, 1 << 20);
                if (reply.status != 200) continue;
                Map<String, List<Object>> attrs = parseIpp(reply.body);
                if (attrs != null) return attrs;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public static String first(Map<String, List<Object>> attrs, String name) {
        if (attrs == null) return "";
        List<Object> list = attrs.get(name);
        return list == null || list.isEmpty() ? "" : String.valueOf(list.get(0));
    }

    /* ───────── PJL (9100, 인쇄 데이터 없음 · UEL로 열고 닫음) ───────── */
    public static String pjlQuery(String host, String[] commands, int timeoutMs) {
        Socket socket = null;
        try {
            socket = Net.connect(host, 9100, Math.min(timeoutMs, 1500));
            socket.setSoTimeout(300);
            StringBuilder cmd = new StringBuilder("\u001b%-12345X@PJL\r\n");
            for (String c : commands) cmd.append(c).append("\r\n");
            cmd.append("\u001b%-12345X");
            socket.getOutputStream().write(cmd.toString().getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            StringBuilder text = new StringBuilder();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try {
                    int n = in.read(buf);
                    if (n < 0) break;
                    text.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
                    int ff = 0;
                    for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\f') ff++;
                    if (ff >= commands.length) break;
                } catch (SocketTimeoutException again) {
                    /* 계속 기다린다 */
                }
            }
            return text.toString();
        } catch (Exception e) {
            return "";
        } finally {
            Net.closeQuietly(socket);
        }
    }

    public static String pjlModel(String host) {
        String text = pjlQuery(host, new String[] {"@PJL INFO ID"}, 1800);
        int at = text.indexOf("INFO ID");
        if (at < 0) return "";
        for (String row : text.substring(at + 7).split("\r?\n")) {
            String t = row.replace("\f", "").trim();
            if (!t.isEmpty()) return Net.clean(t.replaceAll("^\"|\"$", ""), 80);
        }
        return "";
    }

    /* ───────── 내장 웹페이지 <title> ───────── */
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE);

    public static String httpTitle(String host, int timeoutMs) {
        try {
            HttpReply reply = http(host, 80, "GET", "/", null, null, timeoutMs, 256 * 1024);
            Matcher m = TITLE.matcher(new String(reply.body, StandardCharsets.UTF_8));
            if (!m.find()) return "";
            String t = m.group(1).replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
            return Net.clean(t, 80);
        } catch (Exception e) {
            return "";
        }
    }

    /** IEEE 1284 장치 ID(MFG:..;MDL:..;CMD:..) → 키: 값 */
    public static Map<String, String> parse1284(String id) {
        Map<String, String> out = new LinkedHashMap<>();
        if (id == null) return out;
        for (String part : id.split(";")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            String key = part.substring(0, colon).trim().toUpperCase(Locale.US);
            String value = part.substring(colon + 1).trim();
            if (key.equals("MANUFACTURER")) key = "MFG";
            if (key.equals("MODEL")) key = "MDL";
            if (key.equals("COMMAND SET")) key = "CMD";
            out.put(key, value);
        }
        return out;
    }
}
