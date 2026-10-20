package com.pixroyale.reminders;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
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
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;
import android.provider.Settings;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PICK_AUDIO = 202;
    private static final String CUSTOM_SOUND_FILENAME = "custom_alarm_sound";

    private WebView webView;
    private Ringtone currentRingtone;
    private MediaPlayer customMediaPlayer;
    private CameraManager cameraManager;
    private String cameraId;
    private Handler strobeHandler = new Handler(Looper.getMainLooper());
    private boolean isStrobeRunning = false;
    private boolean flashState = false;

    // Siren tone generator thread
    private Thread sirenThread;
    private volatile boolean isSirenRunning = false;

    // Weak / Normal mode vibration loop handler
    private Handler normalVibHandler = new Handler(Looper.getMainLooper());
    private boolean isNormalVibActive = false;

    private static final String CHANNEL_ID_SILENT = "pixel_royale_reminder_v3";
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
        webView.loadUrl("file:///android_asset/index.html");

        // Schedule background alarms immediately on launch
        AlarmScheduler.rescheduleAll(this);
        requestBatteryOptimizationExemption();
    }

    private void initNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                // Silent channel (no sound) for weak/normal mode vibration reminders
                NotificationChannel chSilent = new NotificationChannel(
                        CHANNEL_ID_SILENT,
                        "Пиксель Рояль - Уведомления",
                        NotificationManager.IMPORTANCE_HIGH
                );
                chSilent.setDescription("Вибро-оповещения без звука");
                chSilent.setSound(null, null);
                chSilent.enableVibration(true);
                chSilent.setVibrationPattern(new long[]{0, 600, 250, 600, 250, 800});
                chSilent.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
                chSilent.setBypassDnd(true);
                nm.createNotificationChannel(chSilent);

                // Alarm channel
                NotificationChannel chAlarm = new NotificationChannel(
                        CHANNEL_ID_ALARM,
                        "Пиксель Рояль - Экстра Тревога",
                        NotificationManager.IMPORTANCE_HIGH
                );
                chAlarm.setDescription("Громкая сигнализация");
                nm.createNotificationChannel(chAlarm);
            }
        }
    }

    private void showSystemNotification(String title, String text, boolean isAlarm) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
            );

            NotificationCompat.Builder builder = new NotificationCompat.Builder(
                    this,
                    isAlarm ? CHANNEL_ID_ALARM : CHANNEL_ID_SILENT
            )
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setContentIntent(pi)
                    .setAutoCancel(true);

            nm.notify(1001, builder.build());
        } catch (Exception ignored) {}
    }

    private void initCameraTorch() {
        try {
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager != null) {
                for (String id : cameraManager.getCameraIdList()) {
                    CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                    Boolean hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                    if (hasFlash != null && hasFlash && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        cameraId = id;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // Force maximum volume across all streams
    private void setMaxVolumes() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
                am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION), 0);
                am.setStreamVolume(AudioManager.STREAM_RING, am.getStreamMaxVolume(AudioManager.STREAM_RING), 0);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            }
        } catch (Exception ignored) {}
    }

    // High-pitched loud piercing siren synthesizer or Custom File playback on STREAM_ALARM (loudspeaker)
    private void startLoudSirenAudio(String sirenType) {
        stopLoudSirenAudio();

        if ("custom".equals(sirenType)) {
            File customFile = new File(getFilesDir(), CUSTOM_SOUND_FILENAME);
            if (customFile.exists() && customFile.length() > 0) {
                try {
                    customMediaPlayer = new MediaPlayer();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        customMediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build());
                    } else {
                        customMediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
                    }
                    customMediaPlayer.setDataSource(customFile.getAbsolutePath());
                    customMediaPlayer.setLooping(true);
                    customMediaPlayer.prepare();
                    customMediaPlayer.start();
                    return;
                } catch (Exception ignored) {
                    // Fallback to nuclear synthesizer if custom audio fails
                }
            }
        }

        isSirenRunning = true;

        sirenThread = new Thread(() -> {
            int sampleRate = 44100;
            int bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (bufferSize < 4096) bufferSize = 4096;

            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            AudioFormat af = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build();

            AudioTrack track;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(aa)
                        .setAudioFormat(af)
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
            } else {
                track = new AudioTrack(AudioManager.STREAM_ALARM, sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);
            }

            track.play();

            short[] buffer = new short[bufferSize];
            double phase = 0;
            double time = 0;

            while (isSirenRunning) {
                for (int i = 0; i < bufferSize; i++) {
                    double freq;
                    if ("fast".equals(sirenType)) {
                        // Fast 911 siren: alternating 900Hz and 1400Hz every 0.3s
                        freq = ((int)(time / 0.3) % 2 == 0) ? 900.0 : 1400.0;
                    } else if ("police".equals(sirenType)) {
                        // Continuous wail 700Hz to 1600Hz
                        freq = 700.0 + 900.0 * (0.5 + 0.5 * Math.sin(2 * Math.PI * 1.5 * time));
                    } else {
                        // Nuclear alarm (default): piercing 650Hz to 1800Hz sawtooth/triangle rise & fall
                        double cycle = time % 1.2;
                        if (cycle < 0.6) {
                            freq = 650.0 + (1800.0 - 650.0) * (cycle / 0.6);
                        } else {
                            freq = 1800.0 - (1800.0 - 650.0) * ((cycle - 0.6) / 0.6);
                        }
                    }

                    phase += 2.0 * Math.PI * freq / sampleRate;
                    if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;

                    // High intensity square-sine wave
                    double sample = Math.sin(phase) > 0 ? 0.95 : -0.95;
                    buffer[i] = (short) (sample * 32767);
                    time += 1.0 / sampleRate;
                }
                track.write(buffer, 0, bufferSize);
            }

            try {
                track.stop();
                track.release();
            } catch (Exception ignored) {}
        });

        sirenThread.start();
    }

    private void stopLoudSirenAudio() {
        if (customMediaPlayer != null) {
            try {
                if (customMediaPlayer.isPlaying()) {
                    customMediaPlayer.stop();
                }
                customMediaPlayer.release();
            } catch (Exception ignored) {}
            customMediaPlayer = null;
        }

        isSirenRunning = false;
        if (sirenThread != null) {
            try {
                sirenThread.join(250);
            } catch (Exception ignored) {}
            sirenThread = null;
        }
    }

    private Vibrator getVibrator() {
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

    // Extra Mode continuous vibration
    private void triggerExtraVibration() {
        try {
            Vibrator vibrator = getVibrator();
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] timings = new long[]{0, 400, 100, 400, 100, 400, 100};
                int[] amplitudes = new int[]{0, 255, 0, 255, 0, 255, 0};

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    VibrationEffect effect;
                    if (vibrator.hasAmplitudeControl()) {
                        effect = VibrationEffect.createWaveform(timings, amplitudes, 0);
                    } else {
                        effect = VibrationEffect.createWaveform(timings, 0);
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        VibrationAttributes va = new VibrationAttributes.Builder()
                                .setUsage(VibrationAttributes.USAGE_ALARM)
                                .build();
                        vibrator.vibrate(effect, va);
                    } else {
                        vibrator.vibrate(effect);
                    }
                } else {
                    vibrator.vibrate(timings, 0);
                }
            }
        } catch (Exception ignored) {}
    }

    // Weak / Normal Mode repetitive vibration:
    // "вибрация протяженностью 3 секунды с интервалом 5 секунд прямо до :00 без звука с уведомлением"
    private void startNormalVibrationLoop(int totalSeconds) {
        stopNormalVibrationLoop();
        isNormalVibActive = true;

        final long endTimeMs = System.currentTimeMillis() + (totalSeconds * 1000L);

        Runnable vibPulseRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isNormalVibActive || System.currentTimeMillis() >= endTimeMs) {
                    stopNormalVibrationLoop();
                    return;
                }

                // Vibrate for 3000ms (3 seconds)
                try {
                    Vibrator v = getVibrator();
                    if (v != null && v.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            VibrationEffect effect = VibrationEffect.createOneShot(3000, VibrationEffect.DEFAULT_AMPLITUDE);
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                VibrationAttributes va = new VibrationAttributes.Builder()
                                        .setUsage(VibrationAttributes.USAGE_ALARM)
                                        .build();
                                v.vibrate(effect, va);
                            } else {
                                v.vibrate(effect);
                            }
                        } else {
                            v.vibrate(3000);
                        }
                    }
                } catch (Exception ignored) {}

                // Next pulse in 5 seconds (5000ms)
                if (isNormalVibActive && System.currentTimeMillis() + 5000L < endTimeMs) {
                    normalVibHandler.postDelayed(this, 5000);
                } else {
                    // Final delay to clean up at end
                    long remain = Math.max(0, endTimeMs - System.currentTimeMillis());
                    normalVibHandler.postDelayed(() -> stopNormalVibrationLoop(), remain);
                }
            }
        };

        normalVibHandler.post(vibPulseRunnable);
    }

    private void stopNormalVibrationLoop() {
        isNormalVibActive = false;
        normalVibHandler.removeCallbacksAndMessages(null);
        stopVibration();
    }

    private void stopVibration() {
        try {
            Vibrator vibrator = getVibrator();
            if (vibrator != null) {
                vibrator.cancel();
            }
        } catch (Exception ignored) {}
    }

    // Flashlight Strobe (1 blink per second: 500ms ON / 500ms OFF)
    private void startFlashStrobe(int durationSec) {
        if (cameraManager == null) initCameraTorch();
        if (cameraManager == null || cameraId == null) return;

        isStrobeRunning = true;
        flashState = false;

        Runnable strobeRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isStrobeRunning) return;
                try {
                    flashState = !flashState;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        cameraManager.setTorchMode(cameraId, flashState);
                    }
                } catch (Exception ignored) {}
                strobeHandler.postDelayed(this, 500);
            }
        };
        strobeHandler.post(strobeRunnable);
    }

    private void stopFlashStrobe() {
        isStrobeRunning = false;
        strobeHandler.removeCallbacksAndMessages(null);
        if (cameraManager != null && cameraId != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cameraManager.setTorchMode(cameraId, false);
                }
            } catch (Exception ignored) {}
        }
    }

    // 1. EXTRA MODE: Max Volume, Piercing Siren, Flashlight Strobe, Heavy Vibration
    public void startNativeExtraAlarm(int durationSec, String sirenType) {
        stopAllAlarms();
        setMaxVolumes();
        showSystemNotification("🚨 Пиксель Рояль: ЭКСТРА ТРЕВОГА!", "До ивента осталась 1 минута! Скорее заходи в игру!", true);
        startLoudSirenAudio(sirenType);
        triggerExtraVibration();
        startFlashStrobe(durationSec);

        strobeHandler.postDelayed(this::stopAllAlarms, durationSec * 1000L);
    }

    // 2. WEAK / NORMAL MODE: Silent with system notification, periodic 3s vibration every 5s until :00
    public void startNativeNormalReminder(int durationUntilZeroSec, String eventTitle) {
        stopAllAlarms();

        // Show push notification
        String title = (eventTitle != null && !eventTitle.isEmpty()) ? eventTitle : "Пиксель Рояль: Скоро ивент!";
        showSystemNotification(title, "Осталось времени до :00. Телефон вибрирует каждые 5 сек по 3 сек.", false);

        // Run vibration pulses (3s pulse, 5s period) strictly until event begins (:00)
        startNormalVibrationLoop(durationUntilZeroSec > 0 ? durationUntilZeroSec : 15);
    }

    // 3. CUSTOM MODE: User picks combinations of Notification, Vibration, Flashlight, and Sound
    public void startNativeCustomReminder(int durationSec, boolean useSound, boolean useVib, boolean useFlash, boolean useNotif, String sirenType) {
        stopAllAlarms();

        if (useNotif) {
            showSystemNotification("Пиксель Рояль: Свой режим", "Сработал настраиваемый режим напоминания!", useSound);
        }

        if (useSound) {
            setMaxVolumes();
            startLoudSirenAudio(sirenType);
        }

        if (useVib) {
            if (useSound) {
                triggerExtraVibration();
            } else {
                startNormalVibrationLoop(durationSec > 0 ? durationSec : 15);
            }
        }

        if (useFlash) {
            startFlashStrobe(durationSec > 0 ? durationSec : 10);
        }

        if (durationSec > 0) {
            strobeHandler.postDelayed(this::stopAllAlarms, durationSec * 1000L);
        }
    }

    public void stopAllAlarms() {
        stopLoudSirenAudio();
        stopNormalVibrationLoop();
        stopVibration();
        stopFlashStrobe();
        if (currentRingtone != null && currentRingtone.isPlaying()) {
            try { currentRingtone.stop(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        stopAllAlarms();
        super.onDestroy();
    }

    public void startAudioFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Выберите звук тревоги"), REQUEST_PICK_AUDIO);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_AUDIO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri audioUri = data.getData();
            String displayName = getFileNameFromUri(audioUri);
            boolean success = copyAudioToInternalStorage(audioUri);
            if (success && webView != null) {
                // Save custom sound name in SharedPreferences
                getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE)
                        .edit()
                        .putString("customSoundName", displayName)
                        .apply();

                // Notify web UI
                webView.post(() -> webView.evaluateJavascript("if(window.onCustomSoundPicked) { onCustomSoundPicked(" + 
                        android.database.DatabaseUtils.sqlEscapeString(displayName) + "); }", null));
            }
        }
    }

    private String getFileNameFromUri(Uri uri) {
        String name = "Мой_звук.mp3";
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIdx >= 0) {
                    name = cursor.getString(nameIdx);
                }
            }
        } catch (Exception ignored) {}
        return name;
    }

    private boolean copyAudioToInternalStorage(Uri uri) {
        File dest = new File(getFilesDir(), CUSTOM_SOUND_FILENAME);
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) return false;
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    @SuppressLint("BatteryLife")
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                }
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception ignored) {}
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
        public void triggerExtraAlarm(int seconds, String sirenType) {
            activity.runOnUiThread(() -> activity.startNativeExtraAlarm(seconds, sirenType));
        }

        @JavascriptInterface
        public void triggerNormalReminder(int secondsUntilZero, String eventTitle) {
            activity.runOnUiThread(() -> activity.startNativeNormalReminder(secondsUntilZero, eventTitle));
        }

        @JavascriptInterface
        public void stopAlarm() {
            activity.runOnUiThread(activity::stopAllAlarms);
        }

        @JavascriptInterface
        public void triggerCustomReminder(int seconds, boolean useSound, boolean useVib, boolean useFlash, boolean useNotif, String sirenType) {
            activity.runOnUiThread(() -> activity.startNativeCustomReminder(seconds, useSound, useVib, useFlash, useNotif, sirenType));
        }

        @JavascriptInterface
        public void syncConfigAndReschedule(int captchaLead, int airdropLead, boolean extraOn, boolean normalOn, int nightStart, int nightEnd, boolean nightOn, boolean nightSilent, String sirenType, boolean customOn, boolean customFlash, boolean customVib, boolean customSound, boolean customNotif) {
            activity.runOnUiThread(() -> {
                try {
                    android.content.SharedPreferences prefs = activity.getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE);
                    prefs.edit()
                            .putInt("captchaLeadTime", captchaLead)
                            .putInt("airdropLeadTime", airdropLead)
                            .putBoolean("extraModeEnabled", extraOn)
                            .putBoolean("normalModeEnabled", normalOn)
                            .putInt("nightStartHour", nightStart)
                            .putInt("nightEndHour", nightEnd)
                            .putBoolean("nightModeEnabled", nightOn)
                            .putBoolean("nightSilentMode", nightSilent)
                            .putString("sirenTypeSelect", sirenType != null ? sirenType : "nuclear")
                            .putBoolean("customModeEnabled", customOn)
                            .putBoolean("customFlash", customFlash)
                            .putBoolean("customVib", customVib)
                            .putBoolean("customSound", customSound)
                            .putBoolean("customNotif", customNotif)
                            .apply();
                    AlarmScheduler.rescheduleAll(activity);
                } catch (Exception ignored) {}
            });
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
                        java.io.InputStream in = conn.getInputStream();
                        java.util.Scanner scanner = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A");
                        String response = scanner.hasNext() ? scanner.next() : "";
                        scanner.close();
                        in.close();

                        org.json.JSONObject release = new org.json.JSONObject(response);
                        String latestTag = release.optString("tag_name", "");
                        String releaseName = release.optString("name", latestTag);
                        String htmlUrl = release.optString("html_url", "");
                        String downloadUrl = "";

                        org.json.JSONArray assets = release.optJSONArray("assets");
                        if (assets != null) {
                            for (int i = 0; i < assets.length(); i++) {
                                org.json.JSONObject asset = assets.getJSONObject(i);
                                String name = asset.optString("name", "");
                                if (name.endsWith(".apk")) {
                                    downloadUrl = asset.optString("browser_download_url", "");
                                    break;
                                }
                            }
                        }

                        if (downloadUrl.isEmpty() && !latestTag.isEmpty()) {
                            downloadUrl = "https://github.com/ivanmaspa/pixroyale-reminders/releases/download/" + latestTag + "/PixRoyale_" + latestTag + ".apk";
                        }

                        final String fTag = latestTag;
                        final String fName = releaseName;
                        final String fUrl = downloadUrl.isEmpty() ? htmlUrl : downloadUrl;

                        activity.runOnUiThread(() -> {
                            if (activity.webView != null) {
                                String js = String.format("if (window.onUpdateCheckResult) { window.onUpdateCheckResult('%s', '%s', '%s'); }",
                                        fTag.replace("'", "\\'"),
                                        fName.replace("'", "\\'"),
                                        fUrl.replace("'", "\\'"));
                                activity.webView.evaluateJavascript(js, null);
                            }
                        });
                    } else {
                        activity.runOnUiThread(() -> {
                            if (activity.webView != null) {
                                activity.webView.evaluateJavascript("if (window.onUpdateCheckResult) { window.onUpdateCheckResult(null, 'Ошибка соединения', null); }", null);
                            }
                        });
                    }
                } catch (Exception e) {
                    activity.runOnUiThread(() -> {
                        if (activity.webView != null) {
                            activity.webView.evaluateJavascript("if (window.onUpdateCheckResult) { window.onUpdateCheckResult(null, 'Ошибка проверки: " + e.getMessage() + "', null); }", null);
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
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; PixRoyale-x2i01z)");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);

                    int responseCode = conn.getResponseCode();
                    if (responseCode == java.net.HttpURLConnection.HTTP_MOVED_PERM || 
                        responseCode == java.net.HttpURLConnection.HTTP_MOVED_TEMP || 
                        responseCode == 307 || responseCode == 308) {
                        String newUrl = conn.getHeaderField("Location");
                        conn = (java.net.HttpURLConnection) new java.net.URL(newUrl).openConnection();
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; PixRoyale-x2i01z)");
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(30000);
                    }

                    int fileLength = conn.getContentLength();
                    java.io.InputStream input = new java.io.BufferedInputStream(conn.getInputStream());

                    File cacheDir = activity.getExternalCacheDir();
                    if (cacheDir == null) {
                        cacheDir = activity.getCacheDir();
                    }
                    File apkFile = new File(cacheDir, "update.apk");
                    if (apkFile.exists()) {
                        apkFile.delete();
                    }

                    java.io.OutputStream output = new java.io.FileOutputStream(apkFile);
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
                                String.format("if (window.onDownloadProgress) { window.onDownloadProgress(-1, 'Ошибка: %s'); }", (e.getMessage() != null ? e.getMessage().replace("'", "\'") : "Сбой сети")),
                                null);
                        }
                    });
                }
            }).start();
        }

        @JavascriptInterface
        public void openDownloadUrl(String url) {
            activity.runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void testSingleVibration() {
            activity.runOnUiThread(() -> {
                try {
                    Vibrator v = activity.getVibrator();
                    if (v != null && v.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            VibrationEffect effect = VibrationEffect.createOneShot(3000, VibrationEffect.DEFAULT_AMPLITUDE);
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                VibrationAttributes va = new VibrationAttributes.Builder()
                                        .setUsage(VibrationAttributes.USAGE_ALARM)
                                        .build();
                                v.vibrate(effect, va);
                            } else {
                                v.vibrate(effect);
                            }
                        } else {
                            v.vibrate(3000);
                        }
                    }
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void pickCustomAudioFile() {
            activity.runOnUiThread(activity::startAudioFilePicker);
        }

        @JavascriptInterface
        public String getCustomSoundName() {
            android.content.SharedPreferences prefs = activity.getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE);
            return prefs.getString("customSoundName", "");
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
                        domain = "PBMap_bot";
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
                        Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/PBMap_bot"));
                        webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(webIntent);
                    } catch (Exception ignored) {}
                }
            });
        }
    }
}
