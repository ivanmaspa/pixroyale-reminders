package com.pixroyale.reminders;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
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

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        // Init Camera for Flashlight strobe
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

    public void startNativeExtraAlarm(int durationSec) {
        // 1. Set notification and ringtone volumes to max on phone speaker
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int maxAlarm = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                int maxNotif = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
                int maxRing = am.getStreamMaxVolume(AudioManager.STREAM_RING);

                am.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0);
                am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, maxNotif, 0);
                am.setStreamVolume(AudioManager.STREAM_RING, maxRing, 0);
            }
        } catch (Exception ignored) {}

        // 2. Play Alarm/Ringtone siren through STREAM_ALARM (loudspeaker, bypass headphones)
        try {
            stopNativeExtraAlarm();
            Uri alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alertUri == null) {
                alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            currentRingtone = RingtoneManager.getRingtone(getApplicationContext(), alertUri);
            if (currentRingtone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    AudioAttributes aa = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build();
                    currentRingtone.setAudioAttributes(aa);
                }
                currentRingtone.play();
            }
        } catch (Exception ignored) {}

        // 3. Vibration
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                long[] pattern = {0, 600, 200, 600, 200, 600};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                } else {
                    v.vibrate(pattern, -1);
                }
            }
        } catch (Exception ignored) {}

        // 4. Strobe Flashlight every second (Blinking)
        startFlashStrobe(durationSec);

        // Auto-stop after duration
        strobeHandler.postDelayed(this::stopNativeExtraAlarm, durationSec * 1000L);
    }

    private void startFlashStrobe(int durationSec) {
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
                } catch (CameraAccessException | IllegalArgumentException ignored) {}

                // Toggle every 500ms = 1 full blink per second
                strobeHandler.postDelayed(this, 500);
            }
        };
        strobeHandler.post(strobeRunnable);
    }

    public void stopNativeExtraAlarm() {
        try {
            if (currentRingtone != null && currentRingtone.isPlaying()) {
                currentRingtone.stop();
            }
        } catch (Exception ignored) {}

        isStrobeRunning = false;
        strobeHandler.removeCallbacksAndMessages(null);

        // Turn off torch
        if (cameraManager != null && cameraId != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cameraManager.setTorchMode(cameraId, false);
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        stopNativeExtraAlarm();
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
        public void triggerExtraAlarm(int seconds) {
            activity.runOnUiThread(() -> activity.startNativeExtraAlarm(seconds));
        }

        @JavascriptInterface
        public void stopAlarm() {
            activity.runOnUiThread(activity::stopNativeExtraAlarm);
        }
    }
}
