package com.pixroyale.reminders;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;

    // Flashlight torch control
    private CameraManager cameraManager;
    private String cameraIdWithFlash;
    private boolean isTorchOn = false;
    private Handler torchHandler = new Handler(Looper.getMainLooper());

    private static final String CHANNEL_ID_REMINDER = "pixel_royale_reminder_v3";
    private static final String CHANNEL_ID_ALARM = "pixel_royale_alarm_v3";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        // Auto request notification runtime permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        android.Manifest.permission.POST_NOTIFICATIONS
                }, 101);
            }
        }

        initNotificationChannels();
        initCameraTorch();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDefaultTextEncodingName("utf-8");

        webView.addJavascriptInterface(new NativeBridge(this), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());

        // Load seamless local bundle if exists, else load android_asset
        loadAppBundle();

        // Schedule background alarms immediately on launch
        AlarmScheduler.rescheduleAll(this);
        requestBatteryOptimizationExemption();
    }

    public void loadAppBundle() {
        File hotBundle = new File(getFilesDir(), "web_bundle/index.html");
        if (hotBundle.exists() && hotBundle.length() > 1000) {
            webView.loadUrl(hotBundle.toURI().toString());
        } else {
            webView.loadUrl("file:///android_asset/index.html");
        }
    }

    private void initNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                // Reminder channel with vibration
                NotificationChannel chReminder = new NotificationChannel(
                        CHANNEL_ID_REMINDER,
                        "Пиксель Рояль - Напоминания",
                        NotificationManager.IMPORTANCE_HIGH
                );
                chReminder.setDescription("Вибро-напоминания и уведомления");
                chReminder.enableVibration(true);
                chReminder.setVibrationPattern(new long[]{0, 500, 200, 500, 200, 600});
                chReminder.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
                chReminder.setBypassDnd(true);
                nm.createNotificationChannel(chReminder);

                // Priority Alarm channel
                NotificationChannel chAlarm = new NotificationChannel(
                        CHANNEL_ID_ALARM,
                        "Пиксель Рояль - Важные события",
                        NotificationManager.IMPORTANCE_HIGH
                );
                chAlarm.setDescription("Оповещения перед капчей и аирдропами");
                chAlarm.enableVibration(true);
                chAlarm.setVibrationPattern(new long[]{0, 800, 200, 800, 200, 800});
                chAlarm.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
                chAlarm.setBypassDnd(true);
                nm.createNotificationChannel(chAlarm);
            }
        }
    }

    private void initCameraTorch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                if (cameraManager != null) {
                    for (String id : cameraManager.getCameraIdList()) {
                        CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                        Boolean hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                        Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                        if (hasFlash != null && hasFlash && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                            cameraIdWithFlash = id;
                            break;
                        }
                    }
                    if (cameraIdWithFlash == null && cameraManager.getCameraIdList().length > 0) {
                        cameraIdWithFlash = cameraManager.getCameraIdList()[0];
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    public void setTorch(boolean enable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && cameraManager != null && cameraIdWithFlash != null) {
            try {
                cameraManager.setTorchMode(cameraIdWithFlash, enable);
                isTorchOn = enable;
            } catch (Exception ignored) {}
        }
    }

    public void stopTorchStrobe() {
        torchHandler.removeCallbacksAndMessages(null);
        setTorch(false);
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    @SuppressLint("BatteryLife")
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                }
            } catch (Exception ignored) {}
        }
    }

    public Vibrator getVibrator() {
        Vibrator vibrator = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) vibrator = vm.getDefaultVibrator();
        }
        if (vibrator == null) {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
        return vibrator;
    }

    public void startAudioFilePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            startActivityForResult(intent, 202);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть выбор файла: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 202 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri audioUri = data.getData();
            try {
                String displayName = "custom_sound";
                try (android.database.Cursor cursor = getContentResolver().query(audioUri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (nameIndex >= 0) displayName = cursor.getString(nameIndex);
                    }
                }

                File destFile = new File(getFilesDir(), "custom_alarm_sound");
                try (InputStream in = getContentResolver().openInputStream(audioUri);
                     FileOutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                }

                SharedPreferences prefs = getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE);
                prefs.edit().putString("customSoundName", displayName).apply();

                if (webView != null) {
                    webView.evaluateJavascript("if (window.onCustomSoundPicked) { window.onCustomSoundPicked('" + displayName.replace("'", "\'") + "'); }", null);
                }
                Toast.makeText(this, "Звук выбран: " + displayName, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Ошибка сохранения звука: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void promptInstallApk(File apkFile) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!getPackageManager().canRequestPackageInstalls()) {
                    Intent grantIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()));
                    grantIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(grantIntent);
                    Toast.makeText(this, "Разрешите установку обновлений и вернитесь", Toast.LENGTH_LONG).show();
                }
            }

            Uri apkUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    apkFile
            );

            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(installIntent);
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка установки: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public static class NativeBridge {
        private final MainActivity activity;

        public NativeBridge(MainActivity activity) {
            this.activity = activity;
        }

        @JavascriptInterface
        public void triggerConfiguredVibration(int strength) {
            activity.runOnUiThread(() -> {
                ReminderBroadcastReceiver.performConfiguredVibration(activity, strength, false);
            });
        }

        @JavascriptInterface
        public void triggerNotificationSound(int volumePercent, String soundType) {
            activity.runOnUiThread(() -> {
                ReminderBroadcastReceiver.playNotificationTone(activity, volumePercent, soundType);
            });
        }

        @JavascriptInterface
        public void stopAlarm() {
            activity.runOnUiThread(() -> {
                activity.stopTorchStrobe();
                Vibrator v = activity.getVibrator();
                if (v != null) v.cancel();
            });
        }

        @JavascriptInterface
        public void saveVibAndSoundSettings(int strength, int volume, String soundType, boolean autoNightUpdate) {
            SharedPreferences prefs = activity.getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE);
            prefs.edit()
                    .putInt("vibStrength", strength)
                    .putInt("soundVolume", volume)
                    .putString("soundType", soundType)
                    .putBoolean("autoNightUpdate", autoNightUpdate)
                    .apply();
        }

        @JavascriptInterface
        public int getVibStrength() {
            return activity.getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE).getInt("vibStrength", 4);
        }

        @JavascriptInterface
        public int getSoundVolume() {
            return activity.getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE).getInt("soundVolume", 50);
        }

        @JavascriptInterface
        public String getSoundType() {
            return activity.getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE).getString("soundType", "chime");
        }

        @JavascriptInterface
        public boolean getAutoNightUpdate() {
            return activity.getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE).getBoolean("autoNightUpdate", true);
        }

        @JavascriptInterface
        public void applySeamlessHotUpdate(String htmlContent, String newVersion) {
            activity.runOnUiThread(() -> {
                try {
                    File dir = new File(activity.getFilesDir(), "web_bundle");
                    if (!dir.exists()) dir.mkdirs();
                    File bundleFile = new File(dir, "index.html");
                    try (FileOutputStream fos = new FileOutputStream(bundleFile)) {
                        fos.write(htmlContent.getBytes("UTF-8"));
                        fos.flush();
                    }

                    activity.getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE)
                            .edit().putString("hotBundleVersion", newVersion).apply();

                    // Hot swap webview
                    activity.webView.loadUrl(bundleFile.toURI().toString());
                    Toast.makeText(activity, "Обновлено до версии " + newVersion, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(activity, "Ошибка обновления: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void downloadAndApplySeamlessUpdate(String bundleUrl, String newVersion) {
            new Thread(() -> {
                try {
                    activity.runOnUiThread(() -> {
                        if (activity.webView != null) {
                            activity.webView.evaluateJavascript("if (window.onDownloadProgress) { window.onDownloadProgress(20, 'Бесшовное скачивание...'); }", null);
                        }
                    });

                    java.net.URL url = new java.net.URL(bundleUrl);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 PixRoyale");

                    if (conn.getResponseCode() == 200) {
                        InputStream in = conn.getInputStream();
                        java.util.Scanner s = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A");
                        String content = s.hasNext() ? s.next() : "";
                        s.close();
                        in.close();

                        if (content.length() > 500) {
                            activity.runOnUiThread(() -> {
                                if (activity.webView != null) {
                                    activity.webView.evaluateJavascript("if (window.onDownloadProgress) { window.onDownloadProgress(100, 'Мгновенное применение...'); }", null);
                                }
                                applySeamlessHotUpdate(content, newVersion);
                            });
                            return;
                        }
                    }
                    throw new Exception("HTTP " + conn.getResponseCode());
                } catch (Exception e) {
                    activity.runOnUiThread(() -> {
                        if (activity.webView != null) {
                            activity.webView.evaluateJavascript(
                                    String.format("if (window.onDownloadProgress) { window.onDownloadProgress(-1, 'Сбой: %s'); }", e.getMessage().replace("'", "\\'")),
                                    null);
                        }
                    });
                }
            }).start();
        }

        @JavascriptInterface
        public void syncConfigAndReschedule(int captchaLead, int airdropLead, boolean extraOn, boolean normalOn, int nightStart, int nightEnd, boolean nightOn, boolean nightSilent, String sirenType, boolean customOn, boolean customFlash, boolean customVib, boolean customSound, boolean customNotif) {
            SharedPreferences prefs = activity.getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE);
            prefs.edit()
                    .putInt("captchaLeadTime", captchaLead)
                    .putInt("airdropLeadTime", airdropLead)
                    .putBoolean("extraModeEnabled", extraOn)
                    .putBoolean("normalModeEnabled", normalOn)
                    .putInt("nightStartHour", nightStart)
                    .putInt("nightEndHour", nightEnd)
                    .putBoolean("nightModeEnabled", nightOn)
                    .putBoolean("nightSilentMode", nightSilent)
                    .putString("sirenTypeSelect", sirenType)
                    .putBoolean("customModeEnabled", customOn)
                    .putBoolean("customFlash", customFlash)
                    .putBoolean("customVib", customVib)
                    .putBoolean("customSound", customSound)
                    .putBoolean("customNotif", customNotif)
                    .apply();

            AlarmScheduler.rescheduleAll(activity);
        }

        @JavascriptInterface
        public void checkForUpdates() {
            new Thread(() -> {
                try {
                    java.net.URL url = new java.net.URL("https://api.github.com/repos/ivanmaspa/pixroyale-reminders/releases/latest");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "PixRoyaleApp");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);

                    if (conn.getResponseCode() == 200) {
                        InputStream in = conn.getInputStream();
                        java.util.Scanner scanner = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A");
                        String response = scanner.hasNext() ? scanner.next() : "";
                        scanner.close();
                        in.close();

                        org.json.JSONObject release = new org.json.JSONObject(response);
                        String latestTag = release.optString("tag_name", "");
                        String releaseName = release.optString("name", latestTag);
                        String htmlUrl = release.optString("html_url", "");
                        String apkDownloadUrl = "";
                        String bundleDownloadUrl = "";

                        org.json.JSONArray assets = release.optJSONArray("assets");
                        if (assets != null) {
                            for (int i = 0; i < assets.length(); i++) {
                                org.json.JSONObject asset = assets.getJSONObject(i);
                                String name = asset.optString("name", "");
                                if (name.endsWith(".apk")) {
                                    apkDownloadUrl = asset.optString("browser_download_url", "");
                                } else if (name.equals("index.html") || name.endsWith(".html")) {
                                    bundleDownloadUrl = asset.optString("browser_download_url", "");
                                }
                            }
                        }

                        if (apkDownloadUrl.isEmpty() && !latestTag.isEmpty()) {
                            apkDownloadUrl = "https://github.com/ivanmaspa/pixroyale-reminders/releases/download/" + latestTag + "/PixRoyale_" + latestTag + ".apk";
                        }
                        if (bundleDownloadUrl.isEmpty() && !latestTag.isEmpty()) {
                            bundleDownloadUrl = "https://raw.githubusercontent.com/ivanmaspa/pixroyale-reminders/main/app/src/main/assets/index.html";
                        }

                        final String fTag = latestTag;
                        final String fName = releaseName;
                        final String fApkUrl = apkDownloadUrl;
                        final String fBundleUrl = bundleDownloadUrl;

                        activity.runOnUiThread(() -> {
                            if (activity.webView != null) {
                                String js = String.format("if (window.onUpdateCheckResult) { window.onUpdateCheckResult('%s', '%s', '%s', '%s'); }",
                                        fTag.replace("'", "\\'"),
                                        fName.replace("'", "\\'"),
                                        fApkUrl.replace("'", "\\'"),
                                        fBundleUrl.replace("'", "\\'"));
                                activity.webView.evaluateJavascript(js, null);
                            }
                        });
                    } else {
                        activity.runOnUiThread(() -> {
                            if (activity.webView != null) {
                                activity.webView.evaluateJavascript("if (window.onUpdateCheckResult) { window.onUpdateCheckResult(null, 'Сбой сети', null, null); }", null);
                            }
                        });
                    }
                } catch (Exception e) {
                    activity.runOnUiThread(() -> {
                        if (activity.webView != null) {
                            activity.webView.evaluateJavascript("if (window.onUpdateCheckResult) { window.onUpdateCheckResult(null, 'Ошибка: " + e.getMessage() + "', null, null); }", null);
                        }
                    });
                }
            }).start();
        }

        @JavascriptInterface
        public void downloadAndInstallApk(String downloadUrl) {
            new Thread(() -> {
                try {
                    activity.runOnUiThread(() -> {
                        if (activity.webView != null) {
                            activity.webView.evaluateJavascript("if (window.onDownloadProgress) { window.onDownloadProgress(1, 'Подключение к серверу...'); }", null);
                        }
                    });

                    java.net.URL url = new java.net.URL(downloadUrl);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 PixRoyale");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);

                    int fileLength = conn.getContentLength();
                    InputStream input = new java.io.BufferedInputStream(conn.getInputStream());

                    File cacheDir = activity.getExternalCacheDir();
                    if (cacheDir == null) cacheDir = activity.getCacheDir();
                    File apkFile = new File(cacheDir, "update.apk");
                    if (apkFile.exists()) apkFile.delete();

                    OutputStream output = new FileOutputStream(apkFile);
                    byte[] data = new byte[8192];
                    long total = 0;
                    int count;
                    long lastUpdate = 0;

                    while ((count = input.read(data)) != -1) {
                        total += count;
                        output.write(data, 0, count);

                        long now = System.currentTimeMillis();
                        if (fileLength > 0 && (now - lastUpdate > 250 || total == fileLength)) {
                            lastUpdate = now;
                            int progress = (int) (total * 100 / fileLength);
                            String status = String.format(java.util.Locale.US, "Загрузка: %d%% (%.1f / %.1f MB)",
                                    progress, total / (1024.0 * 1024.0), fileLength / (1024.0 * 1024.0));
                            activity.runOnUiThread(() -> {
                                if (activity.webView != null) {
                                    activity.webView.evaluateJavascript(
                                            String.format(java.util.Locale.US, "if (window.onDownloadProgress) { window.onDownloadProgress(%d, '%s'); }", progress, status),
                                            null);
                                }
                            });
                        }
                    }

                    output.flush();
                    output.close();
                    input.close();

                    activity.runOnUiThread(() -> {
                        if (activity.webView != null) {
                            activity.webView.evaluateJavascript("if (window.onDownloadProgress) { window.onDownloadProgress(100, 'Запуск установщика...'); }", null);
                        }
                        activity.promptInstallApk(apkFile);
                    });

                } catch (Exception e) {
                    activity.runOnUiThread(() -> {
                        if (activity.webView != null) {
                            activity.webView.evaluateJavascript(
                                    String.format("if (window.onDownloadProgress) { window.onDownloadProgress(-1, 'Ошибка: %s'); }", (e.getMessage() != null ? e.getMessage().replace("'", "\\'") : "Сбой сети")),
                                    null);
                        }
                    });
                }
            }).start();
        }

        @JavascriptInterface
        public void pickCustomAudioFile() {
            activity.runOnUiThread(activity::startAudioFilePicker);
        }

        @JavascriptInterface
        public String getCustomSoundName() {
            return activity.getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE).getString("customSoundName", "");
        }

        @JavascriptInterface
        public void setGameLaunchTarget(String target) {
            activity.getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE)
                    .edit()
                    .putString("gameLaunchTarget", target != null ? target.trim() : "")
                    .apply();
        }

        @JavascriptInterface
        public String getGameLaunchTarget() {
            return activity.getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE)
                    .getString("gameLaunchTarget", "");
        }

        @JavascriptInterface
        public void testLaunchGame() {
            activity.runOnUiThread(() -> {
                try {
                    Intent intent = ReminderBroadcastReceiver.createGameLaunchIntent(activity);
                    activity.startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(activity, "Ошибка запуска: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void openTelegramBot(String usernameOrUrl) {
            activity.runOnUiThread(() -> {
                try {
                    String domain = usernameOrUrl;
                    if (domain.startsWith("@")) {
                        domain = domain.substring(1);
                    } else if (domain.contains("t.me/")) {
                        domain = domain.substring(domain.lastIndexOf("t.me/") + 5);
                    }
                    if (domain.contains("?")) {
                        domain = domain.substring(0, domain.indexOf("?"));
                    }
                    domain = domain.replace("/", "").trim();
                    if (domain.isEmpty()) {
                        domain = "masiksssgegai_bot";
                    }

                    Uri tgUri = Uri.parse("tg://resolve?domain=" + domain);
                    Intent intent = new Intent(Intent.ACTION_VIEW, tgUri);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                    String[] tgPackages = new String[]{
                            "org.telegram.messenger",
                            "org.telegram.plus",
                            "org.thunderdog.challegram",
                            "org.telegram.messenger.web",
                            "org.telegram.messenger.beta"
                    };

                    boolean launched = false;
                    for (String pkg : tgPackages) {
                        try {
                            activity.getPackageManager().getPackageInfo(pkg, 0);
                            intent.setPackage(pkg);
                            activity.startActivity(intent);
                            launched = true;
                            break;
                        } catch (Exception ignored) {}
                    }

                    if (!launched) {
                        try {
                            Intent genericTg = new Intent(Intent.ACTION_VIEW, tgUri);
                            genericTg.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            activity.startActivity(genericTg);
                            launched = true;
                        } catch (Exception ignored) {}
                    }

                    if (!launched) {
                        Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/" + domain));
                        webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(webIntent);
                    }
                } catch (Exception e) {
                    try {
                        Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/masiksssgegai_bot"));
                        webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(webIntent);
                    } catch (Exception ignored) {}
                }
            });
        }
    }
}
