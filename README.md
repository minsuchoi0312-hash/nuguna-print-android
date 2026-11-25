# 누구나프린트 — 안드로이드 앱 사본

웹앱 [rocket-plum-cloud-sail](https://github.com/minsuchoi0312-hash/rocket-plum-cloud-sail)을 Capacitor로 감싼 **안드로이드 APK 전용 사본**이에요.
화면 코드는 원본에서 가져오고, 이 저장소에는 앱으로 만드는 데 필요한 것만 더해져 있어요.

## 원본과 다른 파일

| 파일 | 하는 일 |
|---|---|
| `index.html`, `src/android-main.tsx` | 서버 없이 화면만 그리는 앱 진입점 |
| `vite.android.config.ts` | 앱용 화면 빌드 → `dist-android/` |
| `capacitor.config.json` | 앱 이름 · 패키지(`com.nuguna.print`) · 복사기 접속 설정 |
| `android/` | 안드로이드 프로젝트 (아이콘 · 시작 화면 포함) |
| `icon/` | 앱 아이콘 원본과 생성 스크립트 `make-icons.mjs` |
| `build-apk.ps1` | 원본의 최신 화면 코드를 가져와 APK까지 한 번에 |

## APK 다시 만들기 (Windows)

준비물: Android Studio(안드로이드 SDK), Java 21(`C:\dev\tools\jdk21-extract\`), 원본 폴더 `C:\dev\rocket-plum-cloud-sail`

```powershell
npm install
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
```

결과: `누구나프린트-debug.apk` (테스트용 서명)

## 앱의 한계

- 안드로이드 앱 화면(WebView)에는 WebUSB가 없어 **USB 선 인쇄는 안 돼요.**
- 와이파이 직접 인쇄는 Capacitor HTTP로 열어 두었고, 실제 복사기에서 확인이 필요해요.
