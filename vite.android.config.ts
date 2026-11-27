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

export default defineConfig({
  root: ".",
  base: "./",
  publicDir: "public",
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  plugins: [woff2Only(), tailwindcss(), viteReact()],
  build: {
    outDir: "dist-android",
    emptyOutDir: true,
    target: "es2022",
  },
});
