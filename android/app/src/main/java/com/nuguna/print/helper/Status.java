package com.nuguna.print.helper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 읽기 전용 상태 조회 — PC 도우미(raw-print-helper.mjs)의 /status 와 같은 모양 · 같은 판정 */
public final class Status {
    private Status() {}

    private static final List<String> RANK = Arrays.asList("unknown", "ready", "busy", "warning", "error");

    private static String worse(String a, String b) {
        return RANK.indexOf(b) > RANK.indexOf(a) ? b : a;
    }

    /** PJL 상태 코드 → {상태, 사유} */
    static String[] pjlCode(String code) {
        int n;
        try {
            n = Integer.parseInt(code);
        } catch (Exception e) {
            return new String[] {"unknown", ""};
        }
        if (n == 10001 || n == 10000) return new String[] {"ready", n == 10000 ? "power-save" : ""};
        if (n == 10006) return new String[] {"warning", "toner-low"};
        if (n == 10002 || n == 10003 || n == 10004 || n == 10005 || n == 10007 || n == 10023 || n == 10024) return new String[] {"busy", n == 10003 ? "warming-up" : "processing"};
        if (n >= 10000 && n < 11000) return new String[] {"ready", ""};
        if (n >= 11000 && n < 12000) return new String[] {"error", "media-empty"};
        if (n >= 30000 && n < 31000) return new String[] {"error", "cover-open"};
        if (n >= 35000 && n < 36000) return new String[] {"warning", "attention"};
        if (n >= 41000 && n < 42000) return new String[] {"error", "media-empty"};
        if (n >= 42000 && n < 43000) return new String[] {"error", "media-jam"};
        if (n == 40021) return new String[] {"error", "cover-open"};
        if (n == 40022 || n == 40019) return new String[] {"error", "media-jam"};
        if (n == 40038 || n == 40026) return new String[] {"warning", "toner-low"};
        if (n >= 40000 && n < 50000) return new String[] {"error", "printer-error"};
        if (n >= 50000) return new String[] {"error", "hardware-error"};
        return new String[] {"unknown", ""};
    }

    private static final Pattern CODE = Pattern.compile("CODE\\s*=\\s*(\\d{5})", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISPLAY = Pattern.compile("DISPLAY\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern LANG = Pattern.compile("^\\s*(PCL\\w*|PDF|POSTSCRIPT|PS|XPS|PCLXL|URF|PWG\\w*)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    public static JSONObject check(String host, boolean pjl) throws Exception {
        long started = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        Future<Boolean> f9100 = pool.submit(() -> Net.portOpen(host, 9100, 800));
        Future<Boolean> f631 = pool.submit(() -> Net.portOpen(host, 631, 800));
        Future<Boolean> f80 = pool.submit(() -> Net.portOpen(host, 80, 800));
        boolean raw9100 = get(f9100), ipp631 = get(f631), webAdmin = get(f80);
        Future<Map<String, List<Object>>> fIpp = ipp631 ? pool.submit(() -> Probe.ippAttrs(host, Probe.STATUS_ATTRS, 2000)) : null;
        Future<String> fPjl = raw9100 && pjl ? pool.submit(() -> Probe.pjlQuery(host, new String[] {"@PJL INFO STATUS", "@PJL INFO CONFIG"}, 2000)) : null;
        Map<String, List<Object>> ipp = null;
        String pjlText = "";
        try {
            if (fIpp != null) ipp = fIpp.get(6, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        try {
            if (fPjl != null) pjlText = fPjl.get(6, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        pool.shutdownNow();

        String state = "unknown";
        Set<String> reasons = new LinkedHashSet<>();
        if (ipp != null) {
            int ps = -1;
            try {
                ps = Integer.parseInt(Probe.first(ipp, "printer-state"));
            } catch (Exception ignored) {
            }
            state = worse(state, ps == 5 ? "error" : ps == 4 ? "busy" : ps == 3 ? "ready" : "unknown");
            List<Object> rs = ipp.get("printer-state-reasons");
            if (rs != null) {
                for (Object raw : rs) {
                    String text = String.valueOf(raw);
                    String r = text.replaceAll("-(report|warning|error)$", "");
                    if (r.isEmpty() || r.equals("none")) continue;
                    if (r.matches(".*(jam|empty|needed|cover|door|missing|offline).*") || text.endsWith("-error")) state = worse(state, "error");
                    else if (r.matches(".*(low|warning|full|paused|attention).*") || text.endsWith("-warning")) state = worse(state, "warning");
                    reasons.add(r.equals("media-needed") ? "media-empty" : r.equals("door-open") ? "cover-open" : r.equals("marker-supply-low") ? "toner-low" : r);
                }
            }
        }
        String pjlCode = "", display = "";
        List<String> langs = new ArrayList<>();
        Boolean pjlDuplex = null;
        if (!pjlText.isEmpty()) {
            int at = pjlText.indexOf("INFO STATUS");
            String block = at < 0 ? "" : pjlText.substring(at);
            Matcher m = CODE.matcher(block);
            if (m.find()) pjlCode = m.group(1);
            Matcher d = DISPLAY.matcher(block);
            if (d.find()) display = Net.clean(d.group(1), 60);
            if (!pjlCode.isEmpty()) {
                String[] info = pjlCode(pjlCode);
                state = worse(state, info[0]);
                if (!info[1].isEmpty() && !info[1].equals("processing") && !info[1].equals("power-save")) reasons.add(info[1]);
            }
            int ca = pjlText.indexOf("INFO CONFIG");
            if (ca >= 0) {
                int end = pjlText.indexOf('\f', ca);
                String cfg = end < 0 ? pjlText.substring(ca) : pjlText.substring(ca, end);
                Matcher lm = LANG.matcher(cfg);
                while (lm.find()) {
                    String l = lm.group(1).toUpperCase(Locale.US);
                    if (!langs.contains(l)) langs.add(l);
                }
                if (cfg.toUpperCase(Locale.US).contains("DUPLEX")) pjlDuplex = true;
            }
        }
        JSONArray toners = new JSONArray();
        List<Object> names = ipp == null ? null : ipp.get("marker-names");
        List<Object> levels = ipp == null ? null : ipp.get("marker-levels");
        List<Object> colors = ipp == null ? null : ipp.get("marker-colors");
        boolean low = false;
        if (names != null) {
            for (int i = 0; i < names.size(); i++) {
                JSONObject t = new JSONObject();
                t.put("name", Net.clean(String.valueOf(names.get(i)), 40));
                Object lv = levels != null && i < levels.size() ? levels.get(i) : null;
                if (lv instanceof Integer && (Integer) lv >= 0) {
                    int v = Math.min(100, (Integer) lv);
                    t.put("level", v);
                    if (v <= 10) low = true;
                } else t.put("level", JSONObject.NULL);
                t.put("color", colors != null && i < colors.size() ? String.valueOf(colors.get(i)) : "");
                toners.put(t);
            }
        }
        if (low && !reasons.contains("toner-low")) {
            reasons.add("toner-low");
            state = worse(state, "warning");
        }
        List<String> formats = new ArrayList<>();
        if (ipp != null && ipp.get("document-format-supported") != null) for (Object o : ipp.get("document-format-supported")) formats.add(String.valueOf(o).toLowerCase(Locale.US));
        List<String> sides = new ArrayList<>();
        if (ipp != null && ipp.get("sides-supported") != null) for (Object o : ipp.get("sides-supported")) sides.add(String.valueOf(o));

        JSONObject caps = new JSONObject();
        caps.put("pdfDirect", !formats.isEmpty() ? formats.contains("application/pdf") : !langs.isEmpty() ? langs.contains("PDF") : JSONObject.NULL);
        boolean pcl5 = false;
        for (String l : langs) if (l.matches("^PCL(5.*)?$")) pcl5 = true;
        boolean pclFmt = false;
        for (String f : formats) if (f.contains("pcl")) pclFmt = true;
        caps.put("pcl5", !langs.isEmpty() ? pcl5 : !formats.isEmpty() ? pclFmt : JSONObject.NULL);
        boolean two = false;
        for (String s : sides) if (s.startsWith("two-sided")) two = true;
        caps.put("duplex", !sides.isEmpty() ? two : pjlDuplex != null ? pjlDuplex : JSONObject.NULL);
        List<Object> colorSup = ipp == null ? null : ipp.get("color-supported");
        caps.put("color", colorSup != null && !colorSup.isEmpty() ? Boolean.TRUE.equals(colorSup.get(0)) : JSONObject.NULL);
        caps.put("raw9100", raw9100);
        caps.put("ipp631", ipp631);
        caps.put("webAdmin", webAdmin);

        JSONObject res = new JSONObject();
        res.put("ok", true);
        res.put("ip", host);
        res.put("state", state);
        res.put("summary", "");
        JSONArray rs = new JSONArray();
        for (String r : reasons) rs.put(r);
        res.put("reasons", rs);
        res.put("toners", toners);
        res.put("capabilities", caps);
        res.put("model", Net.clean(Probe.first(ipp, "printer-make-and-model"), 80));
        res.put("display", display);
        res.put("pjlCode", pjlCode);
        res.put("latencyMs", System.currentTimeMillis() - started);
        res.put("checkedAt", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US) {{ setTimeZone(java.util.TimeZone.getTimeZone("UTC")); }}.format(new java.util.Date()));
        return res;
    }

    private static boolean get(Future<Boolean> f) {
        try {
            return Boolean.TRUE.equals(f.get(3, TimeUnit.SECONDS));
        } catch (Exception e) {
            return false;
        }
    }
}
