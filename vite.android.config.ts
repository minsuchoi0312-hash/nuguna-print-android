/**
 * 안드로이드 앱용 화면 빌드 — 서버(TanStack Start · Nitro) 없이 index.html + React 만.
 * 결과물은 dist-android/ 에 나오고, Capacitor가 그대로 APK에 담는다.
 */
import { fileURLToPath } from "node:url";
import { defineConfig, type Plugin } from "vite";
import viteReact from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

/**
 * 글꼴은 .woff2 만 싣는다: @fontsource CSS 는 옛 브라우저용 .woff 도 함께 적어 두는데,
 * 앱 WebView(크롬)는 늘 woff2 를 쓰므로 .woff 는 APK 용량만 늘린다.
 */
function woff2Only(): Plugin {
  return {
    name: "nuguna-woff2-only",
    enforce: "pre",
    transform(code, id) {
      if (!/[\\/]@fontsource[\\/].*\.css(\?.*)?$/.test(id)) return null;
      return { code: code.replace(/,\s*url\([^)]*\.woff\)\s*format\(["']woff["']\)/g, ""), map: null };
    },
  };
}

/**
 * 앱은 Pretendard(글자 범위별 woff2 92개, 약 3MB)를 싣지 않고 폰 기본 글꼴로 그린다.
 * styles.css 의 글꼴 목록이 Roboto · Noto Sans · Noto Sans CJK KR … 로 이어지므로 화면 · 인쇄 캔버스 모두 폰 글꼴을 쓴다.
 * 웹 빌드(vite.config.ts)는 그대로 Pretendard 를 쓴다.
 */
function noPretendard(): Plugin {
  return {
    name: "nuguna-no-pretendard",
    enforce: "pre",
    transform(code, id) {
      if (!/[\\/]src[\\/]styles\.css(\?.*)?$/.test(id)) return null;
      return { code: code.replace(/^@import\s+["']pretendard\/[^"']+["'];\s*$/m, ""), map: null };
    },
  };
}

export default defineConfig({
  root: ".",
  base: "./",
  publicDir: "public",
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  plugins: [noPretendard(), woff2Only(), tailwindcss(), viteReact()],
  build: {
    outDir: "dist-android",
    emptyOutDir: true,
    target: "es2022",
  },
});
