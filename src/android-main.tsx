/**
 * 안드로이드 앱(Capacitor) 진입점 — 서버 없이 화면만 그린다.
 * 웹 버전은 TanStack Start(src/routes)로 돌고, 앱은 이 파일로 같은 AtlasApp을 그린다.
 */
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { AtlasApp } from "@/components/atlas-app";
import "./styles.css";

/* 앱 안에서는 서비스 워커(오프라인 캐시)를 쓰지 않는다 — 앱을 업데이트해도 예전 화면이 남는 것을 막는다 */
if (typeof navigator !== "undefined" && "serviceWorker" in navigator) {
  navigator.serviceWorker.register = () => Promise.reject(new Error("service worker disabled in app"));
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <AtlasApp />
  </StrictMode>,
);
