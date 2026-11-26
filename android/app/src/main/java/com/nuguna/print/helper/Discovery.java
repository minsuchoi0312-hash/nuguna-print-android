package com.nuguna.print.helper;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 같은 와이파이의 프린터 찾기 — 네 가지 질의를 동시에 보내고 IP 기준으로 합친다.
 *  1) mDNS(Bonjour) 224.0.0.251:5353  _ipp · _ipps · _pdl-datastream · _printer · _canon-bjnp1
 *  2) SNMP 브로드캐스트 :161 (public)   sysDescr · hrDeviceDescr · IEEE 1284 장치 ID
 *  3) WS-Discovery 239.255.255.250:3702  (윈도우 네트워크 프린터)
 *  4) Canon BJNP 브로드캐스트 :8611
 *  + 대역 .1~.254 의 9100(Raw) · 631(IPP) · 515(LPD) 포트 확인
 * 모델명은 IPP → mDNS TXT → SNMP 1284 → PJL INFO ID → 웹 제목 순으로 채운다.
 */
public final class Discovery {
    private Discovery() {}

    /* 진행률 — /progress 가 읽어 간다. total = 확인할 주소 수 + 마무리(모델명) 1 단계 */
    static final java.util.concurrent.atomic.AtomicInteger progDone = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger progTotal = new java.util.concurrent.atomic.AtomicInteger();
    static volatile boolean progRunning = false;
    static volatile Map<String, Found> progFound = null;

    public static JSONObject progress() throws Exception {
        JSONObject o = new JSONObject();
        int found = 0;
        Map<String, Found> map = progFound;
        if (map != null) {
            synchronized (map) {
                for (Found f : map.values()) if (f.printerLike()) found++;
            }
        }
        o.put("ok", true);
        o.put("running", progRunning);
        o.put("done", progDone.get());
        o.put("total", progTotal.get());
        o.put("found", found);
        return o;
    }

    static final String[] MDNS_SERVICES = {
        "_ipp._tcp.local", "_ipps._tcp.local", "_pdl-datastream._tcp.local", "_printer._tcp.local", "_canon-bjnp1._tcp.local"
    };

    /** 한 대의 프린터에 대해 모은 정보 */
    static final class Found {
        final String host;
        boolean raw9100, ipp, lpd, mdns, snmp, wsd, bjnp;
        int port9100 = 9100;
        String mdnsModel = "", mdnsName = "", pdl = "", snmpDescr = "", snmpName = "", deviceId = "";
        final Set<String> services = new LinkedHashSet<>();

        Found(String host) {
            this.host = host;
        }

        /** 프린터라고 볼 근거가 있는지 (공유기 · PC 제외) */
        boolean printerLike() {
            if (raw9100 || ipp || lpd || mdns || wsd || bjnp) return true;
            if (!deviceId.isEmpty()) return true;
            String d = (snmpDescr + " " + snmpName).toLowerCase(Locale.US);
            return d.matches(".*(print|laserjet|officejet|deskjet|epson|canon|brother|kyocera|ricoh|xerox|sindoh|samsung m|samsung c|samsung sl|lexmark|konica|bizhub|sharp mx|toshiba e-studio|pantum|oki).*");
        }
    }

    private static Found row(Map<String, Found> map, String host) {
        Found f = map.get(host);
        if (f == null) {
            f = new Found(host);
            map.put(host, f);
        }
        return f;
    }

    /* ═════════════ 1) mDNS ═════════════ */
    private static void writeName(ByteArrayOutputStream out, String name) {
        for (String label : name.split("\\.")) {
            byte[] b = label.getBytes(StandardCharsets.UTF_8);
            out.write(b.length);
            out.write(b, 0, b.length);
        }
        out.write(0);
    }

    static byte[] mdnsQuery() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {0, 0, 0, 0, 0, (byte) MDNS_SERVICES.length, 0, 0, 0, 0, 0, 0}, 0, 12);
        for (String svc : MDNS_SERVICES) {
            writeName(out, svc);
            /* PTR, QU 비트(응답을 우리 포트로 바로) + IN */
            out.write(new byte[] {0x00, 0x0c, (byte) 0x80, 0x01}, 0, 4);
        }
        return out.toByteArray();
    }

    private static final class NameAt {
        final String name;
        final int end;

        NameAt(String name, int end) {
            this.name = name;
            this.end = end;
        }
    }

    private static NameAt readName(byte[] buf, int offset) {
        List<String> labels = new ArrayList<>();
        int pos = offset;
        int end = -1;
        for (int guard = 0; guard < 128; guard++) {
            if (pos >= buf.length) throw new IllegalArgumentException("dns overflow");
            int len = buf[pos] & 0xff;
            if (len == 0) {
                if (end < 0) end = pos + 1;
                return new NameAt(join(labels), end);
            }
            if ((len & 0xc0) == 0xc0) {
                if (pos + 1 >= buf.length) throw new IllegalArgumentException("dns pointer");
                if (end < 0) end = pos + 2;
                pos = ((len & 0x3f) << 8) | (buf[pos + 1] & 0xff);
                continue;
            }
            if (pos + 1 + len > buf.length) throw new IllegalArgumentException("dns label");
            labels.add(new String(buf, pos + 1, len, StandardCharsets.UTF_8));
            pos += 1 + len;
        }
        throw new IllegalArgumentException("dns loop");
    }

    private static String join(List<String> labels) {
        StringBuilder sb = new StringBuilder();
        for (String l : labels) {
            if (sb.length() > 0) sb.append('.');
            sb.append(l);
        }
        return sb.toString();
    }

    private static int u16(byte[] b, int p) {
        return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
    }

    /** mDNS 응답 하나를 레코드 표에 모은다 */
    static void parseMdns(byte[] buf, int len, String from, Map<String, String[]> ptr, Map<String, Integer> srvPort,
                          Map<String, String> srvTarget, Map<String, Map<String, String>> txt, Map<String, String> addr) {
        byte[] b = len == buf.length ? buf : java.util.Arrays.copyOf(buf, len);
        if (b.length < 12) return;
        int flags = u16(b, 2);
        if ((flags & 0x8000) == 0) return; /* 질의는 무시 */
        int qd = u16(b, 4);
        int total = u16(b, 6) + u16(b, 8) + u16(b, 10);
        int pos = 12;
        for (int i = 0; i < qd; i++) pos = readName(b, pos).end + 4;
        for (int i = 0; i < total; i++) {
            NameAt n = readName(b, pos);
            int type = u16(b, n.end);
            int rdlen = u16(b, n.end + 8);
            int rd = n.end + 10;
            if (rd + rdlen > b.length) return;
            String key = n.name.toLowerCase(Locale.US);
            if (type == 12) {
                ptr.put(readName(b, rd).name.toLowerCase(Locale.US), new String[] {key, from});
            } else if (type == 33 && rdlen >= 6) {
                srvPort.put(key, u16(b, rd + 4));
                srvTarget.put(key, readName(b, rd + 6).name.toLowerCase(Locale.US));
            } else if (type == 1 && rdlen == 4) {
                addr.put(key, (b[rd] & 0xff) + "." + (b[rd + 1] & 0xff) + "." + (b[rd + 2] & 0xff) + "." + (b[rd + 3] & 0xff));
            } else if (type == 16) {
                Map<String, String> t = txt.get(key);
                if (t == null) {
                    t = new HashMap<>();
                    txt.put(key, t);
                }
                for (int p = rd; p < rd + rdlen; ) {
                    int l = b[p] & 0xff;
                    if (p + 1 + l > rd + rdlen) break;
                    String entry = new String(b, p + 1, l, StandardCharsets.UTF_8);
                    int eq = entry.indexOf('=');
                    if (!entry.isEmpty()) t.put((eq < 0 ? entry : entry.substring(0, eq)).toLowerCase(Locale.US), eq < 0 ? "" : entry.substring(eq + 1));
                    p += 1 + l;
                }
            }
            pos = rd + rdlen;
        }
    }

    static void mdns(Net.Local local, Map<String, Found> out, int ms) {
        Map<String, String[]> ptr = new ConcurrentHashMap<>();
        Map<String, Integer> srvPort = new ConcurrentHashMap<>();
        Map<String, String> srvTarget = new ConcurrentHashMap<>();
        Map<String, Map<String, String>> txt = new ConcurrentHashMap<>();
        Map<String, String> addr = new ConcurrentHashMap<>();
        List<MulticastSocket> sockets = new ArrayList<>();
        try {
            InetAddress group = InetAddress.getByName("224.0.0.251");
            /* ① 임의 포트: QU 질의에 대한 유니캐스트 답 */
            MulticastSocket a = new MulticastSocket(0);
            sockets.add(a);
            /* ② 5353 공유: 멀티캐스트로만 답하는 기기용 (시스템이 쓰고 있으면 건너뜀) */
            try {
                MulticastSocket m = new MulticastSocket(null);
                m.setReuseAddress(true);
                m.bind(new InetSocketAddress(5353));
                if (local != null && local.nif != null) m.joinGroup(new InetSocketAddress(group, 5353), local.nif);
                else m.joinGroup(group);
                sockets.add(m);
            } catch (Exception ignored) {
            }
            byte[] q = mdnsQuery();
            for (MulticastSocket s : sockets) {
                try {
                    if (local != null && local.nif != null) s.setNetworkInterface(local.nif);
                } catch (Exception ignored) {
                }
                s.setTimeToLive(255);
                s.setSoTimeout(200);
            }
            a.send(new DatagramPacket(q, q.length, group, 5353));
            long deadline = System.currentTimeMillis() + ms;
            long resend = System.currentTimeMillis() + 500;
            byte[] buf = new byte[9000];
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() > resend) {
                    a.send(new DatagramPacket(q, q.length, group, 5353));
                    resend = Long.MAX_VALUE;
                }
                for (MulticastSocket s : sockets) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        try {
                            parseMdns(buf, p.getLength(), p.getAddress().getHostAddress(), ptr, srvPort, srvTarget, txt, addr);
                        } catch (Exception noise) {
                            /* 다른 mDNS 잡음 */
                        }
                    } catch (SocketTimeoutException again) {
                        /* 다음 소켓 */
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            for (MulticastSocket s : sockets) s.close();
        }
        for (Map.Entry<String, String[]> e : ptr.entrySet()) {
            String instance = e.getKey();
            String service = e.getValue()[0];
            if (!isPrinterService(service)) continue;
            String target = srvTarget.get(instance);
            String host = target != null && addr.containsKey(target) ? addr.get(target) : e.getValue()[1];
            if (!Net.isPrivate(host)) continue;
            Map<String, String> t = txt.containsKey(instance) ? txt.get(instance) : Collections.<String, String>emptyMap();
            Found f;
            synchronized (out) {
                f = row(out, host);
            }
            f.mdns = true;
            f.services.add(service.replace(".local", ""));
            String name = instance.contains("._") ? instance.substring(0, instance.indexOf("._")) : instance;
            if (f.mdnsName.isEmpty()) f.mdnsName = Net.clean(name, 80);
            String model = t.containsKey("ty") ? t.get("ty") : "";
            if (model.isEmpty() && t.containsKey("usb_mdl")) model = (t.containsKey("usb_mfg") ? t.get("usb_mfg") + " " : "") + t.get("usb_mdl");
            if (model.isEmpty() && t.containsKey("product")) model = t.get("product").replaceAll("^\\(|\\)$", "");
            if (!model.isEmpty() && (f.mdnsModel.isEmpty() || t.containsKey("ty"))) f.mdnsModel = Net.clean(model, 80);
            if (t.containsKey("pdl") && f.pdl.isEmpty()) f.pdl = Net.clean(t.get("pdl"), 300);
            if (service.startsWith("_pdl-datastream")) {
                f.raw9100 = true;
                Integer port = srvPort.get(instance);
                if (port != null && port > 0) f.port9100 = port;
            } else if (service.startsWith("_ipp")) f.ipp = true;
            else if (service.startsWith("_printer")) f.lpd = true;
            else if (service.startsWith("_canon-bjnp")) f.bjnp = true;
        }
    }

    private static boolean isPrinterService(String service) {
        for (String s : MDNS_SERVICES) if (service.equalsIgnoreCase(s)) return true;
        return false;
    }

    /* ═════════════ 2) SNMP v1 (BER 직접 인코딩) ═════════════ */
    static final String OID_SYS_DESCR = "1.3.6.1.2.1.1.1.0";
    static final String OID_SYS_NAME = "1.3.6.1.2.1.1.5.0";
    static final String OID_HR_DEVICE = "1.3.6.1.2.1.25.3.2.1.3.1";
    static final String OID_1284 = "1.3.6.1.4.1.2699.1.2.1.2.1.1.3.1";

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = value.length;
        if (len < 0x80) out.write(len);
        else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xff);
        }
        out.write(value, 0, value.length);
        return out.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    private static byte[] oid(String text) {
        String[] arcs = text.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(40 * Integer.parseInt(arcs[0]) + Integer.parseInt(arcs[1]));
        for (int i = 2; i < arcs.length; i++) {
            long v = Long.parseLong(arcs[i]);
            byte[] tmp = new byte[10];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7f);
                v >>= 7;
            } while (v > 0);
            for (int j = n - 1; j >= 0; j--) out.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, out.toByteArray());
    }

    static byte[] snmpGet(int requestId, String... oids) {
        ByteArrayOutputStream binds = new ByteArrayOutputStream();
        for (String o : oids) {
            byte[] vb = tlv(0x30, cat(oid(o), new byte[] {0x05, 0x00}));
            binds.write(vb, 0, vb.length);
        }
        byte[] pdu = tlv(0xa0, cat(tlv(0x02, new byte[] {(byte) (requestId >> 8), (byte) requestId}), tlv(0x02, new byte[] {0}), tlv(0x02, new byte[] {0}), tlv(0x30, binds.toByteArray())));
        return tlv(0x30, cat(tlv(0x02, new byte[] {0}), tlv(0x04, "public".getBytes(StandardCharsets.US_ASCII)), pdu));
    }

    /** 응답에서 (OID 문자열 → 값 글자) 를 꺼낸다. 오류 응답이면 빈 표 */
    static Map<String, String> snmpValues(byte[] b, int len) {
        Map<String, String> out = new HashMap<>();
        walk(b, 0, len, out, new String[] {null});
        return out;
    }

    private static void walk(byte[] b, int start, int end, Map<String, String> out, String[] lastOid) {
        int pos = start;
        while (pos < end) {
            int tag = b[pos++] & 0xff;
            if (pos >= end) return;
            int len = b[pos++] & 0xff;
            if (len == 0x81) {
                len = b[pos++] & 0xff;
            } else if (len == 0x82) {
                len = ((b[pos] & 0xff) << 8) | (b[pos + 1] & 0xff);
                pos += 2;
            } else if (len > 0x82) return;
            if (pos + len > end) return;
            if (tag == 0x30 || tag == 0xa2) {
                walk(b, pos, pos + len, out, lastOid);
            } else if (tag == 0x06) {
                lastOid[0] = decodeOid(b, pos, len);
            } else if (tag == 0x04 && lastOid[0] != null) {
                out.put(lastOid[0], new String(b, pos, len, StandardCharsets.UTF_8));
                lastOid[0] = null;
            }
            pos += len;
        }
    }

    private static String decodeOid(byte[] b, int pos, int len) {
        if (len < 1) return "";
        StringBuilder sb = new StringBuilder();
        int first = b[pos] & 0xff;
        sb.append(first / 40).append('.').append(first % 40);
        long v = 0;
        for (int i = 1; i < len; i++) {
            int c = b[pos + i] & 0xff;
            v = (v << 7) | (c & 0x7f);
            if ((c & 0x80) == 0) {
                sb.append('.').append(v);
                v = 0;
            }
        }
        return sb.toString();
    }

    static void snmp(Net.Local local, Map<String, Found> out, int ms) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(null);
            Net.bind(s, local);
            s.bind(new InetSocketAddress(0));
            s.setBroadcast(true);
            s.setSoTimeout(200);
            /* v1은 OID 하나라도 없으면 전체가 오류 → 묶음을 나눠서 보낸다 */
            byte[][] asks = {snmpGet(0x4e01, OID_SYS_DESCR, OID_SYS_NAME), snmpGet(0x4e02, OID_HR_DEVICE), snmpGet(0x4e03, OID_1284)};
            Set<String> targets = new LinkedHashSet<>();
            if (local != null) targets.add(local.broadcast());
            targets.add("255.255.255.255");
            for (int round = 0; round < 2; round++) {
                for (String t : targets) {
                    for (byte[] q : asks) {
                        try {
                            s.send(new DatagramPacket(q, q.length, InetAddress.getByName(t), 161));
                        } catch (Exception ignored) {
                        }
                    }
                }
                long deadline = System.currentTimeMillis() + ms / 2;
                byte[] buf = new byte[4096];
                while (System.currentTimeMillis() < deadline) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        String host = p.getAddress().getHostAddress();
                        if (!Net.isPrivate(host) || (local != null && host.equals(local.address))) continue;
                        Map<String, String> v = snmpValues(buf, p.getLength());
                        if (v.isEmpty()) continue;
                        Found f;
                        synchronized (out) {
                            f = row(out, host);
                        }
                        f.snmp = true;
                        if (v.containsKey(OID_SYS_DESCR)) f.snmpDescr = Net.clean(v.get(OID_SYS_DESCR), 120);
                        if (v.containsKey(OID_SYS_NAME)) f.snmpName = Net.clean(v.get(OID_SYS_NAME), 80);
                        if (v.containsKey(OID_HR_DEVICE)) f.snmpDescr = Net.clean(v.get(OID_HR_DEVICE), 120);
                        if (v.containsKey(OID_1284)) f.deviceId = Net.clean(v.get(OID_1284), 400);
                    } catch (SocketTimeoutException again) {
                        /* 계속 */
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (s != null) s.close();
        }
    }

    /* ═════════════ 3) WS-Discovery ═════════════ */
    static void wsd(Net.Local local, Map<String, Found> out, int ms) {
        MulticastSocket s = null;
        try {
            s = new MulticastSocket(0);
            if (local != null && local.nif != null) s.setNetworkInterface(local.nif);
            s.setTimeToLive(4);
            s.setSoTimeout(200);
            String probe = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:wsa=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\""
                + " xmlns:wsd=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:wprt=\"http://schemas.microsoft.com/windows/2006/08/wdp/print\">"
                + "<soap:Header><wsa:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To>"
                + "<wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</wsa:Action>"
                + "<wsa:MessageID>urn:uuid:" + UUID.randomUUID() + "</wsa:MessageID></soap:Header>"
                + "<soap:Body><wsd:Probe><wsd:Types>wprt:PrintDeviceType</wsd:Types></wsd:Probe></soap:Body></soap:Envelope>";
            byte[] q = probe.getBytes(StandardCharsets.UTF_8);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            s.send(new DatagramPacket(q, q.length, group, 3702));
            long deadline = System.currentTimeMillis() + ms;
            long resend = System.currentTimeMillis() + 500;
            byte[] buf = new byte[16384];
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() > resend) {
                    s.send(new DatagramPacket(q, q.length, group, 3702));
                    resend = Long.MAX_VALUE;
                }
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    String host = p.getAddress().getHostAddress();
                    String text = new String(buf, 0, p.getLength(), StandardCharsets.UTF_8);
                    if (!Net.isPrivate(host) || !text.toLowerCase(Locale.US).contains("print")) continue;
                    synchronized (out) {
                        row(out, host).wsd = true;
                    }
                } catch (SocketTimeoutException again) {
                    /* 계속 */
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (s != null) s.close();
        }
    }

    /* ═════════════ 4) Canon BJNP ═════════════ */
    static void bjnp(Net.Local local, Map<String, Found> out, int ms) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(null);
            Net.bind(s, local);
            s.bind(new InetSocketAddress(0));
            s.setBroadcast(true);
            s.setSoTimeout(200);
            /* "BJNP" + 장치 종류 1(프린터) + 명령 1(찾기) + 0 + 순번 + 세션 + 길이 0 */
            byte[] q = {'B', 'J', 'N', 'P', 0x01, 0x01, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0};
            Set<String> targets = new LinkedHashSet<>();
            if (local != null) targets.add(local.broadcast());
            targets.add("255.255.255.255");
            for (String t : targets) s.send(new DatagramPacket(q, q.length, InetAddress.getByName(t), 8611));
            long deadline = System.currentTimeMillis() + ms;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    String host = p.getAddress().getHostAddress();
                    if (p.getLength() >= 16 && buf[0] == 'B' && buf[1] == 'J' && buf[2] == 'N' && buf[3] == 'P' && Net.isPrivate(host)) {
                        synchronized (out) {
                            row(out, host).bjnp = true;
                        }
                    }
                } catch (SocketTimeoutException again) {
                    /* 계속 */
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (s != null) s.close();
        }
    }

    /* ═════════════ 포트 확인 ═════════════ */
    static void portScan(List<String> hosts, Map<String, Found> out, int timeoutMs) {
        ExecutorService pool = Executors.newFixedThreadPool(64);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (final String host : hosts) {
                jobs.add(pool.submit(() -> {
                    boolean raw = Net.portOpen(host, 9100, timeoutMs);
                    boolean ipp = Net.portOpen(host, 631, timeoutMs);
                    boolean lpd = !raw && !ipp && Net.portOpen(host, 515, timeoutMs);
                    /* 캐논 PIXMA 는 9100 · 631 없이 BJNP(TCP 8611) 만 여는 경우가 많다 */
                    boolean bjnp = !raw && !ipp && Net.portOpen(host, Bjnp.PORT, timeoutMs);
                    if (raw || ipp || lpd || bjnp) {
                        synchronized (out) {
                            Found f = row(out, host);
                            f.raw9100 |= raw;
                            f.ipp |= ipp;
                            f.lpd |= lpd;
                            f.bjnp |= bjnp;
                        }
                    }
                    progDone.incrementAndGet();
                }));
            }
            for (Future<?> j : jobs) {
                try {
                    j.get();
                } catch (Exception ignored) {
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /* ═════════════ 전체 ═════════════ */
    public static JSONObject discover(Context ctx, String subnet, boolean pjl) throws Exception {
        long started = System.currentTimeMillis();
        WifiManager.MulticastLock lock = null;
        try {
            WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            lock = wifi.createMulticastLock("nuguna:multicast");
            lock.setReferenceCounted(false);
            lock.acquire();
        } catch (Exception ignored) {
        }
        try {
            final Net.Local local = subnet.isEmpty() ? Net.localFor(null) : Net.localFor(subnet + ".1");
            final String net24 = !subnet.isEmpty() ? subnet : local != null ? local.subnet24() : "";
            final Map<String, Found> found = new HashMap<>();
            List<String> hosts = new ArrayList<>();
            if (!net24.isEmpty()) {
                for (int i = 1; i <= 254; i++) {
                    String h = net24 + "." + i;
                    if (local == null || !h.equals(local.address)) hosts.add(h);
                }
            }
            progDone.set(0);
            progTotal.set(hosts.size() + 1);
            progFound = found;
            progRunning = true;
            ExecutorService side = Executors.newFixedThreadPool(5);
            List<Future<?>> jobs = new ArrayList<>();
            jobs.add(side.submit(() -> mdns(local, found, 2200)));
            jobs.add(side.submit(() -> snmp(local, found, 2000)));
            jobs.add(side.submit(() -> wsd(local, found, 1800)));
            jobs.add(side.submit(() -> bjnp(local, found, 1500)));
            jobs.add(side.submit(() -> portScan(hosts, found, 400)));
            for (Future<?> j : jobs) {
                try {
                    j.get(20, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
            side.shutdownNow();

            List<Found> rows = new ArrayList<>();
            synchronized (found) {
                for (Found f : found.values()) if (f.printerLike() && (local == null || !f.host.equals(local.address))) rows.add(f);
            }
            /* mDNS · SNMP 로만 보인 기기는 포트를 한 번 더 확인 */
            for (Found f : rows) {
                if (!f.raw9100 && (f.mdns || f.snmp || f.wsd || f.bjnp)) f.raw9100 = Net.portOpen(f.host, f.port9100, 600);
                if (!f.ipp && (f.mdns || f.snmp || f.wsd)) f.ipp = Net.portOpen(f.host, 631, 600);
            }
            JSONArray printers = new JSONArray();
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Future<JSONObject>> models = new ArrayList<>();
            for (final Found f : rows) models.add(pool.submit(() -> describe(f, pjl)));
            List<JSONObject> list = new ArrayList<>();
            for (Future<JSONObject> m : models) {
                try {
                    list.add(m.get(15, TimeUnit.SECONDS));
                } catch (Exception ignored) {
                }
            }
            pool.shutdownNow();
            Collections.sort(list, (a, b) -> Long.compare(ipNum(a.optString("host")), ipNum(b.optString("host"))));
            for (JSONObject o : list) printers.put(o);
            JSONObject res = new JSONObject();
            res.put("ok", true);
            JSONArray subnets = new JSONArray();
            if (!net24.isEmpty()) subnets.put(net24);
            res.put("subnets", subnets);
            res.put("printers", printers);
            res.put("ms", System.currentTimeMillis() - started);
            res.put("phone", true);
            return res;
        } finally {
            progDone.set(progTotal.get());
            progRunning = false;
            if (lock != null) {
                try {
                    lock.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static long ipNum(String ip) {
        long n = 0;
        for (String p : ip.split("\\.")) {
            try {
                n = n * 256 + Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return n;
    }

    /** 모델명 · 지원 언어를 채운다 */
    static JSONObject describe(Found f, boolean pjl) throws Exception {
        String model = "";
        String via = null;
        Set<String> langs = new LinkedHashSet<>();
        /* 캐논: BJNP 로 장치 ID(모델명 · 인쇄 언어)를 묻는다 */
        if (f.bjnp && f.deviceId.isEmpty()) f.deviceId = Net.clean(Bjnp.deviceId(f.host), 400);
        Map<String, String> id = Probe.parse1284(f.deviceId);
        if (f.ipp) {
            java.util.Map<String, java.util.List<Object>> attrs = Probe.ippAttrs(f.host, new String[] {"printer-make-and-model", "document-format-supported"}, 1800);
            if (attrs != null) {
                model = Net.clean(Probe.first(attrs, "printer-make-and-model"), 80);
                if (!model.isEmpty()) via = "ipp";
                java.util.List<Object> formats = attrs.get("document-format-supported");
                if (formats != null) for (Object o : formats) langs.add(String.valueOf(o).toLowerCase(Locale.US));
            }
        }
        if (model.isEmpty() && !f.mdnsModel.isEmpty()) {
            model = f.mdnsModel;
            via = "mdns";
        }
        if (model.isEmpty() && id.containsKey("MDL")) {
            model = Net.clean((id.containsKey("MFG") ? id.get("MFG") + " " : "") + id.get("MDL"), 80);
            via = "snmp";
        }
        if (model.isEmpty() && f.raw9100 && pjl) {
            model = Probe.pjlModel(f.host);
            if (!model.isEmpty()) via = "pjl";
        }
        if (model.isEmpty() && !f.snmpDescr.isEmpty()) {
            model = f.snmpDescr;
            via = "snmp";
        }
        if (model.isEmpty()) {
            model = Probe.httpTitle(f.host, 600);
            if (!model.isEmpty()) via = "http";
        }
        if (model.isEmpty() && !f.mdnsName.isEmpty()) {
            model = f.mdnsName;
            via = "mdns";
        }
        if (id.containsKey("CMD")) for (String c : id.get("CMD").split(",")) if (!c.trim().isEmpty()) langs.add(c.trim());
        if (!f.pdl.isEmpty()) for (String c : f.pdl.split(",")) if (!c.trim().isEmpty()) langs.add(c.trim().toLowerCase(Locale.US));

        JSONObject o = new JSONObject();
        o.put("host", f.host);
        o.put("raw9100", f.raw9100);
        o.put("ipp", f.ipp);
        o.put("lpd", f.lpd);
        o.put("mdns", f.mdns);
        o.put("model", model);
        o.put("via", via == null ? JSONObject.NULL : via);
        o.put("canon", f.bjnp);
        StringBuilder cmd = new StringBuilder();
        for (String l : langs) {
            if (cmd.length() > 0) cmd.append(',');
            cmd.append(l);
        }
        o.put("cmd", Net.clean(cmd.toString(), 400));
        JSONArray found = new JSONArray();
        if (f.mdns) found.put("mdns");
        if (f.snmp) found.put("snmp");
        if (f.wsd) found.put("wsd");
        if (f.bjnp) found.put("bjnp");
        if (f.raw9100 || f.ipp || f.lpd) found.put("port");
        o.put("found", found);
        return o;
    }

}
