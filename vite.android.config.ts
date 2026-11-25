/**
 * 안드로이드 앱용 화면 빌드 — 서버(TanStack Start · Nitro) 없이 index.html + React 만.
 * 결과물은 dist-android/ 에 나오고, Capacitor가 그대로 APK에 담는다.
 */
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import viteReact from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  root: ".",
  base: "./",
  publicDir: "public",
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  plugins: [tailwindcss(), viteReact()],
  build: {
    outDir: "dist-android",
    emptyOutDir: true,
    target: "es2022",
  },
});
