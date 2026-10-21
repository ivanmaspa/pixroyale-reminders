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
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
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
    public static final String EXTRA_MODE = "extra_mode";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_TEXT = "extra_text";

    private static final String CHANNEL_ID_REMINDER = "pixel_royale_reminder_v3";
    private static final String CHANNEL_ID_ALARM = "pixel_royale_alarm_v3";
    private static final String PREFS_AUDIO = "PixRoyaleAudioState";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (ACTION_RESTORE_AUDIO.equals(action)) {
            restoreAudioState(context);
            AlarmScheduler.rescheduleAll(context);
            return;
        }

        String mode = intent.getStringExtra(EXTRA_MODE);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String text = intent.getStringExtra(EXTRA_TEXT);

        if (title == null) title = "Pixel Royale";
        if (text == null) text = "Время ивента!";

        // Acquire WakeLock for 15s to guarantee vibration & audio play completely
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                    "PixRoyale:AlarmWakeLock"
            );
            wakeLock.acquire(15 * 1000L);
        }

        SharedPreferences prefs = context.getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE);
        boolean nightEnabled = prefs.getBoolean("nightModeEnabled", true);
        boolean nightSilent = prefs.getBoolean("nightSilentMode", true);
        int nightStart = prefs.getInt("nightStartHour", 0);
        int nightEnd = prefs.getInt("nightEndHour", 8);

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

        boolean extraEnabled = prefs.getBoolean("extraModeEnabled", true);
        boolean normalEnabled = prefs.getBoolean("normalModeEnabled", false);
        boolean customEnabled = prefs.getBoolean("customModeEnabled", false);
        boolean customFlash = prefs.getBoolean("customFlash", false);
        boolean customVib = prefs.getBoolean("customVib", true);
        boolean customSound = prefs.getBoolean("customSound", true);
        boolean customNotif = prefs.getBoolean("customNotif", true);

        int vibStrength = prefs.getInt("vibStrength", 4);
        int soundVolume = prefs.getInt("soundVolume", 50);
        String soundType = prefs.getString("soundType", "chime");

        if ("extra".equals(mode) && !extraEnabled) {
            AlarmScheduler.rescheduleAll(context);
            return;
        }
        if ("weak".equals(mode) && !normalEnabled) {
            AlarmScheduler.rescheduleAll(context);
            return;
        }
        if ("custom".equals(mode) && !customEnabled) {
            AlarmScheduler.rescheduleAll(context);
            return;
        }

        // Show system notification
        if (!"custom".equals(mode) || customNotif) {
            showNotification(context, title, text, "extra".equals(mode));
        }

        // Handle Audio & Vibration if not in silent night mode
        if (!isNight || !nightSilent) {
            if ("custom".equals(mode)) {
                if (customSound) {
                    playNotificationTone(context, soundVolume, soundType);
                }
                if (customVib) {
                    performConfiguredVibration(context, vibStrength, false);
                }
                if (customFlash) {
                    pulseFlashlight(context, 4000);
                }
            } else if ("extra".equals(mode)) {
                // Enhanced Mode: gentle melodic sound at user volume + heavy vibration + flashlight
                playNotificationTone(context, soundVolume, soundType);
                performConfiguredVibration(context, Math.max(4, vibStrength), true);
                pulseFlashlight(context, 4000);
            } else {
                // Weak / Standard Mode:
                prepareWeakAudioState(context, soundVolume);
                playNotificationTone(context, soundVolume, soundType);
                performConfiguredVibration(context, vibStrength, false);
            }
        }

        // Automatically reschedule next alarms to keep cycle going forever
        AlarmScheduler.rescheduleAll(context);
    }

    public static void prepareWeakAudioState(Context context, int targetVolumePercent) {
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

                if (wasMuted) {
                    am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                }

                int max = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
                int target = Math.max(1, (int) Math.round((targetVolumePercent / 100.0) * max));
                am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, target, 0);
            }
        } catch (Exception ignored) {}
    }

    public static void restoreAudioState(Context context) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;

            SharedPreferences audioPrefs = context.getSharedPreferences(PREFS_AUDIO, Context.MODE_PRIVATE);
            boolean isActive = audioPrefs.getBoolean("state_active", false);
            if (!isActive) return;

            boolean wasMuted = audioPrefs.getBoolean("was_muted", false);
            int prevRingerMode = audioPrefs.getInt("prev_ringer_mode", AudioManager.RINGER_MODE_NORMAL);
            boolean wasZero = audioPrefs.getBoolean("was_zero_volume", false);
            int prevVol = audioPrefs.getInt("prev_volume", 0);

            if (wasMuted) {
                am.setRingerMode(prevRingerMode);
            }
            if (wasZero) {
                am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0);
            } else if (prevVol > 0) {
                am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, prevVol, 0);
            }

            audioPrefs.edit().clear().apply();
        } catch (Exception ignored) {}
    }

    public static Intent createGameLaunchIntent(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE);
            String customTarget = prefs.getString("gameLaunchTarget", "").trim();
            if (customTarget.isEmpty()) {
                customTarget = "tg://resolve?domain=PBMap_bot";
            }

            android.content.pm.PackageManager pm = context.getPackageManager();

            if (customTarget.startsWith("http://") || customTarget.startsWith("https://") || customTarget.startsWith("tg://")) {
                Uri uri = Uri.parse(customTarget);
                String[] tgPackages = new String[]{
                        "org.telegram.messenger",
                        "org.telegram.plus",
                        "org.thunderdog.challegram",
                        "org.telegram.messenger.web",
                        "org.telegram.messenger.beta"
                };
                for (String tgPkg : tgPackages) {
                    try {
                        pm.getPackageInfo(tgPkg, 0);
                        Intent tgIntent = new Intent(Intent.ACTION_VIEW, uri);
                        tgIntent.setPackage(tgPkg);
                        tgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        return tgIntent;
                    } catch (Exception ignored) {}
                }

                Intent directTg = new Intent(Intent.ACTION_VIEW, uri);
                directTg.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                return directTg;
            } else {
                Intent pkgIntent = pm.getLaunchIntentForPackage(customTarget);
                if (pkgIntent != null) {
                    pkgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    return pkgIntent;
                }
            }
        } catch (Exception ignored) {}

        try {
            Intent webTg = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/PBMap_bot"));
            webTg.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return webTg;
        } catch (Exception ignored) {}

        Intent fallback = new Intent(context, MainActivity.class);
        fallback.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return fallback;
    }

    private void showNotification(Context context, String title, String text, boolean isAlarm) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        String channelId = isAlarm ? CHANNEL_ID_ALARM : CHANNEL_ID_REMINDER;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    channelId,
                    isAlarm ? "Пиксель Рояль: Важные события" : "Пиксель Рояль: Напоминания",
                    NotificationManager.IMPORTANCE_HIGH
            );
            ch.setDescription("Уведомления и вибро-напоминания");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 500, 200, 500, 200, 600});
            ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            ch.setBypassDnd(true);
            nm.createNotificationChannel(ch);
        }

        Intent gameIntent = createGameLaunchIntent(context);
        PendingIntent gamePi = PendingIntent.getActivity(
                context,
                (int) System.currentTimeMillis(),
                gameIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent openAppIntent = new Intent(context, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent appPi = PendingIntent.getActivity(
                context,
                (int) System.currentTimeMillis() + 1,
                openAppIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_kite_cut)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(gamePi)
                .setFullScreenIntent(gamePi, true)
                .addAction(R.drawable.ic_kite_cut, "🎮 В игру", gamePi)
                .addAction(R.drawable.ic_kite_cut, "⏱ В приложение", appPi)
                .setVibrate(new long[]{0, 500, 200, 500, 200, 600})
                .setAutoCancel(true);

        nm.notify((int) (System.currentTimeMillis() % 100000), builder.build());
    }

    public static void performConfiguredVibration(Context context, int strength, boolean isLong) {
        try {
            Vibrator v = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) v = vm.getDefaultVibrator();
            }
            if (v == null) {
                v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v == null || !v.hasVibrator()) return;

            int amp;
            long pulseTime;
            long pauseTime;
            int count;

            switch (strength) {
                case 1:
                    amp = 70;
                    pulseTime = 250;
                    pauseTime = 300;
                    count = 2;
                    break;
                case 2:
                    amp = 120;
                    pulseTime = 400;
                    pauseTime = 200;
                    count = 3;
                    break;
                case 3:
                    amp = 175;
                    pulseTime = 600;
                    pauseTime = 200;
                    count = 3;
                    break;
                case 4:
                    amp = 220;
                    pulseTime = 800;
                    pauseTime = 180;
                    count = 4;
                    break;
                case 5:
                default:
                    amp = 255;
                    pulseTime = 1000;
                    pauseTime = 150;
                    count = 4;
                    break;
            }

            if (isLong) count += 1;

            long[] timings = new long[count * 2];
            int[] amplitudes = new int[count * 2];
            timings[0] = 0;
            amplitudes[0] = 0;

            for (int i = 0; i < count; i++) {
                timings[i * 2] = (i == 0) ? 0 : pauseTime;
                amplitudes[i * 2] = 0;
                timings[i * 2 + 1] = pulseTime;
                amplitudes[i * 2 + 1] = amp;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect effect;
                if (v.hasAmplitudeControl()) {
                    effect = VibrationEffect.createWaveform(timings, amplitudes, -1);
                } else {
                    effect = VibrationEffect.createWaveform(timings, -1);
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    VibrationAttributes va = new VibrationAttributes.Builder()
                            .setUsage(VibrationAttributes.USAGE_ALARM)
                            .build();
                    v.vibrate(effect, va);
                } else {
                    AudioAttributes aa = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build();
                    v.vibrate(effect, aa);
                }
            } else {
                v.vibrate(timings, -1);
            }
        } catch (Exception ignored) {}
    }

    public static void playNotificationTone(Context context, int volumePercent, String soundType) {
        new Thread(() -> {
            try {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
                    int targetVol = Math.max(1, (int) Math.round((volumePercent / 100.0) * maxVol));
                    am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, targetVol, 0);
                }

                // If user selected custom sound
                if ("custom".equals(soundType)) {
                    File customFile = new File(context.getFilesDir(), "custom_alarm_sound");
                    if (customFile.exists() && customFile.length() > 0) {
                        MediaPlayer mp = new MediaPlayer();
                        mp.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build());
                        mp.setDataSource(customFile.getAbsolutePath());
                        float vol = Math.max(0.05f, volumePercent / 100.0f);
                        mp.setVolume(vol, vol);
                        mp.prepare();
                        mp.start();
                        Thread.sleep(3500);
                        if (mp.isPlaying()) mp.stop();
                        mp.release();
                        return;
                    }
                }

                // If system notification sound
                if ("system".equals(soundType)) {
                    Uri notifUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                    Ringtone r = RingtoneManager.getRingtone(context, notifUri);
                    if (r != null) {
                        r.play();
                        return;
                    }
                }

                // Default melodic chime chord
                int sampleRate = 44100;
                int durationSamples = (int) (sampleRate * 1.5);
                short[] buffer = new short[durationSamples];
                float volScale = (volumePercent / 100.0f) * 0.75f;

                double f1 = 523.25; // C5
                double f2 = 659.25; // E5
                double f3 = 783.99; // G5
                double f4 = 1046.50; // C6

                for (int i = 0; i < durationSamples; i++) {
                    double t = (double) i / sampleRate;
                    double env = Math.exp(-3.5 * t);
                    double sample = 0.35 * Math.sin(2 * Math.PI * f1 * t)
                                  + 0.30 * Math.sin(2 * Math.PI * f2 * t)
                                  + 0.25 * Math.sin(2 * Math.PI * f3 * t)
                                  + 0.10 * Math.sin(2 * Math.PI * f4 * t);
                    buffer[i] = (short) (sample * env * volScale * 32767);
                }

                AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(buffer.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();

                track.write(buffer, 0, buffer.length);
                track.play();
                Thread.sleep(1600);
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
                if (camId == null && cm.getCameraIdList().length > 0) camId = cm.getCameraIdList()[0];
                if (camId == null) return;

                long end = System.currentTimeMillis() + durationMs;
                boolean state = false;
                while (System.currentTimeMillis() < end) {
                    state = !state;
                    try { cm.setTorchMode(camId, state); } catch (Exception ignored) {}
                    Thread.sleep(400);
                }
                try { cm.setTorchMode(camId, false); } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }).start();
    }
}
