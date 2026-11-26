package com.nuguna.print.helper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * 캐논 BJNP(8611) — PIXMA 가 9100 대신 받는 전송 방식.
 *   머리 16바이트: "BJNP" · 장치 1(프린터) · 명령 · 0(2) · 순번(BE16) · 세션(BE16) · 길이(BE32)
 *   인쇄: UDP 작업 정보(0x10, 세션 받음) → TCP 연결 → 0x21 로 4096바이트씩 보내고 답(받은 바이트 수)을 기다림 → UDP 닫기(0x11)
 *   식별: UDP 0x30 → IEEE 1284 장치 ID (MFG · MDL · CMD)
 */
public final class Bjnp {
    private Bjnp() {}

    public static final int PORT = 8611;
    private static final int CHUNK = 4096;

    static byte[] header(int cmd, int seq, int session, int payload) {
        byte[] h = new byte[16];
        h[0] = 'B';
        h[1] = 'J';
        h[2] = 'N';
        h[3] = 'P';
        h[4] = 0x01;
        h[5] = (byte) cmd;
        h[8] = (byte) (seq >> 8);
        h[9] = (byte) seq;
        h[10] = (byte) (session >> 8);
        h[11] = (byte) session;
        h[12] = (byte) (payload >>> 24);
        h[13] = (byte) (payload >>> 16);
        h[14] = (byte) (payload >>> 8);
        h[15] = (byte) payload;
        return h;
    }

    private static int u16(byte[] b, int p) {
        return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
    }

    /** UDP 명령 하나 보내고 답 받기(3번까지 다시) */
    static byte[] udp(String host, byte[] packet, int timeoutMs) throws IOException {
        DatagramSocket s = new DatagramSocket(null);
        try {
            Net.bind(s, Net.localFor(host));
            s.bind(new InetSocketAddress(0));
            s.setSoTimeout(timeoutMs);
            InetAddress addr = InetAddress.getByName(host);
            byte[] buf = new byte[4096];
            for (int attempt = 0; attempt < 3; attempt++) {
                s.send(new DatagramPacket(packet, packet.length, addr, PORT));
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    if (p.getLength() >= 16 && buf[0] == 'B' && buf[1] == 'J' && buf[2] == 'N' && buf[3] == 'P') {
                        byte[] out = new byte[p.getLength()];
                        System.arraycopy(buf, 0, out, 0, out.length);
                        return out;
                    }
                } catch (SocketTimeoutException again) {
                    /* 다시 */
                }
            }
            throw new IOException("bjnp no reply");
        } finally {
            s.close();
        }
    }

    /** IEEE 1284 장치 ID ("MFG:Canon;MDL:MX870 series;CMD:BJL,BJRaster3,…"). 실패하면 "" */
    public static String deviceId(String host) {
        try {
            byte[] r = udp(host, header(0x30, 0, 0, 0), 800);
            if (r.length < 18) return "";
            int len = u16(r, 16) - 2;
            if (len <= 0 || 18 + len > r.length) len = r.length - 18;
            return new String(r, 18, len, StandardCharsets.US_ASCII).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 작업 정보: 앞 8바이트 + 호스트 이름 64 + 사용자 64 + 작업 이름 256, 글자는 2바이트(BE) */
    static byte[] jobDetails(String user, String title) {
        byte[] body = new byte[8 + 64 + 64 + 256];
        put2(body, 8, "nuguna-phone", 64);
        put2(body, 8 + 64, user, 64);
        put2(body, 8 + 128, title, 256);
        byte[] out = new byte[16 + body.length];
        System.arraycopy(header(0x10, 0, 0, body.length), 0, out, 0, 16);
        System.arraycopy(body, 0, out, 16, body.length);
        return out;
    }

    private static void put2(byte[] dst, int at, String text, int size) {
        int n = Math.min(text.length(), size / 2 - 1);
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            dst[at + i * 2] = (byte) (c >> 8);
            dst[at + i * 2 + 1] = (byte) c;
        }
    }

    /** 인쇄: in 에서 length 바이트를 읽어 BJNP 로 보낸다. 보낸 바이트 수를 돌려준다 */
    public static long print(String host, InputStream in, long length, String title) throws IOException {
        byte[] reply = udp(host, jobDetails("nuguna", title), 3000);
        int session = u16(reply, 10);
        int seq = 0;
        long sent = 0;
        Socket tcp = Net.connect(host, PORT, 4000);
        try {
            tcp.setSoTimeout(60_000);
            OutputStream out = tcp.getOutputStream();
            InputStream back = tcp.getInputStream();
            byte[] chunk = new byte[CHUNK];
            while (sent < length) {
                int want = (int) Math.min(CHUNK, length - sent);
                int got = 0;
                while (got < want) {
                    int n = in.read(chunk, got, want - got);
                    if (n < 0) throw new IOException("short body");
                    got += n;
                }
                seq++;
                out.write(header(0x21, seq, session, got));
                out.write(chunk, 0, got);
                out.flush();
                /* 답: 머리 16바이트 + (길이만큼) 받은 바이트 수 */
                byte[] head = readFully(back, 16);
                int payload = ((head[12] & 0xff) << 24) | ((head[13] & 0xff) << 16) | ((head[14] & 0xff) << 8) | (head[15] & 0xff);
                if (payload > 0 && payload < 65536) readFully(back, payload);
                sent += got;
            }
        } finally {
            Net.closeQuietly(tcp);
            try {
                udp(host, header(0x11, ++seq, session, 0), 2000);
            } catch (Exception ignored) {
            }
        }
        return sent;
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r < 0) throw new IOException("bjnp closed");
            got += r;
        }
        return b;
    }
}
