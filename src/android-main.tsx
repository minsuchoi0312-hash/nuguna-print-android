/**
 * 안드로이드 앱(Capacitor) 진입점 — 서버 없이 화면만 그린다.
 * 웹 버전은 TanStack Start(src/routes)로 돌고, 앱은 이 파일로 같은 AtlasApp을 그린다.
 */
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { AtlasApp } from "@/components/atlas-app";
import { registerPlugin, SystemBars, SystemBarsStyle } from "@capacitor/core";
/* 글꼴을 앱 안에서 불러온다(Google Fonts 접속 없음 → 개인정보처리방침 '외부 전송 없음'과 맞춤) */
import "@fontsource/gowun-batang/400.css";
import { disableServiceWorker, installDownloads, installWebUsb } from "./android-native";
import "./styles.css";
import "./android-safe-area.css";

/* 상태바 아이콘 색을 앱 테마에 맞춘다 — 어두운 테마면 흰 아이콘, 밝은 테마면 검은 아이콘 */
function syncSystemBars() {
  const light = document.documentElement.dataset.theme === "light";
  SystemBars.setStyle({ style: light ? SystemBarsStyle.Light : SystemBarsStyle.Dark }).catch(() => {});
}
syncSystemBars();
new MutationObserver(syncSystemBars).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });

/* 앱 안에서는 서비스 워커(오프라인 캐시)를 쓰지 않는다 — 예전 것도 지워 앱을 업데이트해도 옛 화면이 남지 않게.
   USB OTG(navigator.usb)와 <a download> 저장은 네이티브(NugunaNativePrint)로 잇는다 — 화면을 그리기 전에. */
disableServiceWorker();
installWebUsb();
installDownloads();

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

/* ───── 다른 앱의 '공유 → 누구나프린트' ─────
 * MainActivity 가 받은 파일을 내장 도우미(/shared)에서 꺼내 화면(window.__nugunaOpenFiles)에 넘긴다.
 * 같은 묶음(batch)을 두 번 열지 않게 이번 실행에서 연 번호를 기억한다. */
type SharedList = { batch: number; files: { i: number; name: string; mime: string }[] };
let openedBatch = Number(sessionStorage.getItem("nuguna-shared-batch") || "0");

function screenReady(): Promise<(files: File[]) => void> {
  const host = window as { __nugunaOpenFiles?: (files: File[]) => void };
  if (host.__nugunaOpenFiles) return Promise.resolve(host.__nugunaOpenFiles);
  return new Promise((resolve) => {
    window.addEventListener("nuguna-files-ready", () => resolve(host.__nugunaOpenFiles!), { once: true });
  });
}

async function pullShared(helper: HelperInfo) {
  try {
    const headers = { "X-Helper-Code": helper.code };
    const res = await fetch(`${helper.url}/shared`, { headers, cache: "no-store" });
    if (!res.ok) return;
    const list = (await res.json()) as SharedList;
    if (!list.files?.length || list.batch <= openedBatch) return;
    openedBatch = list.batch;
    sessionStorage.setItem("nuguna-shared-batch", String(list.batch));
    const files: File[] = [];
    for (const f of list.files) {
      const one = await fetch(`${helper.url}/shared/file?batch=${list.batch}&i=${f.i}`, { headers, cache: "no-store" });
      if (!one.ok) continue;
      files.push(new File([await one.blob()], f.name || "shared", { type: f.mime }));
    }
    if (files.length) (await screenReady())(files);
  } catch {
    /* 공유 파일을 못 읽어도 앱은 평소처럼 연다 */
  }
}

void startPhoneHelper().then(async (helper) => {
  if (helper) {
    routeIppThroughHelper(helper);
    await waitHelper(helper);
    (window as { __nugunaShared?: () => void }).__nugunaShared = () => void pullShared(helper);
  }
  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <AtlasApp />
    </StrictMode>,
  );
  if (helper) void pullShared(helper);
});
