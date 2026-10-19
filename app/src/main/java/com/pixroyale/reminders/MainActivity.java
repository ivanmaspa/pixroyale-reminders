package com.pixroyale.reminders;

import android.annotation.SuppressLint;
import android.content.Context;
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

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        // Auto request camera runtime permission if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 101);
            }
        }

        // Init Camera for Flashlight strobe
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
            double t = 0;

            while (isSirenRunning) {
                for (int i = 0; i < buffer.length; i++) {
                    double freq;
                    if ("nuclear".equals(sirenType)) {
                        // Nuclear war alarm: sweeps 400Hz to 1200Hz every 2 seconds
                        double cycle = (t % 2.0) / 2.0;
                        freq = 400 + 800 * (0.5 * (1 - Math.cos(2 * Math.PI * cycle)));
                    } else if ("fast".equals(sirenType)) {
                        // Fast European Police siren: sweeps 800Hz to 1600Hz twice per second
                        double cycle = (t % 0.5) / 0.5;
                        freq = 800 + 800 * cycle;
                    } else {
                        // Standard Police wail: sweeps 600Hz to 1400Hz every 1 second
                        double cycle = (t % 1.0);
                        freq = 600 + 800 * Math.abs(Math.sin(Math.PI * cycle));
                    }

                    phase += 2 * Math.PI * freq / sampleRate;
                    if (phase > 2 * Math.PI) phase -= 2 * Math.PI;

                    // Square / clipped sine wave for extreme loudness & cutting harmonics
                    double rawSample = Math.sin(phase) + 0.3 * Math.sin(3 * phase);
                    if (rawSample > 0.8) rawSample = 0.8;
                    if (rawSample < -0.8) rawSample = -0.8;

                    buffer[i] = (short) (rawSample * 32767);
                    t += 1.0 / sampleRate;
                }
                track.write(buffer, 0, buffer.length);
            }

            track.stop();
            track.release();
        });
        sirenThread.setPriority(Thread.MAX_PRIORITY);
        sirenThread.start();
    }

    private void stopLoudSirenAudio() {
        isSirenRunning = false;
        if (sirenThread != null) {
            try { sirenThread.interrupt(); } catch (Exception ignored) {}
            sirenThread = null;
        }
    }

    // Robust Native Vibration using both Vibrator and VibratorManager (Android 12-16)
    private void triggerRobustVibration(int durationSec, boolean isExtra) {
        try {
            Vibrator vibrator = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) vibrator = vm.getDefaultVibrator();
            }
            if (vibrator == null) {
                vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                long[] timings;
                int[] amplitudes;

                if (isExtra) {
                    // Maximum aggressive pulse vibration
                    timings = new long[]{0, 400, 100, 400, 100, 400, 100};
                    amplitudes = new int[]{0, 255, 0, 255, 0, 255, 0};
                } else {
                    // Gentle normal vibration
                    timings = new long[]{0, 300, 200, 300};
                    amplitudes = new int[]{0, 180, 0, 180};
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    VibrationEffect effect;
                    if (vibrator.hasAmplitudeControl()) {
                        effect = VibrationEffect.createWaveform(timings, amplitudes, isExtra ? 0 : -1);
                    } else {
                        effect = VibrationEffect.createWaveform(timings, isExtra ? 0 : -1);
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
                    vibrator.vibrate(timings, isExtra ? 0 : -1);
                }
            }
        } catch (Exception ignored) {}
    }

    private void stopVibration() {
        try {
            Vibrator vibrator = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) vibrator = vm.getDefaultVibrator();
            }
            if (vibrator == null) {
                vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
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
        startLoudSirenAudio(sirenType);
        triggerRobustVibration(durationSec, true);
        startFlashStrobe(durationSec);

        strobeHandler.postDelayed(this::stopAllAlarms, durationSec * 1000L);
    }

    // 2. NORMAL MODE: Standard notification chime, mild vibration, NO flashlight
    public void startNativeNormalReminder() {
        stopAllAlarms();
        try {
            Uri notifUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), notifUri);
            if (r != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    r.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                }
                r.play();
            }
        } catch (Exception ignored) {}

        triggerRobustVibration(2, false);
    }

    public void stopAllAlarms() {
        stopLoudSirenAudio();
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
        public void triggerNormalReminder() {
            activity.runOnUiThread(activity::startNativeNormalReminder);
        }

        @JavascriptInterface
        public void stopAlarm() {
            activity.runOnUiThread(activity::stopAllAlarms);
        }
    }
}
