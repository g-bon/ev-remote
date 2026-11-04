package it.gbon.evremote;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

/**
 * Controlli periodici del limite di ricarica ad app chiusa, con allarmi esatti (consentiti anche in Doze,
 * al massimo uno ogni ~9 minuti): l'allarme avvia {@link ChargeGuardService}, che controlla e riprogramma.
 */
final class ChargeGuard {
    static final String ACTION_CHECK = "it.gbon.evremote.CHECK_CHARGE";
    static final String CHANNEL = "ricarica";
    static final int NOTIF_RUNNING = 1, NOTIF_SUMMARY = 2;
    private static final long MIN = 60_000L;

    private ChargeGuard() {}

    /** Prossimo controllo: vicino al limite più spesso (stima dalla velocità di ricarica), a riposo ogni 30-60 minuti. */
    static long nextDelayMs(Car car) {
        JSONObject st = car.status;
        JSONObject chg = Status.charging(st);
        if (chg == null) return 30 * MIN;
        if (!"InProgress".equals(chg.optString("status"))) return chg.optBoolean("plugged") ? 30 * MIN : 60 * MIN;
        if (car.s.getBool("limit_stop_sent", false)) return 30 * MIN;
        Double level = Status.level(st);
        if (level == null) return 15 * MIN;
        if (level >= car.limit()) return 10 * MIN;
        JSONObject el = Status.electric(st);
        double autonomy = el == null ? 0 : el.optDouble("autonomy", 0);
        double rate = chg.optDouble("chargingRate", 0);
        if (rate <= 0 || autonomy <= 0 || level <= 0) return 15 * MIN;
        double pctPerMin = rate / (autonomy / level) / 60.0;
        double minutes = (car.limit() - level) / pctPerMin;
        return (long) (Math.max(10, Math.min(30, minutes * 0.7)) * MIN);
    }

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, ChargeGuardReceiver.class).setAction(ACTION_CHECK);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void schedule(Context c, long delayMs) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        long at = System.currentTimeMillis() + delayMs;
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c));
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c));
        }
        Car.get(c).s.putLong("guard_next", at);
    }

    static void cancel(Context c) {
        ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(alarmIntent(c));
        Car.get(c).s.putLong("guard_next", 0);
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_SUMMARY);
    }

    /** Attiva i controlli se il limite è acceso e i comandi remoti sono pronti; force riprogramma comunque (riavvio, aggiornamento). */
    static void ensureScheduled(Context c, boolean force) {
        Car car = Car.get(c);
        if (!car.limitOn() || !car.s.loggedIn() || !car.s.remoteReady()) {
            cancel(c);
            return;
        }
        if (force || car.s.getLong("guard_next") < System.currentTimeMillis()) schedule(c, 2 * MIN);
    }

    static void createChannel(Context c) {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Limite di ricarica", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Controllo della ricarica e stop al limite");
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    static Notification notification(Context c, String title, String text) {
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .build();
    }

    /** Notifica di riepilogo, visibile solo durante una ricarica o subito dopo uno stop al limite. */
    static void showSummary(Context c, Car car, String summary, long delayMs) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        boolean charging = "InProgress".equals(Status.chargingStatus(car.status));
        if (!charging && !summary.contains("fermata")) {
            nm.cancel(NOTIF_SUMMARY);
            return;
        }
        Double level = Status.level(car.status);
        String title = "Ricarica " + (level == null ? "" : Math.round(level) + "% ") + "· limite " + car.limit() + "%";
        String text = summary + " · prossimo controllo " + Status.clock(System.currentTimeMillis() + delayMs);
        nm.notify(NOTIF_SUMMARY, notification(c, title, text));
    }
}
