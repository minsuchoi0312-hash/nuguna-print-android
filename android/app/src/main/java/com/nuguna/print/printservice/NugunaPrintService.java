package com.nuguna.print.printservice;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrintJobInfo;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintDocument;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;
import android.util.Log;

import com.nuguna.print.helper.Discovery;
import com.nuguna.print.helper.Net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 안드로이드 시스템 인쇄 서비스 — 설정 → 연결된 기기 → 인쇄(삼성: 모든 프린터 → 프린터 추가)에 '누구나프린트'로 보인다.
 * 다른 앱(갤러리 · 크롬 · 문서 앱)의 '인쇄'에서 고른 문서를 폰에서 300dpi 흑백 PCL 래스터로 바꿔 9100 으로 보낸다.
 * 글꼴이 없는 한글 PDF도 폰이 그림으로 그려 보내므로 복사기 글꼴과 상관없다.
 */
public class NugunaPrintService extends PrintService {
    private static final String TAG = "NugunaPrintService";
    /** 6mm = 236 mil — 이 안쪽은 비운다 (PclRaster.EDGE 와 같다) */
    private static final int MARGIN_MILS = 236;
    private static final long MAX_PDF_BYTES = 120L * 1024 * 1024;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService jobs = Executors.newSingleThreadExecutor();
    /** 취소된 작업 id (PrintJob 메서드는 메인 스레드 전용이라 id 문자열만 넘긴다) */
    private final Set<String> cancelled = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Override
    public void onCreate() {
        super.onCreate();
        Net.init(this);
    }

    @Override
    public void onDestroy() {
        jobs.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        return new Session();
    }

    @Override
    protected void onRequestCancelPrintJob(PrintJob job) {
        cancelled.add(String.valueOf(job.getId()));
        if (job.isQueued()) job.cancel();
    }

    @Override
    protected void onPrintJobQueued(final PrintJob job) {
        if (!job.isQueued()) return;
        final PrintJobInfo info = job.getInfo();
        final PrintDocument doc = job.getDocument();
        final ParcelFileDescriptor data = doc.getData();
        final String key = String.valueOf(job.getId());
        job.start();
        jobs.execute(() -> run(job, key, info, data));
    }

    /* ───────── 인쇄 ───────── */

    /** 백그라운드 스레드 — job 은 main.post 안에서만 만진다 */
    private void run(PrintJob job, String key, PrintJobInfo info, ParcelFileDescriptor data) {
        File pdf = new File(new File(getCacheDir(), "printservice"), "job.pdf");
        String fail = null;
        boolean cancel = false;
        try {
            if (data == null) throw new IOException("no document");
            //noinspection ResultOfMethodCallIgnored
            pdf.getParentFile().mkdirs();
            try (InputStream in = new FileInputStream(data.getFileDescriptor()); OutputStream out = new FileOutputStream(pdf)) {
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_PDF_BYTES) throw new IOException("too large");
                    out.write(buf, 0, n);
                }
            } finally {
                Net.closeQuietly(data);
            }
            String host = info.getPrinterId() == null ? "" : info.getPrinterId().getLocalId();
            if (!Net.isIPv4(host) || !Net.isPrivate(host)) throw new IOException("bad printer");
            cancel = send(key, host, info, pdf);
            if (!cancel) KnownPrinters.remember(this, host, "");
        } catch (Throwable e) { // 메모리 부족(큰 용지)도 앱이 꺼지지 않고 작업 실패로
            Log.w(TAG, "print failed: " + e.getClass().getSimpleName());
            fail = e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException || e instanceof java.net.NoRouteToHostException
                ? "복사기에 연결하지 못했어요. 같은 와이파이인지 확인해 주세요."
                : "인쇄하지 못했어요. 누구나프린트 앱에서 다시 해 보세요.";
        } finally {
            //noinspection ResultOfMethodCallIgnored
            pdf.delete();
            cancelled.remove(key);
        }
        final String why = fail;
        final boolean wasCancelled = cancel;
        main.post(() -> {
            if (wasCancelled) {
                if (!job.isCancelled() && !job.isCompleted() && !job.isFailed()) job.cancel();
            } else if (why != null) {
                if (!job.isFailed() && !job.isCancelled()) job.fail(why);
            } else if (!job.isCompleted() && !job.isCancelled()) job.complete();
        });
    }

    /** @return 사용자가 취소했으면 true */
    private boolean send(String key, String host, PrintJobInfo info, File pdf) throws IOException {
        PrintAttributes attrs = info.getAttributes();
        PrintAttributes.MediaSize media = attrs.getMediaSize() != null ? attrs.getMediaSize() : PrintAttributes.MediaSize.ISO_A4;
        PrintAttributes.MediaSize portrait = media.asPortrait();
        String mediaId = portrait.getId();
        PclRaster.Duplex duplex = PclRaster.Duplex.SIMPLEX;
        if (attrs.getDuplexMode() == PrintAttributes.DUPLEX_MODE_LONG_EDGE) duplex = PclRaster.Duplex.LONG;
        else if (attrs.getDuplexMode() == PrintAttributes.DUPLEX_MODE_SHORT_EDGE) duplex = PclRaster.Duplex.SHORT;
        int copies = Math.max(1, info.getCopies());

        int fullW = (int) Math.ceil(Math.round(portrait.getWidthMils() * PclRaster.DPI / 1000.0) / 8.0) * 8;
        int fullH = (int) Math.round(portrait.getHeightMils() * PclRaster.DPI / 1000.0);
        int w = Math.max(8, (fullW - 2 * PclRaster.EDGE) & ~7);
        int h = Math.max(8, fullH - 2 * PclRaster.EDGE);

        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd)) {
            int pages = renderer.getPageCount();
            if (pages < 1) throw new IOException("empty document");
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Socket printer = Net.connect(host, 9100, 5000);
            try {
                printer.setSoTimeout(60_000);
                OutputStream out = new BufferedOutputStream(printer.getOutputStream(), 65536);
                out.write(PclRaster.header(copies, duplex, mediaId, pages));
                for (int i = 0; i < pages; i++) {
                    if (cancelled.contains(key)) {
                        out.write(PclRaster.abortFooter());
                        out.flush();
                        return true;
                    }
                    bmp.eraseColor(Color.WHITE);
                    try (PdfRenderer.Page page = renderer.openPage(i)) {
                        page.render(bmp, null, fit(page.getWidth(), page.getHeight(), fullW, fullH), PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                    }
                    PclRaster.page(bmp, out);
                }
                /* 양면 · 여러 부 · 홀수 쪽이면 다음 부가 뒷면에 붙지 않게 빈 쪽 (웹 needsBlankBack 과 같음) */
                if (duplex != PclRaster.Duplex.SIMPLEX && pages % 2 == 1 && copies > 1) {
                    bmp.eraseColor(Color.WHITE);
                    PclRaster.page(bmp, out);
                }
                out.write(PclRaster.footer());
                out.flush();
                printer.shutdownOutput();
            } finally {
                Net.closeQuietly(printer);
                bmp.recycle();
            }
        }
        return false;
    }

    /**
     * PDF 쪽(포인트)을 300dpi 세로 용지에 맞춘다. 가로 쪽은 90도 돌려 세로 용지에 앉히고,
     * 크기가 다르면 비율을 지켜 줄이거나 늘려 가운데에 둔다. 마지막에 가장자리 6mm 만큼 당겨 인쇄 영역 비트맵에 맞춘다.
     */
    private static Matrix fit(int pw, int ph, int fullW, int fullH) {
        Matrix m = new Matrix();
        boolean turn = pw > ph;
        float rw = turn ? ph : pw;
        float rh = turn ? pw : ph;
        if (turn) {
            m.postRotate(90);
            m.postTranslate(ph, 0);
        }
        float s = Math.min(fullW / rw, fullH / rh);
        m.postScale(s, s);
        m.postTranslate((fullW - rw * s) / 2f - PclRaster.EDGE, (fullH - rh * s) / 2f - PclRaster.EDGE);
        return m;
    }

    /* ───────── 프린터 찾기 ───────── */

    private PrinterCapabilitiesInfo caps(PrinterId id) {
        boolean letter = Locale.US.getCountry().equals(Locale.getDefault().getCountry()) || "CA".equals(Locale.getDefault().getCountry());
        PrinterCapabilitiesInfo.Builder b = new PrinterCapabilitiesInfo.Builder(id);
        b.addMediaSize(PrintAttributes.MediaSize.ISO_A4, !letter);
        b.addMediaSize(PrintAttributes.MediaSize.NA_LETTER, letter);
        b.addMediaSize(PrintAttributes.MediaSize.ISO_A3, false);
        b.addMediaSize(PrintAttributes.MediaSize.ISO_A5, false);
        b.addMediaSize(PrintAttributes.MediaSize.JIS_B5, false);
        b.addMediaSize(PrintAttributes.MediaSize.JIS_B4, false);
        b.addMediaSize(PrintAttributes.MediaSize.NA_LEGAL, false);
        b.addResolution(new PrintAttributes.Resolution("300", "300 dpi", 300, 300), true);
        b.setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME);
        b.setDuplexModes(PrintAttributes.DUPLEX_MODE_NONE | PrintAttributes.DUPLEX_MODE_LONG_EDGE | PrintAttributes.DUPLEX_MODE_SHORT_EDGE,
            PrintAttributes.DUPLEX_MODE_NONE);
        b.setMinMargins(new PrintAttributes.Margins(MARGIN_MILS, MARGIN_MILS, MARGIN_MILS, MARGIN_MILS));
        return b.build();
    }

    private static String label(String host, String model) {
        return model == null || model.trim().isEmpty() ? "프린터 " + host : model.trim();
    }

    private final class Session extends PrinterDiscoverySession {
        private final ExecutorService work = Executors.newCachedThreadPool();
        private final Map<String, String> names = new ConcurrentHashMap<>();
        private volatile boolean discovering;

        /** 상태를 확인한 프린터(host → 상태). 늦게 온 검색 결과가 기능 정보를 지우지 않게 늘 같이 싣는다 */
        private final Map<String, Integer> tracked = new ConcurrentHashMap<>();

        private PrinterInfo info(String host, int status, boolean withCaps) {
            Integer known = tracked.get(host);
            if (withCaps) tracked.put(host, status);
            else if (known != null) {
                withCaps = true;
                status = known;
            }
            PrinterId id = generatePrinterId(host);
            PrinterInfo.Builder b = new PrinterInfo.Builder(id, label(host, names.get(host)), status)
                .setDescription("누구나프린트 · " + host);
            if (withCaps) b.setCapabilities(caps(id));
            return b.build();
        }

        /** 백그라운드에서 찾은 결과를 메인 스레드에서 PrinterInfo 로 만들어 올린다 (generatePrinterId 는 메인 스레드 전용) */
        private void post(final List<String> hosts, final int status, final boolean withCaps) {
            if (hosts.isEmpty()) return;
            main.post(() -> {
                if (isDestroyed()) return;
                List<PrinterInfo> list = new ArrayList<>();
                for (String h : hosts) list.add(info(h, status, withCaps));
                addPrinters(list);
            });
        }

        @Override
        public void onStartPrinterDiscovery(List<PrinterId> priorityList) {
            discovering = true;
            /* 1) 앱에서 쓴 프린터는 바로 */
            List<PrinterInfo> known = new ArrayList<>();
            for (KnownPrinters.Item it : KnownPrinters.list(NugunaPrintService.this)) {
                if (!it.name.isEmpty()) names.put(it.host, it.name);
                known.add(info(it.host, PrinterInfo.STATUS_IDLE, false));
            }
            if (!known.isEmpty()) addPrinters(known);
            /* 2) 같은 와이파이에서 9100 프린터 찾기 */
            work.execute(() -> {
                try {
                    JSONObject res = Discovery.discover(NugunaPrintService.this, "", false);
                    JSONArray arr = res.optJSONArray("printers");
                    List<String> found = new ArrayList<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject p = arr.optJSONObject(i);
                            if (p == null || !p.optBoolean("raw9100") || p.optBoolean("canon")) continue;
                            String host = p.optString("host");
                            if (!Net.isIPv4(host) || !Net.isPrivate(host)) continue;
                            String model = Net.clean(p.optString("model"), 80);
                            if (!model.isEmpty() && !names.containsKey(host)) names.put(host, model);
                            found.add(host);
                        }
                    }
                    if (discovering) post(found, PrinterInfo.STATUS_IDLE, false);
                } catch (Throwable e) {
                    Log.w(TAG, "discover failed: " + e.getClass().getSimpleName());
                }
            });
        }

        @Override
        public void onStopPrinterDiscovery() {
            discovering = false;
        }

        @Override
        public void onValidatePrinters(List<PrinterId> printerIds) {
        }

        @Override
        public void onStartPrinterStateTracking(final PrinterId printerId) {
            final String host = printerId.getLocalId();
            work.execute(() -> {
                boolean open = Net.isIPv4(host) && Net.isPrivate(host) && Net.portOpen(host, 9100, 1500);
                post(Collections.singletonList(host), open ? PrinterInfo.STATUS_IDLE : PrinterInfo.STATUS_UNAVAILABLE, true);
            });
        }

        @Override
        public void onStopPrinterStateTracking(PrinterId printerId) {
        }

        @Override
        public void onDestroy() {
            discovering = false;
            work.shutdownNow();
        }
    }
}
