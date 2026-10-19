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
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private Ringtone currentRingtone;
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

    private static final String CHANNEL_ID_SILENT = "pixel_royale_silent_channel";
    private static final String CHANNEL_ID_ALARM = "pixel_royale_alarm_channel";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        // Auto request camera runtime permission if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        android.Manifest.permission.CAMERA,
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
                chSilent.enableVibration(false);
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

    // High-pitched loud piercing siren synthesizer using AudioTrack STREAM_ALARM (loudspeaker)
    private void startLoudSirenAudio(String sirenType) {
        stopLoudSirenAudio();
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
                                        .setUsage(VibrationAttributes.USAGE_NOTIFICATION)
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

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
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
        public void syncConfigAndReschedule(int captchaLead, int airdropLead, boolean extraOn, boolean normalOn, int nightStart, int nightEnd, boolean nightOn, boolean nightSilent) {
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
                            .apply();
                    AlarmScheduler.rescheduleAll(activity);
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
                                        .setUsage(VibrationAttributes.USAGE_NOTIFICATION)
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
        public void openTelegramBot(String usernameOrUrl) {
            activity.runOnUiThread(() -> {
                try {
                    String url = usernameOrUrl;
                    if (url.startsWith("@")) {
                        url = "https://t.me/" + url.substring(1);
                    } else if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("tg:")) {
                        url = "https://t.me/" + url;
                    }
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                } catch (Exception e) {
                    try {
                        Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/" + usernameOrUrl.replace("@", "")));
                        webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(webIntent);
                    } catch (Exception ignored) {}
                }
            });
        }
    }
}
