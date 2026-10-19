package com.pixroyale.reminders;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import androidx.core.app.NotificationCompat;
import java.io.File;

public class ReminderBroadcastReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE_REMINDER = "com.pixroyale.reminders.FIRE_REMINDER";
    public static final String ACTION_RESTORE_AUDIO = "com.pixroyale.reminders.RESTORE_AUDIO";
    public static final String EXTRA_MODE = "extra_mode"; // "weak" or "extra"
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_TEXT = "extra_text";
    public static final String EXTRA_DURATION = "extra_duration"; // in seconds

    private static final String CHANNEL_ID_SILENT = "pixel_royale_silent_channel";
    private static final String CHANNEL_ID_ALARM = "pixel_royale_alarm_channel";
    private static final String PREFS_AUDIO = "PixRoyaleAudioState";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        // Handle Audio State Restoration at :00
        if (ACTION_RESTORE_AUDIO.equals(action)) {
            restoreAudioState(context);
            // Reschedule next cycle
            AlarmScheduler.rescheduleAll(context);
            return;
        }

        String mode = intent.getStringExtra(EXTRA_MODE);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String text = intent.getStringExtra(EXTRA_TEXT);
        int durationSec = intent.getIntExtra(EXTRA_DURATION, 3);

        if (title == null) title = "Пиксель Рояль";
        if (text == null) text = "Время ивента!";

        // Acquire WakeLock to guarantee device wakes up from deep sleep / doze mode
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "PixRoyale:AlarmWakeLock");
            wakeLock.acquire(10 * 1000L); // 10 seconds max
        }

        // Check Night Mode preferences
        SharedPreferences prefs = context.getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE);
        boolean nightEnabled = prefs.getBoolean("nightModeEnabled", true);
        boolean nightSilent = prefs.getBoolean("nightSilentMode", true);
        int nightStart = prefs.getInt("nightStartHour", 0);
        int nightEnd = prefs.getInt("nightEndHour", 8);

        // Get current MSK hour
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Moscow"));
        int curH = cal.get(java.util.Calendar.HOUR_OF_DAY);

        boolean isNight = false;
        if (nightEnabled) {
            if (nightStart > nightEnd) {
                isNight = (curH >= nightStart || curH < nightEnd);
            } else {
                isNight = (curH >= nightStart && curH < nightEnd);
            }
        }

        // Check mode flags directly from SharedPreferences to guarantee user settings are respected
        boolean extraEnabled = prefs.getBoolean("extraModeEnabled", true);
        boolean normalEnabled = prefs.getBoolean("normalModeEnabled", true);
        boolean customEnabled = prefs.getBoolean("customModeEnabled", false);
        boolean customFlash = prefs.getBoolean("customFlash", false);
        boolean customVib = prefs.getBoolean("customVib", true);
        boolean customSound = prefs.getBoolean("customSound", false);
        boolean customNotif = prefs.getBoolean("customNotif", true);

        // If extra mode was triggered but user turned off extraModeEnabled, ignore it completely
        if ("extra".equals(mode) && !extraEnabled) {
            AlarmScheduler.rescheduleAll(context);
            if (wakeLock != null && wakeLock.isHeld()) {
                try { wakeLock.release(); } catch (Exception ignored) {}
            }
            return;
        }

        // If weak mode was triggered but user turned off normalModeEnabled, ignore it completely
        if ("weak".equals(mode) && !normalEnabled) {
            AlarmScheduler.rescheduleAll(context);
            if (wakeLock != null && wakeLock.isHeld()) {
                try { wakeLock.release(); } catch (Exception ignored) {}
            }
            return;
        }

        // If custom mode was triggered but user turned off customModeEnabled, ignore it
        if ("custom".equals(mode) && !customEnabled) {
            AlarmScheduler.rescheduleAll(context);
            if (wakeLock != null && wakeLock.isHeld()) {
                try { wakeLock.release(); } catch (Exception ignored) {}
            }
            return;
        }

        // Show push notification
        if ("custom".equals(mode)) {
            if (customNotif) {
                showNotification(context, title, text, customSound);
            }
        } else {
            showNotification(context, title, text, "extra".equals(mode));
        }

        // Vibration, Flash, and Audio behavior:
        if (!isNight || !nightSilent) {
            if ("custom".equals(mode)) {
                if (customSound) {
                    playLoudAlarm(context);
                }
                if (customVib) {
                    performVibration(context, 3000, customSound);
                }
                if (customFlash) {
                    pulseFlashlight(context, 5000);
                }
            } else if ("extra".equals(mode)) {
                // Extra mode: play loud alarm and 5-sec alarm vibration
                playLoudAlarm(context);
                performVibration(context, 5000, true);
            } else {
                // Weak mode:
                // Handle smart audio unmute & minimum volume
                prepareWeakAudioState(context);

                // Weak mode vibration: 3 seconds
                performVibration(context, 3000, false);
            }
        }

        // Automatically reschedule next alarms to keep cycle going forever
        AlarmScheduler.rescheduleAll(context);

        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
    }

    public static void prepareWeakAudioState(Context context) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;

            SharedPreferences audioPrefs = context.getSharedPreferences(PREFS_AUDIO, Context.MODE_PRIVATE);
            boolean alreadySaved = audioPrefs.getBoolean("state_active", false);

            if (!alreadySaved) {
                int ringerMode = am.getRingerMode();
                boolean wasMuted = (ringerMode != AudioManager.RINGER_MODE_NORMAL);
                int notifVol = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
                boolean wasZero = (notifVol == 0);

                audioPrefs.edit()
                        .putBoolean("state_active", true)
                        .putBoolean("was_muted", wasMuted)
                        .putInt("prev_ringer_mode", ringerMode)
                        .putBoolean("was_zero_volume", wasZero)
                        .putInt("prev_volume", notifVol)
                        .apply();

                // If muted/vibrate, switch to NORMAL mode without changing volume
                if (wasMuted) {
                    try {
                        am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                    } catch (Exception ignored) {}
                }

                // If volume was 0 (slider at minimum), make it at least 1 (audible)
                if (wasZero) {
                    try {
                        am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 1, 0);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    public static void restoreAudioState(Context context) {
        try {
            SharedPreferences audioPrefs = context.getSharedPreferences(PREFS_AUDIO, Context.MODE_PRIVATE);
            boolean stateActive = audioPrefs.getBoolean("state_active", false);
            if (!stateActive) return;

            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                boolean wasZero = audioPrefs.getBoolean("was_zero_volume", false);
                int prevVol = audioPrefs.getInt("prev_volume", 0);
                if (wasZero) {
                    try {
                        am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, prevVol, 0);
                    } catch (Exception ignored) {}
                }

                boolean wasMuted = audioPrefs.getBoolean("was_muted", false);
                int prevRinger = audioPrefs.getInt("prev_ringer_mode", AudioManager.RINGER_MODE_NORMAL);
                if (wasMuted) {
                    try {
                        am.setRingerMode(prevRinger);
                    } catch (Exception ignored) {}
                }
            }

            audioPrefs.edit().clear().apply();
        } catch (Exception ignored) {}
    }

    private void showNotification(Context context, String title, String text, boolean isAlarm) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    isAlarm ? CHANNEL_ID_ALARM : CHANNEL_ID_SILENT,
                    isAlarm ? "Пиксель Рояль: Экстра Тревога" : "Пиксель Рояль: Уведомления",
                    NotificationManager.IMPORTANCE_HIGH
            );
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }

        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context,
                isAlarm ? CHANNEL_ID_ALARM : CHANNEL_ID_SILENT
        )
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setContentIntent(pi)
                .setAutoCancel(true);

        nm.notify((int) System.currentTimeMillis(), builder.build());
    }

    private void performVibration(Context context, long durationMs, boolean isAlarm) {
        try {
            Vibrator v = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) v = vm.getDefaultVibrator();
            }
            if (v == null) {
                v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }

            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    VibrationEffect effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        VibrationAttributes va = new VibrationAttributes.Builder()
                                .setUsage(isAlarm ? VibrationAttributes.USAGE_ALARM : VibrationAttributes.USAGE_NOTIFICATION)
                                .build();
                        v.vibrate(effect, va);
                    } else {
                        v.vibrate(effect);
                    }
                } else {
                    v.vibrate(durationMs);
                }
            }
        } catch (Exception ignored) {}
    }

    private void playLoudAlarm(Context context) {
        new Thread(() -> {
            try {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
                }

                SharedPreferences prefs = context.getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE);
                String sirenType = prefs.getString("sirenTypeSelect", "nuclear");

                if ("custom".equals(sirenType)) {
                    File customFile = new File(context.getFilesDir(), "custom_alarm_sound");
                    if (customFile.exists() && customFile.length() > 0) {
                        try {
                            MediaPlayer mp = new MediaPlayer();
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                mp.setAudioAttributes(new AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_ALARM)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .build());
                            } else {
                                mp.setAudioStreamType(AudioManager.STREAM_ALARM);
                            }
                            mp.setDataSource(customFile.getAbsolutePath());
                            mp.prepare();
                            mp.start();
                            Thread.sleep(5000);
                            if (mp.isPlaying()) mp.stop();
                            mp.release();
                            return;
                        } catch (Exception ignored) {}
                    }
                }

                int sampleRate = 44100;
                int bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioAttributes aa = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                AudioFormat af = new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build();

                AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(aa)
                        .setAudioFormat(af)
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();

                track.play();
                short[] buffer = new short[bufferSize];
                double phase = 0;
                long end = System.currentTimeMillis() + 4000L;
                double time = 0;

                while (System.currentTimeMillis() < end) {
                    for (int i = 0; i < bufferSize; i++) {
                        double freq;
                        if ("fast".equals(sirenType)) {
                            freq = ((int)(time / 0.3) % 2 == 0) ? 900.0 : 1400.0;
                        } else if ("police".equals(sirenType)) {
                            freq = 700.0 + 900.0 * (0.5 + 0.5 * Math.sin(2 * Math.PI * 1.5 * time));
                        } else {
                            double cycle = time % 1.2;
                            freq = (cycle < 0.6) ? 700 + 1100 * (cycle / 0.6) : 1800 - 1100 * ((cycle - 0.6) / 0.6);
                        }
                        phase += 2.0 * Math.PI * freq / sampleRate;
                        if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;
                        double sample = Math.sin(phase) > 0 ? 0.95 : -0.95;
                        buffer[i] = (short) (sample * 32767);
                        time += 1.0 / sampleRate;
                    }
                    track.write(buffer, 0, bufferSize);
                }
                track.stop();
                track.release();
            } catch (Exception ignored) {}
        }).start();
    }

    private void pulseFlashlight(Context context, long durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        new Thread(() -> {
            try {
                android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                if (cm == null) return;
                String camId = null;
                for (String id : cm.getCameraIdList()) {
                    android.hardware.camera2.CameraCharacteristics chars = cm.getCameraCharacteristics(id);
                    Boolean hasFlash = chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                    if (hasFlash != null && hasFlash && facing != null && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                        camId = id;
                        break;
                    }
                }
                if (camId == null && cm.getCameraIdList().length > 0) {
                    camId = cm.getCameraIdList()[0];
                }
                if (camId == null) return;

                long end = System.currentTimeMillis() + durationMs;
                boolean state = false;
                while (System.currentTimeMillis() < end) {
                    state = !state;
                    try {
                        cm.setTorchMode(camId, state);
                    } catch (Exception ignored) {}
                    Thread.sleep(500);
                }
                try {
                    cm.setTorchMode(camId, false);
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }).start();
    }
}
