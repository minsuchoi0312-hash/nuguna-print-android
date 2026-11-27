/**
 * 앱(APK) 전용 네이티브 연결 — android-main.tsx 가 화면을 그리기 전에 부른다. 웹 코드는 건드리지 않는다.
 *
 * 1) navigator.usb: 안드로이드 WebView에는 WebUSB가 없다. NugunaNativePrint(UsbManager)로 같은 모양을 만들어
 *    웹의 USB 인쇄 코드(src/lib/discover.ts)가 그대로 돈다 — 장치 고르기 · GET_DEVICE_ID · Bulk OUT 전송.
 * 2) <a download>: WebView는 무시하므로 가로채서 '다운로드/NugunaPrint'에 저장한다(PDF 저장 · QR 카드 · 로그 백업).
 * 3) 서비스 워커: 앱에서는 쓰지 않고, 예전에 등록된 것이 있으면 지운다(업데이트 후 옛 화면이 남지 않게).
 *
 * 이 파일은 안드로이드 사본에만 있다(build-*.ps1 이 원본 src 를 덮어써도 남는다).
 */
import { registerPlugin, type PluginListenerHandle } from "@capacitor/core";
import { tr } from "@/lib/i18n/core";

/* ───── 네이티브 쪽 모양 (android/.../helper/NugunaNativePrintPlugin.java) ───── */
type NativeEndpoint = { endpointNumber: number; direction: "in" | "out"; type: string };
type NativeAlt = { alternateSetting: number; interfaceClass: number; interfaceSubclass: number; interfaceProtocol: number; endpoints: NativeEndpoint[] };
type NativeIface = { interfaceNumber: number; alternates: NativeAlt[] };
type NativeConfig = { configurationValue: number; interfaces: NativeIface[] };
type NativeDevice = {
  id: string;
  vendorId: number;
  productId: number;
  deviceClass: number;
  permission: boolean;
  productName: string;
  manufacturerName: string;
  serialNumber: string;
  configurations: NativeConfig[];
};
type Setup = { requestType: string; recipient: string; request: number; value: number; index: number };

type NativePrint = {
  usbList(): Promise<{ devices: NativeDevice[] }>;
  usbRequestPermission(o: { id: string }): Promise<{ granted: boolean; device: NativeDevice }>;
  usbOpen(o: { id: string }): Promise<void>;
  usbClose(o: { id: string }): Promise<void>;
  usbClaim(o: { id: string; iface: number }): Promise<void>;
  usbRelease(o: { id: string; iface: number }): Promise<void>;
  usbSelectAlternate(o: { id: string; iface: number; alt: number }): Promise<void>;
  usbControlIn(o: Setup & { id: string; length: number }): Promise<{ status: string; data: string }>;
  usbClearHalt(o: { id: string; endpoint: number; direction: string }): Promise<void>;
  usbTransferOut(o: { id: string; endpoint: number; data: string }): Promise<{ status: string; bytesWritten: number }>;
  saveFile(o: { name: string; mime: string; data: string }): Promise<{ saved: boolean; where?: string; shared?: boolean }>;
  addListener(event: "usb", fn: (e: { type: "connect" | "disconnect"; device: NativeDevice }) => void): Promise<PluginListenerHandle>;
};

export const NativePrint = registerPlugin<NativePrint>("NugunaNativePrint");

/* ───── base64 ↔ 바이트 ───── */
export function toBase64(bytes: Uint8Array) {
  let out = "";
  for (let i = 0; i < bytes.length; i += 0x8000) out += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(out);
}

export function fromBase64(text: string) {
  const raw = atob(text);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i += 1) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

function bytesOf(data: BufferSource) {
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
}

/* ───── 1) navigator.usb ───── */

/** WebUSB USBDevice 의 쓰는 부분만: 같은 장치는 늘 같은 객체(웹 코드가 객체로 장치를 구분한다) */
class AppUsbDevice {
  id: string;
  vendorId = 0;
  productId = 0;
  deviceClass = 0;
  productName = "";
  manufacturerName = "";
  serialNumber = "";
  configurations: NativeConfig[] = [];
  opened = false;

  constructor(n: NativeDevice) {
    this.id = n.id;
    this.update(n);
  }

  update(n: NativeDevice) {
    this.vendorId = n.vendorId;
    this.productId = n.productId;
    this.deviceClass = n.deviceClass;
    this.productName = n.productName;
    this.manufacturerName = n.manufacturerName;
    this.serialNumber = n.serialNumber || this.serialNumber;
    this.configurations = n.configurations;
  }

  /** 안드로이드는 장치를 늘 첫 설정으로 잡아 둔다 */
  get configuration() {
    return this.configurations[0] ?? null;
  }

  async open() {
    await NativePrint.usbOpen({ id: this.id });
    this.opened = true;
  }

  async close() {
    await NativePrint.usbClose({ id: this.id });
    this.opened = false;
  }

  async selectConfiguration() {
    /* 안드로이드에서는 바꿀 수 없다 — 지금 설정을 쓴다 */
  }

  claimInterface(iface: number) {
    return NativePrint.usbClaim({ id: this.id, iface });
  }

  releaseInterface(iface: number) {
    return NativePrint.usbRelease({ id: this.id, iface });
  }

  selectAlternateInterface(iface: number, alt: number) {
    return NativePrint.usbSelectAlternate({ id: this.id, iface, alt });
  }

  clearHalt(direction: "in" | "out", endpoint: number) {
    return NativePrint.usbClearHalt({ id: this.id, endpoint, direction });
  }

  async controlTransferIn(setup: Setup, length: number) {
    const r = await NativePrint.usbControlIn({ id: this.id, length, ...setup });
    const bytes = fromBase64(r.data || "");
    return { status: r.status, data: new DataView(bytes.buffer) };
  }

  async transferOut(endpoint: number, data: BufferSource) {
    return NativePrint.usbTransferOut({ id: this.id, endpoint, data: toBase64(bytesOf(data)) });
  }
}

type Filter = { vendorId?: number; productId?: number; classCode?: number };

function matches(d: NativeDevice, filters: Filter[]) {
  if (!filters.length) return true;
  const classes = new Set<number>([d.deviceClass]);
  for (const c of d.configurations) for (const i of c.interfaces) for (const a of i.alternates) classes.add(a.interfaceClass);
  return filters.some((f) => (f.vendorId == null || f.vendorId === d.vendorId) && (f.productId == null || f.productId === d.productId) && (f.classCode == null || classes.has(f.classCode)));
}

function isPrinter(d: NativeDevice) {
  return d.configurations.some((c) => c.interfaces.some((i) => i.alternates.some((a) => a.interfaceClass === 7)));
}

function notFound(message: string) {
  return new DOMException(message, "NotFoundError");
}

export function installWebUsb() {
  const devices = new Map<string, AppUsbDevice>();
  const target = new EventTarget();
  const objectFor = (n: NativeDevice) => {
    const known = devices.get(n.id);
    if (known) {
      known.update(n);
      return known;
    }
    const made = new AppUsbDevice(n);
    devices.set(n.id, made);
    return made;
  };

  const usb = {
    async getDevices() {
      const { devices: list } = await NativePrint.usbList();
      return list.filter((d) => d.permission).map(objectFor);
    },
    /** 크롬의 장치 고르기 창 대신: 맞는 장치 중 프린터(클래스 7)를 먼저 골라 안드로이드 권한 창을 띄운다 */
    async requestDevice(options: { filters?: Filter[] } = {}) {
      const { devices: list } = await NativePrint.usbList();
      const hits = list.filter((d) => matches(d, options.filters ?? []));
      const pick = hits.find(isPrinter) ?? hits[0];
      /* 꽂힌 장치가 없음 → "선을 꽂고 다시 눌러 주세요" (취소가 아니라 안내) */
      if (!pick) throw new Error(tr("ap.plugUsb"));
      const r = await NativePrint.usbRequestPermission({ id: pick.id });
      /* 안드로이드 권한 창에서 '거부' = 크롬 고르기 창 취소와 같게 */
      if (!r.granted) throw notFound("USB permission denied");
      return objectFor(r.device);
    },
    addEventListener: (type: string, fn: EventListenerOrEventListenerObject) => target.addEventListener(type, fn),
    removeEventListener: (type: string, fn: EventListenerOrEventListenerObject) => target.removeEventListener(type, fn),
  };

  void NativePrint.addListener("usb", (e) => {
    const device = objectFor(e.device);
    if (e.type === "disconnect") {
      device.opened = false;
      devices.delete(e.device.id);
    } else if (!e.device.permission) {
      /* 권한이 없는 새 장치는 화면이 연결 버튼으로 다시 고르게 둔다(웹 크롬과 같다) */
      return;
    }
    const ev = new Event(e.type) as Event & { device?: AppUsbDevice };
    ev.device = device;
    target.dispatchEvent(ev);
  });

  Object.defineProperty(navigator, "usb", { value: usb, configurable: true });
}

/* ───── 2) <a download> → 네이티브 저장 ───── */

async function saveFrom(href: string, name: string) {
  try {
    const blob = await (await fetch(href)).blob();
    const bytes = new Uint8Array(await blob.arrayBuffer());
    await NativePrint.saveFile({ name: name || "nuguna-print", mime: blob.type || "application/octet-stream", data: toBase64(bytes) });
  } catch {
    /* 저장 실패는 조용히 — 화면의 인쇄 흐름을 막지 않는다 */
  }
}

/** 이름 정하기: download 속성 → 주소의 파일 이름 */
function nameOf(link: HTMLAnchorElement) {
  const given = link.getAttribute("download");
  if (given) return given;
  try {
    return decodeURIComponent(new URL(link.href).pathname.split("/").pop() || "");
  } catch {
    return "";
  }
}

export function installDownloads() {
  const take = (link: HTMLAnchorElement) => {
    if (!link.hasAttribute("download") || !link.href) return false;
    /* fetch 는 지금 바로 시작해야 한다: 웹 코드가 click() 직후 blob 주소를 지운다 */
    void saveFrom(link.href, nameOf(link));
    return true;
  };
  /* 화면에 붙지 않은 링크의 link.click() */
  const realClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function (this: HTMLAnchorElement) {
    if (take(this)) return;
    realClick.call(this);
  };
  /* 화면에 있는 <a download> 를 손으로 누를 때 */
  document.addEventListener(
    "click",
    (event) => {
      const link = (event.target as Element | null)?.closest?.("a[download]") as HTMLAnchorElement | null;
      if (link && take(link)) event.preventDefault();
    },
    true,
  );
}

/* ───── 3) 서비스 워커 끄기 ───── */

export function disableServiceWorker() {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
  const sw = navigator.serviceWorker;
  sw.register = () => Promise.reject(new Error("service worker disabled in app"));
  void sw
    .getRegistrations()
    .then((list) => Promise.all(list.map((r) => r.unregister())))
    .catch(() => {});
  if ("caches" in window) {
    void caches
      .keys()
      .then((keys) => Promise.all(keys.map((k) => caches.delete(k))))
      .catch(() => {});
  }
}
