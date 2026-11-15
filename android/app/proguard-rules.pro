# The web page calls these methods by name through window.MTSNative.
-keepattributes JavascriptInterface
-keepclassmembers class com.meridiantheschool.mtsstaff.WebBridge {
    @android.webkit.JavascriptInterface <methods>;
}
