# 누구나프린트 release 축소(R8) 규칙
# Capacitor는 플러그인을 이름(@CapacitorPlugin)과 메서드 이름(@PluginMethod)으로 리플렉션해 부른다 → 이름이 바뀌면 화면에서 못 부른다.

# Capacitor 브리지 · 플러그인 기반 (라이브러리 규칙이 있어도 한 번 더 확실히)
-keep class com.getcapacitor.** { *; }
-keep @com.getcapacitor.annotation.CapacitorPlugin public class * {
    @com.getcapacitor.PluginMethod public <methods>;
    @com.getcapacitor.annotation.PermissionCallback <methods>;
    @com.getcapacitor.annotation.ActivityCallback <methods>;
    public <init>(...);
}
-keepclassmembers class * extends com.getcapacitor.Plugin {
    @com.getcapacitor.PluginMethod public <methods>;
}
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# 앱 자체 코드(내장 도우미 · USB · 저장)는 작고 리플렉션 경로가 있으니 통째로 둔다
-keep class com.nuguna.print.** { *; }

# WebView JS 인터페이스(Capacitor 브리지)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Cordova 호환 층(플러그인 없음이지만 Capacitor가 참조)
-keep class org.apache.cordova.** { *; }
-dontwarn org.apache.cordova.**

# 줄 번호는 남겨 충돌 보고를 읽을 수 있게, 원본 파일 이름은 감춘다
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
