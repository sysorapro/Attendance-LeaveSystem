package com.sysora.attendance;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends FragmentActivity {

    private static final int FILE_CHOOSER_REQ = 1001;
    private static final String KEY_ALIAS = "sysora_bio_key";
    private static final String PREFS = "sysora_bio";
    private static final int AUTH = BiometricManager.Authenticators.BIOMETRIC_STRONG;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private String appUrl;

    // ---- splash / back / language state ----
    private static final long SPLASH_MIN_MS = 3200;
    private static final long SPLASH_MAX_MS = 12000;
    private FrameLayout root;
    private WebView splash;
    private long splashStart;
    private boolean pageLoaded = false;
    private volatile boolean splashDoneFlag = false;
    private boolean appReadyFlag = false;
    private long appReadyAt = 0;
    private volatile boolean backPending = false;
    private long lastBackAt = 0;
    private volatile String lang = "ar";
    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean ar() { return !"en".equals(lang); }

    private static final String FIX_CSS_JS =
        "(function(){try{var st=document.createElement('style');"
      + "st.textContent='html,body{margin:0!important;padding:0!important;height:100%!important;"
      + "background:#08152b!important;overflow:hidden!important}"
      + "iframe{position:fixed!important;top:0!important;left:0!important;width:100%!important;"
      + "height:100%!important;border:0!important}';"
      + "document.documentElement.appendChild(st);"
      // إخفاء أي عنصر غير الإطار (رسالة "أنشأ هذا التطبيق أحد مستخدمي برمجة تطبيقات Google")
      + "function clean(){var f=document.querySelector('iframe');if(!f)return;var n=f;"
      + "while(n&&n!==document.documentElement){var p=n.parentNode;if(!p)break;var ch=p.children;"
      + "for(var i=0;i<ch.length;i++){var c=ch[i];var t=c.tagName;"
      + "if(c!==n&&t!=='SCRIPT'&&t!=='STYLE'&&t!=='LINK'&&t!=='IFRAME'&&t!=='HEAD'){c.style.setProperty('display','none','important');}}"
      + "n=p;}}"
      + "clean();new MutationObserver(clean).observe(document.documentElement,{childList:true,subtree:true});"
      + "}catch(e){}})();";

    // نتائج عمليات البصمة (الصفحة بتسأل عنها بالـ polling)
    private final ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appUrl = getString(R.string.web_app_url);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF08152B);
        webView = new WebView(this);
        webView.setBackgroundColor(0xFF08152B);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        if (savedInstanceState == null) {
            splashStart = SystemClock.uptimeMillis();
            splash = new WebView(this);
            splash.setBackgroundColor(0xFF08152B);
            splash.getSettings().setJavaScriptEnabled(true);
            splash.setVerticalScrollBarEnabled(false);
            splash.setHorizontalScrollBarEnabled(false);
            splash.setOnTouchListener((v, e) -> true);     // امنع اللمس أثناء اللوجو
            root.addView(splash, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            splash.loadUrl("file:///android_asset/splash.html");
            ui.postDelayed(this::maybeHideSplash, SPLASH_MAX_MS);
        } else {
            splashDoneFlag = true;
        }

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String host = u.getHost() == null ? "" : u.getHost();
                // مسموح فقط بنطاقات Apps Script (الجسر بيتعرّض للصفحات دي بس)
                boolean internal = host.equals("script.google.com")
                        || host.equals("script.googleusercontent.com")
                        || host.endsWith("-script.googleusercontent.com")
                        || host.equals("accounts.google.com");
                if (internal) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // اجعل إطار Apps Script يملأ الشاشة بالكامل (يزيل الشريط الأبيض)
                view.evaluateJavascript(FIX_CSS_JS, null);
                pageLoaded = true;
                setSplashProgress(75);
                ui.removeCallbacks(creep);
                ui.postDelayed(creep, 250);
                // لو الصفحة ماقالتش إنها جاهزة (نسخة قديمة) نكمل بعد 6 ثواني
                ui.postDelayed(MainActivity.this::markAppReady, 6000);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) showOffline();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                if (!pageLoaded) setSplashProgress((int) (p * 0.7));
            }

            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQ);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else loadApp();
    }

    private void loadApp() {
        if (isOnline()) webView.loadUrl(appUrl);
        else showOffline();
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        return cm != null && cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isConnected();
    }

    private void showOffline() {
        markAppReady();
        boolean a = ar();
        String html = "<html dir='" + (a ? "rtl" : "ltr") + "'><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='background:#08152b;color:#fff;font-family:sans-serif;text-align:center;padding-top:30vh'>"
                + "<h2>" + (a ? "لا يوجد اتصال بالإنترنت" : "No internet connection") + "</h2><p>"
                + (a ? "تأكد من الاتصال ثم حاول مرة أخرى" : "Check your connection and try again") + "</p>"
                + "<button onclick='AndroidBridge.reload()' style='font-size:18px;padding:12px 28px;border:0;border-radius:10px;background:#2f80ed;color:#fff'>"
                + (a ? "إعادة المحاولة" : "Retry") + "</button>"
                + "</body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    // ───────────────────── Splash ─────────────────────

    private void setSplashProgress(final int p) {
        final WebView sp = splash;
        if (sp != null) sp.evaluateJavascript("window.setProgress&&setProgress(" + p + ")", null);
    }

    // نزوّد النسبة تدريجياً (75 → 96) لحد ما الصفحة تقول إنها جاهزة
    private final Runnable creep = new Runnable() {
        int cur = 75;
        @Override public void run() {
            if (splash == null || appReadyFlag) return;
            if (cur < 96) { cur++; setSplashProgress(cur); }
            ui.postDelayed(this, 250);
        }
    };

    private void markAppReady() {
        if (appReadyFlag) return;
        appReadyFlag = true;
        appReadyAt = SystemClock.uptimeMillis();
        setSplashProgress(100);
        ui.postDelayed(this::maybeHideSplash, 850);
    }

    private void maybeHideSplash() {
        if (splash == null) return;
        long now = SystemClock.uptimeMillis();
        long elapsed = now - splashStart;
        boolean ready = appReadyFlag && elapsed >= SPLASH_MIN_MS && now - appReadyAt >= 800;
        boolean timeout = elapsed >= SPLASH_MAX_MS;
        if (ready || timeout) {
            final WebView sp = splash;
            splash = null;
            sp.animate().alpha(0f).setDuration(450).withEndAction(() -> {
                root.removeView(sp);
                sp.destroy();
                splashDoneFlag = true;
            }).start();
        } else if (appReadyFlag) {
            long wait = Math.max(SPLASH_MIN_MS - elapsed, 800 - (now - appReadyAt));
            ui.postDelayed(this::maybeHideSplash, Math.max(wait, 50));
        }
    }

    // ───────────────────── Back button ─────────────────────

    private void handleExit() {
        long now = SystemClock.uptimeMillis();
        if (now - lastBackAt < 2000) {
            finish();
        } else {
            lastBackAt = now;
            Toast.makeText(this, ar() ? "اضغط رجوع مرة أخرى للخروج" : "Press back again to exit",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQ && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        webView.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (!splashDoneFlag) return;          // تجاهل الرجوع أثناء شاشة اللوجو
        // نطلب من الصفحة تنفيذ الرجوع (إغلاق نافذة / الصفحة السابقة)، ولو ماردتش نطلع بالضغط المزدوج
        backPending = true;
        ui.postDelayed(() -> {
            if (backPending) { backPending = false; handleExit(); }
        }, 700);
    }

    @Override protected void onPause()  { super.onPause();  webView.onPause();  CookieManager.getInstance().flush(); }
    @Override protected void onResume() { super.onResume(); webView.onResume(); }

    // ───────────────────── Biometric helpers ─────────────────────

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return (SecretKey) ks.getKey(KEY_ALIAS, null);

        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true);
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        } else {
            b.setUserAuthenticationValidityDurationSeconds(-1);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(b.build());
        return kg.generateKey();
    }

    private void deleteKey() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) { }
    }

    private BiometricPrompt.PromptInfo promptInfo(String title, String sub) {
        return new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(sub)
                .setAllowedAuthenticators(AUTH)
                .setNegativeButtonText(ar() ? "إلغاء" : "Cancel")
                .build();
    }

    private interface AuthDone { void onSuccess(BiometricPrompt.AuthenticationResult r) throws Exception; }

    private void runPrompt(final String reqId, String title, String sub,
                           BiometricPrompt.CryptoObject crypto, final AuthDone done) {
        Executor ex = ContextCompat.getMainExecutor(this);
        BiometricPrompt bp = new BiometricPrompt(this, ex, new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                try { done.onSuccess(r); } catch (Exception e) { results.put(reqId, "fail:" + e.getClass().getSimpleName()); }
            }
            @Override public void onAuthenticationError(int code, CharSequence msg) {
                boolean cancelled = code == BiometricPrompt.ERROR_USER_CANCELED
                        || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        || code == BiometricPrompt.ERROR_CANCELED;
                results.put(reqId, cancelled ? "cancel" : "fail:" + msg);
            }
            // onAuthenticationFailed = بصمة غلط، النظام بيسيب المستخدم يحاول تاني
        });
        if (crypto != null) bp.authenticate(promptInfo(title, sub), crypto);
        else bp.authenticate(promptInfo(title, sub));
    }

    /** Methods callable from the page's JavaScript (window.AndroidBridge). */
    private class Bridge {

        @JavascriptInterface
        public void reload() { runOnUiThread(MainActivity.this::loadApp); }

        @JavascriptInterface
        public void setLang(String l) { lang = "en".equals(l) ? "en" : "ar"; }

        @JavascriptInterface
        public boolean splashDone() { return splashDoneFlag; }

        /** الصفحة خلصت تحميلها واتعرضت (شاشة الدخول أو الصفحة الرئيسية) */
        @JavascriptInterface
        public void appReady() { runOnUiThread(MainActivity.this::markAppReady); }

        /** الصفحة بتسأل: هل في ضغطة رجوع مستنية؟ */
        @JavascriptInterface
        public boolean consumeBack() {
            if (backPending) { backPending = false; return true; }
            return false;
        }

        /** الصفحة مالقتش حاجة ترجع لها → نعمل الخروج بالضغط المزدوج */
        @JavascriptInterface
        public void backUnhandled() { runOnUiThread(MainActivity.this::handleExit); }

        // ── حالة البصمة: ok / none_enrolled / unsupported ──
        @JavascriptInterface
        public String biometricStatus() {
            int r = BiometricManager.from(MainActivity.this).canAuthenticate(AUTH);
            if (r == BiometricManager.BIOMETRIC_SUCCESS) return "ok";
            if (r == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) return "none_enrolled";
            return "unsupported";
        }

        // ── تأكيد هوية بالبصمة فقط (لتسجيل الحضور/الانصراف) ──
        @JavascriptInterface
        public void biometricAuth(final String reqId, final String title, final String subtitle) {
            results.remove(reqId);
            runOnUiThread(() -> runPrompt(reqId, title, subtitle, null, r -> results.put(reqId, "ok")));
        }

        // ── نتيجة العملية: "" = لسه، ok / ok:{json} / cancel / fail:... ──
        @JavascriptInterface
        public String biometricResult(String reqId) {
            String r = results.get(reqId);
            if (r != null) results.remove(reqId);
            return r == null ? "" : r;
        }

        // ── الدخول بالبصمة: حفظ بيانات الدخول مشفّرة بمفتاح محمي بالبصمة ──
        @JavascriptInterface
        public void bioSaveCredentials(final String reqId, final String user, final String pass) {
            results.remove(reqId);
            runOnUiThread(() -> {
                try {
                    deleteKey();
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
                    runPrompt(reqId, ar() ? "تفعيل الدخول بالبصمة" : "Enable fingerprint login",
                            ar() ? "ضع إصبعك لتأكيد التفعيل" : "Touch the sensor to confirm",
                            new BiometricPrompt.CryptoObject(c), r -> {
                                Cipher ci = r.getCryptoObject().getCipher();
                                JSONObject o = new JSONObject();
                                o.put("u", user); o.put("p", pass);
                                byte[] enc = ci.doFinal(o.toString().getBytes(StandardCharsets.UTF_8));
                                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                        .putString("enc", Base64.encodeToString(enc, Base64.NO_WRAP))
                                        .putString("iv", Base64.encodeToString(ci.getIV(), Base64.NO_WRAP))
                                        .putString("user", user)
                                        .apply();
                                results.put(reqId, "ok");
                            });
                } catch (Exception e) {
                    results.put(reqId, "fail:" + e.getClass().getSimpleName());
                }
            });
        }

        @JavascriptInterface
        public boolean bioHasCredentials() {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getString("enc", null) != null;
        }

        @JavascriptInterface
        public String bioSavedUsername() {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getString("user", "");
        }

        // ── فك التشفير بالبصمة وإرجاع بيانات الدخول ──
        @JavascriptInterface
        public void bioUnlock(final String reqId) {
            results.remove(reqId);
            runOnUiThread(() -> {
                SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                String enc = sp.getString("enc", null), iv = sp.getString("iv", null);
                if (enc == null || iv == null) { results.put(reqId, "fail:nocreds"); return; }
                try {
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                            new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
                    runPrompt(reqId, ar() ? "الدخول بالبصمة" : "Fingerprint login", "SYSORA Attendance",
                            new BiometricPrompt.CryptoObject(c), r -> {
                                byte[] plain = r.getCryptoObject().getCipher()
                                        .doFinal(Base64.decode(enc, Base64.NO_WRAP));
                                results.put(reqId, "ok:" + new String(plain, StandardCharsets.UTF_8));
                            });
                } catch (KeyPermanentlyInvalidatedException e) {
                    // اتغيرت البصمات المسجلة على الموبايل → لازم التفعيل من جديد
                    clearCreds();
                    results.put(reqId, "fail:invalidated");
                } catch (Exception e) {
                    results.put(reqId, "fail:" + e.getClass().getSimpleName());
                }
            });
        }

        @JavascriptInterface
        public void bioClearCredentials() { clearCreds(); }

        private void clearCreds() {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply();
            deleteKey();
        }

        // ── حفظ ملفات التصدير (CSV) في Downloads ──
        @JavascriptInterface
        public void saveFile(String filename, String mime, String content) {
            try {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                String safe = filename.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new Exception("insert failed");
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(bytes); }
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    try (FileOutputStream os = new FileOutputStream(new File(dir, safe))) { os.write(bytes); }
                }
                toast("تم حفظ الملف في مجلد Downloads: " + safe);
            } catch (Exception e) {
                toast("تعذر حفظ الملف");
            }
        }

        private void toast(String m) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show());
        }
    }
}
