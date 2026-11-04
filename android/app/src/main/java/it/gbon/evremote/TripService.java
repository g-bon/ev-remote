package it.gbon.evremote;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

/** Modalità viaggio: ogni minuto legge lo stato e lo invia ad ABRP; si ferma da sola dopo 20 minuti di sosta. */
public class TripService extends Service {
    static final String ACTION_STOP = "it.gbon.evremote.TRIP_STOP";
    static final int NOTIF = 3;
    private static final long INTERVAL = 60_000L, PARKED_STOP = 20 * 60_000L, MAX = 12 * 3600_000L;

    static volatile boolean running;
    private volatile Thread worker;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, TripService.class));
    }

    static void stop(Context c) {
        c.startService(new Intent(c, TripService.class).setAction(ACTION_STOP));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification(String text) {
        PendingIntent stopPi = PendingIntent.getService(this, 1, new Intent(this, TripService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, ChargeGuard.CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Modalità viaggio ABRP")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Ferma", stopPi).build())
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Thread w = worker;
            if (w != null) w.interrupt();
            else stopSelf();
            return START_NOT_STICKY;
        }
        ChargeGuard.createChannel(this);
        Notification n = notification("Avvio…");
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF, n);
        if (worker != null) return START_NOT_STICKY;

        running = true;
        Car car = Car.get(this);
        car.log.info("Modalità viaggio ABRP avviata: invio ogni minuto");
        PowerManager.WakeLock wl = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "corsa:trip");
        wl.acquire(MAX);
        worker = new Thread(() -> {
            long started = System.currentTimeMillis(), parkedSince = 0;
            int sent = 0;
            String reason = "fermata";
            try {
                while (System.currentTimeMillis() - started < MAX) {
                    try {
                        car.refreshStatus(false);
                        if (car.sendAbrp()) sent++;
                        JSONObject t = car.abrpTelemetry();
                        boolean parked = t.optBoolean("is_parked") && !t.optBoolean("is_charging");
                        long now = System.currentTimeMillis();
                        if (!parked) parkedSince = 0;
                        else if (parkedSince == 0) parkedSince = now;
                        else if (now - parkedSince > PARKED_STOP) {
                            reason = "terminata: auto parcheggiata da 20 minuti";
                            break;
                        }
                        String text = Math.round(t.optDouble("soc")) + "% · " + Math.round(t.optDouble("est_battery_range")) + " km · "
                                + ("ok".equals(car.s.get("abrp_last_result")) ? "inviato alle " : "errore alle ") + Status.clock(now);
                        ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF, notification(text));
                    } catch (Exception e) {
                        car.log.debug("Modalità viaggio: " + e);
                    }
                    Thread.sleep(INTERVAL);
                }
                if (System.currentTimeMillis() - started >= MAX) reason = "terminata dopo 12 ore";
            } catch (InterruptedException ignored) {
            } finally {
                car.log.info("Modalità viaggio " + reason + " (" + sent + " invii ad ABRP)");
                running = false;
                worker = null;
                if (wl.isHeld()) wl.release();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                car.notifyChanged();
            }
        });
        worker.start();
        car.notifyChanged();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Thread w = worker;
        if (w != null) w.interrupt();
        super.onDestroy();
    }
}
