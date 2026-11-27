# 누구나프린트 안드로이드 APK 만들기 (사본 전용)
# 1) 원본의 최신 화면 코드(src, public)를 이 사본으로 가져온다 — 원본은 읽기만 한다
# 2) 화면 빌드 → Capacitor 동기화 → Gradle로 테스트용(debug) APK
# 사용: PowerShell에서  powershell -ExecutionPolicy Bypass -File C:\dev\nuguna-print-android\build-apk.ps1

$ErrorActionPreference = "Stop"
$src = "C:\dev\rocket-plum-cloud-sail"
$dst = "C:\dev\nuguna-print-android"
$jdk = Get-ChildItem "C:\dev\tools\jdk21-extract" -Directory | Select-Object -First 1

# 앱 전용 파일(android-main.tsx)은 남기고, 나머지 화면 코드만 원본과 맞춘다
robocopy "$src\src" "$dst\src" /E /XF android-main.tsx /NFL /NDL /NJH /NJS /NP | Out-Null
# 웹 전용 파일(__grok 설치 안내, 서비스 워커, 공유 미리보기 그림)은 앱에 싣지 않는다
robocopy "$src\public" "$dst\public" /E /XD __grok /XF sw.js og.jpg /NFL /NDL /NJH /NJS /NP | Out-Null
foreach ($web in "__grok", "sw.js", "og.jpg") { if (Test-Path "$dst\public\$web") { Remove-Item "$dst\public\$web" -Recurse -Force } }

Set-Location $dst
npx vite build --config vite.android.config.ts
npx cap sync android

$env:JAVA_HOME = $jdk.FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Push-Location "$dst\android"
[Environment]::CurrentDirectory = (Get-Location).Path
& .\gradlew.bat assembleDebug --no-daemon
Pop-Location

Copy-Item "$dst\android\app\build\outputs\apk\debug\app-debug.apk" "$dst\누구나프린트-debug.apk" -Force
Write-Host "완료: $dst\누구나프린트-debug.apk"
