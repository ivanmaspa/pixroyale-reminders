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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import androidx.core.app.NotificationCompat;

public class ReminderBroadcastReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE_REMINDER = "com.pixroyale.reminders.FIRE_REMINDER";
    public static final String EXTRA_MODE = "extra_mode"; // "weak" or "extra"
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_TEXT = "extra_text";
    public static final String EXTRA_DURATION = "extra_duration"; // in seconds

    private static final String CHANNEL_ID_SILENT = "pixel_royale_silent_channel";
    private static final String CHANNEL_ID_ALARM = "pixel_royale_alarm_channel";

    @Override
    public void onReceive(Context context, Intent intent) {
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

        // Show push notification
        showNotification(context, title, text, "extra".equals(mode));

        // Vibration behavior:
        // "должно 1 раз вебрировать и по времени заданом в приложении"
        if (!isNight || !nightSilent) {
            if ("extra".equals(mode)) {
                // Extra mode: play loud alarm and 5-sec alarm vibration
                playLoudAlarm(context);
                performVibration(context, 5000, true);
            } else {
                // Weak mode: strictly 1 vibration pulse without sounds, duration 3 seconds (3000ms)
                performVibration(context, 3000, false);
            }
        }

        // Automatically reschedule next alarms to keep cycle going forever
        AlarmScheduler.rescheduleAll(context);

        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
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
                        double cycle = time % 1.2;
                        double freq = (cycle < 0.6) ? 700 + 1100 * (cycle / 0.6) : 1800 - 1100 * ((cycle - 0.6) / 0.6);
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
}
