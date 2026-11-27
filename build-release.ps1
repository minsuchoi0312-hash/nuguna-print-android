# 누구나프린트 정식(release) 빌드: 서명된 APK(직접 설치용) + AAB(Play 스토어 업로드용)
# 서명 키: C:\dev\nuguna-print-keystore (저장소 밖, 꼭 백업). 버전은 android\app\build.gradle 의 versionCode / versionName.
# 사용: powershell -ExecutionPolicy Bypass -File C:\dev\nuguna-print-android\build-release.ps1

$ErrorActionPreference = "Stop"
$src = "C:\dev\rocket-plum-cloud-sail"
$dst = "C:\dev\nuguna-print-android"
$jdk = Get-ChildItem "C:\dev\tools\jdk21-extract" -Directory | Select-Object -First 1

if (-not (Test-Path "C:\dev\nuguna-print-keystore\keystore.properties")) {
  throw "서명 키가 없어요: C:\dev\nuguna-print-keystore\keystore.properties"
}

# 원본의 화면 코드를 가져온다 (원본은 읽기만)
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
& .\gradlew.bat assembleRelease bundleRelease --no-daemon
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "Gradle 빌드 실패" }
Pop-Location

Copy-Item "$dst\android\app\build\outputs\apk\release\app-release.apk" "$dst\누구나프린트-release.apk" -Force
Copy-Item "$dst\android\app\build\outputs\bundle\release\app-release.aab" "$dst\누구나프린트-release.aab" -Force
Write-Host "완료: $dst\누구나프린트-release.apk (직접 설치) / 누구나프린트-release.aab (Play 스토어)"
