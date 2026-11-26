/**
 * 안드로이드 앱(Capacitor) 진입점 — 서버 없이 화면만 그린다.
 * 웹 버전은 TanStack Start(src/routes)로 돌고, 앱은 이 파일로 같은 AtlasApp을 그린다.
 */
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { AtlasApp } from "@/components/atlas-app";
import { registerPlugin, SystemBars, SystemBarsStyle } from "@capacitor/core";
import "./styles.css";
import "./android-safe-area.css";

/* 상태바 아이콘 색을 앱 테마에 맞춘다 — 어두운 테마면 흰 아이콘, 밝은 테마면 검은 아이콘 */
function syncSystemBars() {
  const light = document.documentElement.dataset.theme === "light";
  SystemBars.setStyle({ style: light ? SystemBarsStyle.Light : SystemBarsStyle.Dark }).catch(() => {});
}
syncSystemBars();
new MutationObserver(syncSystemBars).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });

/* 앱 안에서는 서비스 워커(오프라인 캐시)를 쓰지 않는다 — 앱을 업데이트해도 예전 화면이 남는 것을 막는다 */
if (typeof navigator !== "undefined" && "serviceWorker" in navigator) {
  navigator.serviceWorker.register = () => Promise.reject(new Error("service worker disabled in app"));
}

/* ───── 폰 내장 프린터 도우미 (android/.../helper) ─────
 * PC 도우미와 같은 주소 · 응답이라 화면은 "도우미가 켜져 있다"고 보고 그대로 검색 · 9100 인쇄를 맡긴다. */
type HelperInfo = { url: string; code: string };
const PhoneHelper = registerPlugin<{ info(): Promise<HelperInfo> }>("PhoneHelper");

async function startPhoneHelper(): Promise<HelperInfo | null> {
  try {
    const info = await PhoneHelper.info();
    (window as { __nugunaPhone?: boolean }).__nugunaPhone = true;
    let saved: { url?: string } | null = null;
    try {
      saved = JSON.parse(localStorage.getItem("nuguna-helper") || "null");
    } catch {
      saved = null;
    }
    /* 직접 넣은 PC 도우미 주소(다른 IP)는 그대로 두고, 비었거나 예전 폰 주소면 지금 코드로 바꾼다 */
    if (!saved?.url || /^http:\/\/127\.0\.0\.1:47631\/?$/.test(saved.url)) {
      localStorage.setItem("nuguna-helper", JSON.stringify({ url: info.url, code: info.code }));
    }
    return info;
  } catch {
    return null;
  }
}

/** 프린터 631(IPP)로 가는 인쇄 요청은 내장 도우미가 대신 전달한다 — WebView는 프린터 CORS에 막히기 때문 */
function routeIppThroughHelper(helper: HelperInfo) {
  const realFetch = window.fetch.bind(window);
  const IPP = /^http:\/\/((?:10|172|192|169)\.\d{1,3}\.\d{1,3}\.\d{1,3}):631(\/[A-Za-z0-9/_.-]*)?$/;
  window.fetch = (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    const hit = IPP.exec(url);
    const method = (init?.method || (input instanceof Request ? input.method : "GET")).toUpperCase();
    if (hit && method === "POST") {
      const path = hit[2] || "/ipp/print";
      return realFetch(`${helper.url}/ipp?host=${encodeURIComponent(hit[1])}&path=${encodeURIComponent(path)}`, {
        method: "POST",
        headers: { "Content-Type": "application/ipp", "X-Helper-Code": helper.code },
        body: init?.body ?? (input instanceof Request ? input.body : undefined),
        signal: init?.signal,
      });
    }
    return realFetch(input, init);
  };
}

/** 내장 도우미가 실제로 답할 때까지 잠깐 기다린다(앱을 닫았다 바로 열면 포트가 몇 초 늦게 열린다) */
async function waitHelper(helper: HelperInfo, ms = 6000) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    try {
      const res = await fetch(`${helper.url}/ping`, { cache: "no-store" });
      if (res.ok) return true;
    } catch {
      /* 아직 안 열림 */
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  return false;
}

void startPhoneHelper().then(async (helper) => {
  if (helper) {
    routeIppThroughHelper(helper);
    await waitHelper(helper);
  }
  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <AtlasApp />
    </StrictMode>,
  );
});
