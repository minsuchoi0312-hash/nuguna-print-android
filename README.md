# 누구나프린트 — 안드로이드 앱

웹앱 [rocket-plum-cloud-sail](https://github.com/minsuchoi0312-hash/rocket-plum-cloud-sail)을 Capacitor 8로 감싼 안드로이드 앱이에요.
화면 코드는 원본에서 가져오고, 이 저장소에는 앱으로 만드는 데 필요한 것만 더해져 있어요.

- 최신 버전: **5.0** (versionCode 50) · APK는 [Releases](https://github.com/minsuchoi0312-hash/nuguna-print-android/releases)에서 받아요
- 패키지: `com.nuguna.print` · 최소 안드로이드 7.0 (API 24)

## 웹과 다른 점

- 복사기 9100 포트로 바로 보내요 (PC 도우미 필요 없음)
- **USB OTG 인쇄**: 폰에 USB 선으로 프린터를 꽂아서 인쇄
- **공유로 인쇄**: 다른 앱에서 공유 → 누구나프린트 (사진 · PDF · 글)
- **앱 링크**: `nugunaprint://connect`, QR로 프린터 연결
- 안드로이드 내장 PDF 엔진(pdfium)을 예비로 써요

## 원본과 다른 파일

| 파일 | 하는 일 |
|---|---|
| `index.html`, `src/android-main.tsx` | 서버 없이 화면만 그리는 앱 진입점 |
| `vite.android.config.ts` | 앱용 화면 빌드 → `dist-android/` |
| `capacitor.config.json` | 앱 이름 · 패키지 · 복사기 접속 설정 |
| `android/` | 안드로이드 프로젝트 (네이티브 인쇄 플러그인, 아이콘, 시작 화면) |
| `icon/` | 앱 아이콘 원본과 생성 스크립트 `make-icons.mjs` |
| `build-apk.ps1` | 테스트용 APK 빌드 |
| `build-release.ps1` | 정식 서명 APK + AAB(플레이스토어용) 빌드 |

## 빌드하기 (Windows)

준비물
- Android Studio (안드로이드 SDK)
- Java 21 (`C:\dev\tools\jdk21-extract\`). Android Studio에 들어 있는 Java는 Gradle 8.14와 안 맞아요
- 원본 폴더 `C:\dev\rocket-plum-cloud-sail` (빌드할 때 원본의 `src`, `public`을 복사해 와요)

```powershell
npm install
# 테스트용 APK → 누구나프린트-debug.apk
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
# 정식 서명 APK + AAB → 누구나프린트-release.apk / .aab
powershell -ExecutionPolicy Bypass -File .\build-release.ps1
```

정식 빌드에는 서명 키(`C:\dev\nuguna-print-keystore\`)가 있어야 해요. 서명 키는 저장소에 없어서 관리자만 정식 빌드를 할 수 있어요.

## 버전 올리기

`android/app/build.gradle`의 `versionName`과 `versionCode`를 같이 올려요. (예: 5.1 → 51)
플레이스토어는 `versionCode`가 더 커야 업데이트로 받아 줘요.

## 같이 개발할 때

- 화면을 고칠 때는 원본 저장소에서 고쳐요. 이 저장소의 `src`는 빌드할 때 원본으로 덮어써져요.
- 이 저장소에서는 `android/`, 빌드 스크립트, 앱 전용 파일만 고쳐요.
