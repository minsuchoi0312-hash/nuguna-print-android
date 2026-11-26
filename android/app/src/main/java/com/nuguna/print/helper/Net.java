package com.nuguna.print.helper;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 와이파이 주소·대역 읽기와 소켓 도구.
 * 복사기 와이파이는 "인터넷 없음"인 경우가 많아 안드로이드가 연결을 모바일 데이터로 보내 버린다 →
 * 모든 소켓을 와이파이 네트워크에 직접 묶는다.
 */
public final class Net {
    private Net() {}

    private static final Pattern IPV4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    /** 이 폰의 와이파이(또는 유선) 주소 하나 */
    public static final class Local {
        public final String iface;
        public final String address;
        public final int prefix;
        public final Network network;
        public final NetworkInterface nif;

        Local(String iface, String address, int prefix, Network network, NetworkInterface nif) {
            this.iface = iface;
            this.address = address;
            this.prefix = prefix;
            this.network = network;
            this.nif = nif;
        }

        /** 대역 앞 세 자리 (예: 192.168.0) */
        public String subnet24() {
            return address.substring(0, address.lastIndexOf('.'));
        }

        /** 대역 브로드캐스트 주소 (SNMP · BJNP 검색용) */
        public String broadcast() {
            try {
                byte[] b = InetAddress.getByName(address).getAddress();
                int ip = ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
                int p = Math.max(8, Math.min(30, prefix));
                int mask = p == 0 ? 0 : (int) (0xffffffffL << (32 - p));
                int bc = ip | ~mask;
                return ((bc >>> 24) & 0xff) + "." + ((bc >>> 16) & 0xff) + "." + ((bc >>> 8) & 0xff) + "." + (bc & 0xff);
            } catch (Exception e) {
                return subnet24() + ".255";
            }
        }
    }

    private static volatile Context app;

    public static void init(Context context) {
        app = context.getApplicationContext();
    }

    public static boolean isIPv4(String text) {
        return text != null && IPV4.matcher(text).matches();
    }

    /** 사설 IPv4만 (10/8 · 172.16/12 · 192.168/16 · 169.254/16), .0 · .255 제외 */
    public static boolean isPrivate(String host) {
        if (!isIPv4(host)) return false;
        String[] p = host.split("\\.");
        int a = Integer.parseInt(p[0]);
        int b = Integer.parseInt(p[1]);
        int d = Integer.parseInt(p[3]);
        if (d == 0 || d == 255) return false;
        return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 169 && b == 254);
    }

    /** 와이파이 · 유선 네트워크의 사설 IPv4 목록 (와이파이 먼저) */
    @SuppressWarnings("deprecation")
    public static List<Local> locals() {
        List<Local> out = new ArrayList<>();
        Context ctx = app;
        if (ctx != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                    if (caps == null) continue;
                    boolean wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                    boolean eth = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                    if (!wifi && !eth) continue;
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp == null) continue;
                    NetworkInterface nif = null;
                    try {
                        nif = lp.getInterfaceName() == null ? null : NetworkInterface.getByName(lp.getInterfaceName());
                    } catch (Exception ignored) {
                    }
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress addr = la.getAddress();
                        if (addr instanceof Inet4Address && isPrivate(addr.getHostAddress())) {
                            Local row = new Local(String.valueOf(lp.getInterfaceName()), addr.getHostAddress(), la.getPrefixLength(), n, nif);
                            if (wifi) out.add(0, row);
                            else out.add(row);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (out.isEmpty()) {
            /* 권한 · 기기 문제로 못 읽으면 인터페이스 목록에서 wlan · eth 를 찾는다 */
            try {
                for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!nif.isUp() || nif.isLoopback()) continue;
                    String name = nif.getName();
                    if (!name.startsWith("wlan") && !name.startsWith("eth") && !name.startsWith("ap")) continue;
                    for (java.net.InterfaceAddress ia : nif.getInterfaceAddresses()) {
                        InetAddress addr = ia.getAddress();
                        if (addr instanceof Inet4Address && isPrivate(addr.getHostAddress())) {
                            out.add(new Local(name, addr.getHostAddress(), ia.getNetworkPrefixLength(), null, nif));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** host가 속한(또는 첫 번째) 와이파이 네트워크 */
    public static Local localFor(String host) {
        List<Local> list = locals();
        if (list.isEmpty()) return null;
        if (host != null && host.lastIndexOf('.') > 0) {
            String net = host.substring(0, host.lastIndexOf('.'));
            for (Local row : list) if (row.subnet24().equals(net)) return row;
        }
        return list.get(0);
    }

    /** 와이파이에 묶은 TCP 연결 */
    public static Socket connect(String host, int port, int timeoutMs) throws IOException {
        Local local = localFor(host);
        Socket socket = local != null && local.network != null ? local.network.getSocketFactory().createSocket() : new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    public static boolean portOpen(String host, int port, int timeoutMs) {
        try {
            Socket s = connect(host, port, timeoutMs);
            closeQuietly(s);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** UDP 소켓을 와이파이에 묶는다 (실패해도 그대로 쓴다) */
    public static void bind(DatagramSocket socket, Local local) {
        if (local == null || local.network == null) return;
        try {
            local.network.bindSocket(socket);
        } catch (Exception ignored) {
        }
    }

    public static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > limit) throw new IOException("too large");
        }
        return out.toByteArray();
    }

    public static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    /** 프린터가 보낸 글에서 제어 문자를 걷어 내고 길이를 제한한다 */
    public static String clean(String text, int max) {
        if (text == null) return "";
        String t = text.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
