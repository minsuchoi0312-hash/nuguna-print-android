package com.nuguna.print.printservice;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * PCL5 흑백 래스터(300dpi) — 웹 쪽 src/lib/office-job.ts(pclJobHeader · pclPageCommands · monoFromLuma)와 같은 명령.
 * 코니카 28BW-4 실기에서 양면이 확인된 머리(PJL COPIES/QTY · DUPLEX · BINDING=LONGEDGE)를 그대로 쓴다.
 */
final class PclRaster {
    private PclRaster() {}

    static final int DPI = 300;
    /** 가장자리 6mm(300dpi 72점)는 비운다 — 웹 printableSize 와 같다 */
    static final int EDGE = 72;

    enum Duplex { SIMPLEX, LONG, SHORT }

    /** 미디어 id(안드로이드 MediaSize) → PCL 용지 번호 · PJL 이름 */
    static int pclPaper(String mediaId) {
        switch (mediaId == null ? "" : mediaId) {
            case "ISO_A3": return 27;
            case "ISO_A5": return 25;
            case "ISO_A6": return 24;
            case "JIS_B5": return 45;
            case "JIS_B4": return 46;
            case "NA_LETTER": return 2;
            case "NA_LEGAL": return 3;
            default: return 26; // ISO_A4
        }
    }

    static String pjlPaper(String mediaId) {
        switch (mediaId == null ? "" : mediaId) {
            case "ISO_A3": return "A3";
            case "ISO_A5": return "A5";
            case "ISO_A6": return "A6";
            case "NA_LETTER": return "LETTER";
            case "NA_LEGAL": return "LEGAL";
            case "ISO_A4": return "A4";
            default: return null; // JIS 계열은 기종마다 이름이 달라 생략
        }
    }

    static byte[] header(int copies, Duplex duplex, String mediaId, int pages) {
        int count = Math.min(99, Math.max(1, copies));
        StringBuilder s = new StringBuilder();
        s.append("\u001b%-12345X@PJL JOB NAME=\"NUGUNA PRINT\"\r\n");
        s.append(pages > 1 && count > 1 ? "@PJL SET QTY=" + count : "@PJL SET COPIES=" + count).append("\r\n");
        String paper = pjlPaper(mediaId);
        if (paper != null) s.append("@PJL SET PAPER=").append(paper).append("\r\n");
        if (duplex == Duplex.SIMPLEX) s.append("@PJL SET DUPLEX=OFF\r\n");
        else {
            s.append("@PJL SET DUPLEX=ON\r\n");
            s.append("@PJL SET BINDING=").append(duplex == Duplex.LONG ? "LONGEDGE" : "SHORTEDGE").append("\r\n");
        }
        s.append("@PJL ENTER LANGUAGE=PCL\r\n");
        int side = duplex == Duplex.LONG ? 1 : duplex == Duplex.SHORT ? 2 : 0;
        s.append("\u001bE\u001b&l").append(pclPaper(mediaId)).append("A\u001b&l0O\u001b&l").append(side)
            .append("S\u001b&u300D\u001b&l0E\u001b&l0L\u001b&a0L\u001b*t300R\u001b*r0F");
        return s.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] footer() {
        return "\u001bE\u001b%-12345X@PJL EOJ\r\n\u001b%-12345X".getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] abortFooter() {
        return "\u001b*rC\u001bE\u001b%-12345X@PJL EOJ\r\n\u001b%-12345X".getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void ascii(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * 비트맵 한 장을 흑백으로 바꿔(Floyd–Steinberg, 웹 'dither' 와 같은 문턱) 래스터 명령으로 쓴다.
     * 오차는 두 줄만 들고 다녀 A4 300dpi 에서도 메모리를 적게 쓴다. width 는 8의 배수여야 한다.
     */
    static void page(Bitmap bmp, OutputStream out) throws IOException {
        int width = bmp.getWidth() & ~7;
        int height = bmp.getHeight();
        int bytesPerRow = width / 8;
        ascii(out, "\u001b*p0x" + EDGE + "Y\u001b*r" + width + "S\u001b*r" + height + "T\u001b*r1A\u001b*b2M");
        int[] argb = new int[width];
        float[] cur = new float[width + 2];
        float[] next = new float[width + 2];
        float[] spare = new float[width + 2];
        byte[] packed = new byte[bytesPerRow];
        ByteArrayOutputStream comp = new ByteArrayOutputStream(bytesPerRow + 64);
        int skip = 0;
        boolean anyInk = false;
        /* 첫 줄의 회색값 */
        loadRow(bmp, 0, width, argb, cur);
        for (int y = 0; y < height; y++) {
            java.util.Arrays.fill(next, 0f);
            boolean nextLoaded = y + 1 < height;
            if (nextLoaded) {
                java.util.Arrays.fill(spare, 0f);
                loadRow(bmp, y + 1, width, argb, spare);
            }
            java.util.Arrays.fill(packed, (byte) 0);
            boolean ink = false;
            for (int x = 0; x < width; x++) {
                float old = cur[x + 1];
                float nv = old < 128 ? 0 : 255;
                float e = old - nv;
                if (nv == 0) {
                    packed[x >> 3] |= (byte) (0x80 >> (x & 7));
                    ink = true;
                }
                cur[x + 2] += e * 7 / 16;
                next[x] += e * 3 / 16;
                next[x + 1] += e * 5 / 16;
                next[x + 2] += e / 16;
            }
            if (nextLoaded) {
                for (int i = 1; i <= width; i++) spare[i] += next[i];
                float[] t = cur;
                cur = spare;
                spare = t;
            }
            /* 빈 쪽도 한 장 나오게 마지막 점 하나 (웹과 같음) */
            if (y == height - 1 && !anyInk && !ink && width > 0) {
                packed[bytesPerRow - 1] |= 1;
                ink = true;
            }
            if (!ink) {
                skip++;
                continue;
            }
            anyInk = true;
            if (skip > 0) {
                ascii(out, "\u001b*b" + skip + "Y");
                skip = 0;
            }
            int end = bytesPerRow;
            while (end > 0 && packed[end - 1] == 0) end--;
            comp.reset();
            packBits(packed, end, comp);
            ascii(out, "\u001b*b" + comp.size() + "W");
            comp.writeTo(out);
        }
        if (skip > 0) ascii(out, "\u001b*b" + skip + "Y");
        ascii(out, "\u001b*rC\f");
    }

    /** 회색값(웹 monoFromLuma 와 같이 아주 밝으면 흰색 · 아주 어두우면 검정으로 고정) */
    private static void loadRow(Bitmap bmp, int y, int width, int[] argb, float[] gray) {
        bmp.getPixels(argb, 0, width, 0, y, width, 1);
        for (int x = 0; x < width; x++) {
            int c = argb[x];
            int a = (c >>> 24) & 0xff;
            int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
            float l = r * 0.3f + g * 0.59f + b * 0.11f;
            if (a < 255) l = l * a / 255f + 255f * (255 - a) / 255f; // 투명 = 흰 종이
            if (l >= 240) l = 255;
            else if (l <= 40) l = 0;
            gray[x + 1] = l;
        }
    }

    static void packBits(byte[] row, int len, ByteArrayOutputStream out) {
        int i = 0;
        while (i < len) {
            int run = 1;
            while (i + run < len && row[i + run] == row[i] && run < 128) run++;
            if (run >= 2) {
                out.write((1 - run) & 0xff);
                out.write(row[i]);
                i += run;
                continue;
            }
            int start = i;
            i++;
            while (i < len && i - start < 128) {
                if (i + 1 < len && row[i] == row[i + 1]) break;
                i++;
            }
            int n = i - start;
            out.write(n - 1);
            out.write(row, start, n);
        }
    }
}
