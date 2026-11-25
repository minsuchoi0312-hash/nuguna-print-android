// 누구나프린트 앱 아이콘 · 시작 화면 만들기 (안드로이드 res 폴더에 크기별 PNG를 쓴다)
// 사용: node icon/make-icons.mjs   (사본 폴더에서)
import { createRequire } from "node:module";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");
const require = createRequire(join(root, "package.json"));
let chromium;
try {
  ({ chromium } = require("playwright"));
} catch {
  ({ chromium } = createRequire("C:/dev/rocket-plum-cloud-sail/package.json")("playwright"));
}

const BLUE = "#3182F6"; // 토스 블루
const DARK = "#17171C"; // 앱 어두운 바탕

/* 108dp 격자 기준 프린터 그림 — 안드로이드 적응형 아이콘의 안전 영역(지름 66dp) 안에 들어간다 */
const glyph = `
<g transform="translate(0 -1)">
  <rect x="38" y="27" width="32" height="18" rx="4" fill="#fff"/>
  <rect x="24" y="40" width="60" height="30" rx="10" fill="#fff"/>
  <circle cx="74" cy="50" r="3" fill="${BLUE}" opacity="0.5"/>
  <rect x="38" y="60" width="32" height="23" rx="4" fill="#fff" stroke="${BLUE}" stroke-width="3"/>
  <path d="M44.5 68.5h19M44.5 75h12" stroke="${BLUE}" stroke-width="3.2" stroke-linecap="round" fill="none"/>
</g>`;

const scaled = (s) => `<g transform="translate(54 54) scale(${s}) translate(-54 -54)">${glyph}</g>`;

const svgs = {
  /* 적응형 아이콘 앞면(투명 바탕) — 바탕색은 colors 리소스로 */
  foreground: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">${glyph}</svg>`,
  /* 예전 안드로이드용 네모 아이콘 */
  square: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect x="4" y="4" width="100" height="100" rx="24" fill="${BLUE}"/>${scaled(1.25)}</svg>`,
  /* 동그란 아이콘 */
  round: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><circle cx="54" cy="54" r="50" fill="${BLUE}"/>${scaled(1.2)}</svg>`,
  /* 스토어 · 공유용 큰 아이콘 */
  full: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect width="108" height="108" fill="${BLUE}"/>${scaled(1.3)}</svg>`,
};

const res = join(root, "android", "app", "src", "main", "res");
const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };

const browser = await chromium.launch();
const page = await browser.newPage();

async function render(svg, w, h, file, background = "transparent") {
  await page.setViewportSize({ width: w, height: h });
  await page.setContent(`<html><body style="margin:0;background:${background}">${svg.replace("<svg ", `<svg width="${w}" height="${h}" `)}</body></html>`);
  const buf = await page.screenshot({ omitBackground: background === "transparent", clip: { x: 0, y: 0, width: w, height: h } });
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, buf);
}

for (const [name, k] of Object.entries(densities)) {
  const dir = join(res, `mipmap-${name}`);
  await render(svgs.square, Math.round(48 * k), Math.round(48 * k), join(dir, "ic_launcher.png"));
  await render(svgs.round, Math.round(48 * k), Math.round(48 * k), join(dir, "ic_launcher_round.png"));
  await render(svgs.foreground, Math.round(108 * k), Math.round(108 * k), join(dir, "ic_launcher_foreground.png"));
}

/* 시작 화면 — 어두운 바탕 가운데 아이콘 */
const splash = (w, h) => {
  const size = Math.round(Math.min(w, h) * 0.3);
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}"><rect width="${w}" height="${h}" fill="${DARK}"/><svg x="${(w - size) / 2}" y="${(h - size) / 2}" width="${size}" height="${size}" viewBox="0 0 108 108"><rect x="4" y="4" width="100" height="100" rx="24" fill="${BLUE}"/>${scaled(1.25)}</svg></svg>`;
};
const splashes = {
  drawable: [480, 320],
  "drawable-land-mdpi": [480, 320],
  "drawable-land-hdpi": [800, 480],
  "drawable-land-xhdpi": [1280, 720],
  "drawable-land-xxhdpi": [1600, 960],
  "drawable-land-xxxhdpi": [1920, 1280],
  "drawable-port-mdpi": [320, 480],
  "drawable-port-hdpi": [480, 800],
  "drawable-port-xhdpi": [720, 1280],
  "drawable-port-xxhdpi": [960, 1600],
  "drawable-port-xxxhdpi": [1280, 1920],
};
for (const [dir, [w, h]] of Object.entries(splashes)) await render(splash(w, h), w, h, join(res, dir, "splash.png"));

/* 원본 그림 보관 */
await render(svgs.full, 1024, 1024, join(here, "icon-1024.png"));
await render(svgs.square, 512, 512, join(here, "icon-512-rounded.png"));
writeFileSync(join(here, "icon-foreground.svg"), svgs.foreground);
writeFileSync(join(here, "icon.svg"), svgs.square);

await browser.close();
console.log("icons written");
