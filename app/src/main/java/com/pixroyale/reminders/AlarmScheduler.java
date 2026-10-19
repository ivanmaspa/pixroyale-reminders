package com.pixroyale.reminders;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.Calendar;
import java.util.TimeZone;

public class AlarmScheduler {

    private static final int[] AIRDROP_HOURS = {1, 3, 5, 7, 9, 11, 13, 15, 17, 20, 21, 23};

    public static void rescheduleAll(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        SharedPreferences prefs = context.getSharedPreferences("PixRoyaleConfig", Context.MODE_PRIVATE);
        boolean extraEnabled = prefs.getBoolean("extraModeEnabled", true);
        boolean normalEnabled = prefs.getBoolean("normalModeEnabled", true);
        int captchaLeadMin = prefs.getInt("captchaLeadTime", 3);
        int airdropLeadMin = prefs.getInt("airdropLeadTime", 5);

        Calendar nowMsk = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"));

        // 1. Schedule Captcha 19:00 MSK
        Calendar captchaCal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"));
        captchaCal.set(Calendar.HOUR_OF_DAY, 19);
        captchaCal.set(Calendar.MINUTE, 0);
        captchaCal.set(Calendar.SECOND, 0);
        captchaCal.set(Calendar.MILLISECOND, 0);

        if (nowMsk.after(captchaCal)) {
            captchaCal.add(Calendar.DAY_OF_YEAR, 1);
        }

        // 1.1 Captcha Weak Reminder (user-specified lead minutes: e.g. 3 min before = 18:57)
        if (normalEnabled) {
            Calendar cNormal = (Calendar) captchaCal.clone();
            cNormal.add(Calendar.MINUTE, -captchaLeadMin);
            if (cNormal.after(nowMsk)) {
                scheduleExactAlarm(context, am, 101, cNormal.getTimeInMillis(), "weak",
                        "Пиксель Рояль: Капча!", "Капча через " + captchaLeadMin + " мин! Окно сдачи: 19:00 - 19:03");
            }
        } else {
            cancelAlarm(context, am, 101);
        }

        // 1.2 Captcha Extra Alarm (1 min before = 18:59)
        if (extraEnabled) {
            Calendar cExtra = (Calendar) captchaCal.clone();
            cExtra.add(Calendar.MINUTE, -1);
            if (cExtra.after(nowMsk)) {
                scheduleExactAlarm(context, am, 102, cExtra.getTimeInMillis(), "extra",
                        "🚨 Пиксель Рояль: КАПЧА ЧЕРЕЗ 1 МИНУТУ!", "Заходи в бота прямо сейчас!");
            }
        } else {
            cancelAlarm(context, am, 102);
        }

        // 1.3 Captcha Audio Restore Alarm at exactly 19:00:00
        if (captchaCal.after(nowMsk)) {
            scheduleRestoreAudioAlarm(context, am, 103, captchaCal.getTimeInMillis());
        }

        // 2. Schedule Next Airdrop
        int nextAirdropHour = -1;
        Calendar airdropCal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"));
        airdropCal.set(Calendar.MINUTE, 0);
        airdropCal.set(Calendar.SECOND, 0);
        airdropCal.set(Calendar.MILLISECOND, 0);

        int curHour = nowMsk.get(Calendar.HOUR_OF_DAY);
        int curMin = nowMsk.get(Calendar.MINUTE);

        for (int h : AIRDROP_HOURS) {
            if (h > curHour || (h == curHour && curMin < 3)) {
                nextAirdropHour = h;
                break;
            }
        }

        if (nextAirdropHour != -1) {
            airdropCal.set(Calendar.HOUR_OF_DAY, nextAirdropHour);
        } else {
            nextAirdropHour = AIRDROP_HOURS[0];
            airdropCal.add(Calendar.DAY_OF_YEAR, 1);
            airdropCal.set(Calendar.HOUR_OF_DAY, nextAirdropHour);
        }

        // 2.1 Airdrop Weak Reminder (e.g. 5 min before)
        if (normalEnabled) {
            Calendar aNormal = (Calendar) airdropCal.clone();
            aNormal.add(Calendar.MINUTE, -airdropLeadMin);
            if (aNormal.after(nowMsk)) {
                scheduleExactAlarm(context, am, 201, aNormal.getTimeInMillis(), "weak",
                        "Пиксель Рояль: Аирдропы!", "Сброс сундуков в " + nextAirdropHour + ":00 МСК (через " + airdropLeadMin + " мин)");
            }
        } else {
            cancelAlarm(context, am, 201);
        }

        // 2.2 Airdrop Extra Alarm (1 min before)
        if (extraEnabled) {
            Calendar aExtra = (Calendar) airdropCal.clone();
            aExtra.add(Calendar.MINUTE, -1);
            if (aExtra.after(nowMsk)) {
                scheduleExactAlarm(context, am, 202, aExtra.getTimeInMillis(), "extra",
                        "🚨 Пиксель Рояль: АИРДРОП ЧЕРЕЗ 1 МИН!", "Сундуки на карте в " + nextAirdropHour + ":00 МСК!");
            }
        } else {
            cancelAlarm(context, am, 202);
        }

        // 2.3 Airdrop Audio Restore Alarm at exactly :00:00
        if (airdropCal.after(nowMsk)) {
            scheduleRestoreAudioAlarm(context, am, 203, airdropCal.getTimeInMillis());
        }
    }

    private static void cancelAlarm(Context context, AlarmManager am, int requestCode) {
        try {
            Intent intent = new Intent(context, ReminderBroadcastReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_NO_CREATE
            );
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        } catch (Exception ignored) {}
    }

    @SuppressLint("ScheduleExactAlarm")
    private static void scheduleRestoreAudioAlarm(Context context, AlarmManager am, int requestCode, long triggerAtMillis) {
        Intent intent = new Intent(context, ReminderBroadcastReceiver.class);
        intent.setAction(ReminderBroadcastReceiver.ACTION_RESTORE_AUDIO);

        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        }
    }

    @SuppressLint("ScheduleExactAlarm")
    private static void scheduleExactAlarm(Context context, AlarmManager am, int requestCode, long triggerAtMillis, String mode, String title, String text) {
        Intent intent = new Intent(context, ReminderBroadcastReceiver.class);
        intent.setAction(ReminderBroadcastReceiver.ACTION_FIRE_REMINDER);
        intent.putExtra(ReminderBroadcastReceiver.EXTRA_MODE, mode);
        intent.putExtra(ReminderBroadcastReceiver.EXTRA_TITLE, title);
        intent.putExtra(ReminderBroadcastReceiver.EXTRA_TEXT, text);

        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        }
    }
}
