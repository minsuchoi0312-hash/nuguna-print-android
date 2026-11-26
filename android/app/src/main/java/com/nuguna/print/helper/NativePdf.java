package com.nuguna.print.helper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 안드로이드 내장 PDF 엔진(pdfium, android.graphics.pdf.PdfRenderer)으로 PDF 쪽을 그림으로 만든다.
 * 크롬 · 안드로이드가 쓰는 엔진이라 글꼴이 안 들어간 한글 PDF도 기기 글꼴로 제대로 그린다
 * (화면 쪽 pdf.js보다 실제 문서와 똑같이 나온다).
 *
 * 문서는 앱 캐시 폴더에 잠시 파일로 두고, 닫거나 새 문서가 많아지면 지운다.
 * PdfRenderer 는 한 번에 한 쪽만 열 수 있어 문서마다 잠근다.
 */
final class NativePdf {
    private NativePdf() {}

    static final long MAX_BYTES = 100L * 1024 * 1024;
    /* 쪽 그림 한 변의 상한 (A4 300dpi = 2480 × 3508) — 메모리 보호 */
    static final int MAX_SIDE = 5000;
    private static final int MAX_OPEN = 3;

    private static final class Doc {
        final File file;
        final ParcelFileDescriptor fd;
        final PdfRenderer renderer;

        Doc(File file, ParcelFileDescriptor fd, PdfRenderer renderer) {
            this.file = file;
            this.fd = fd;
            this.renderer = renderer;
        }

        void close() {
            try {
                renderer.close();
            } catch (Exception ignored) {
            }
            try {
                fd.close();
            } catch (Exception ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static final Map<String, Doc> docs = new LinkedHashMap<>();
    private static int seq = 0;

    /** 본문으로 받은 PDF를 연다. 암호 · 손상으로 못 열면 null */
    static JSONObject open(Context ctx, InputStream body, long length) throws Exception {
        File dir = new File(ctx.getCacheDir(), "native-pdf");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String id;
        synchronized (NativePdf.class) {
            id = "d" + (++seq) + "-" + Long.toHexString(System.nanoTime());
        }
        File file = new File(dir, id + ".pdf");
        long got = 0;
        try (FileOutputStream out = new FileOutputStream(file)) {
            byte[] buf = new byte[65536];
            while (got < length) {
                int n = body.read(buf, 0, (int) Math.min(buf.length, length - got));
                if (n < 0) break;
                out.write(buf, 0, n);
                got += n;
            }
        }
        if (got != length) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            throw new IOException("short body");
        }
        ParcelFileDescriptor fd = null;
        PdfRenderer renderer;
        try {
            fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(fd);
        } catch (Exception e) {
            /* 암호가 걸렸거나 엔진이 못 읽는 PDF → 화면 쪽이 pdf.js로 대신 그린다 */
            if (fd != null) {
                try {
                    fd.close();
                } catch (Exception ignored) {
                }
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
        Doc doc = new Doc(file, fd, renderer);
        JSONArray pages = new JSONArray();
        synchronized (doc) {
            int count = renderer.getPageCount();
            for (int i = 0; i < count; i++) {
                try (PdfRenderer.Page page = renderer.openPage(i)) {
                    JSONObject p = new JSONObject();
                    p.put("w", page.getWidth());
                    p.put("h", page.getHeight());
                    pages.put(p);
                }
            }
        }
        synchronized (NativePdf.class) {
            docs.put(id, doc);
            /* 오래된 문서부터 닫아 캐시 · 메모리를 아낀다 */
            Iterator<Map.Entry<String, Doc>> it = docs.entrySet().iterator();
            while (docs.size() > MAX_OPEN && it.hasNext()) {
                Map.Entry<String, Doc> oldest = it.next();
                if (oldest.getKey().equals(id)) continue;
                oldest.getValue().close();
                it.remove();
            }
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("id", id);
        o.put("pages", pages);
        return o;
    }

    /** 쪽 하나를 width × height 흰 바탕 그림(PNG)으로. 문서나 쪽이 없으면 null */
    static byte[] page(String id, int index, int width, int height) {
        Doc doc;
        synchronized (NativePdf.class) {
            doc = docs.get(id);
        }
        if (doc == null || width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) return null;
        Bitmap bitmap = null;
        try {
            synchronized (doc) {
                if (index < 0 || index >= doc.renderer.getPageCount()) return null;
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                /* 엔진은 투명 바탕에 그리므로 먼저 흰색으로 */
                bitmap.eraseColor(Color.WHITE);
                try (PdfRenderer.Page page = doc.renderer.openPage(index)) {
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(width * height / 8);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } catch (OutOfMemoryError | RuntimeException e) {
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    static void close(String id) {
        Doc doc;
        synchronized (NativePdf.class) {
            doc = docs.remove(id);
        }
        if (doc != null) {
            synchronized (doc) {
                doc.close();
            }
        }
    }
}
