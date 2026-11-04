package it.gbon.evremote;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Servizio breve: legge lo stato, applica il limite di ricarica, programma il controllo successivo. */
public class ChargeGuardService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ChargeGuard.createChannel(this);
        android.app.Notification n = ChargeGuard.notification(this, "EV Remote", "Controllo della ricarica…");
        if (Build.VERSION.SDK_INT >= 34) startForeground(ChargeGuard.NOTIF_RUNNING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(ChargeGuard.NOTIF_RUNNING, n);

        new Thread(() -> {
            Car car = Car.get(this);
            long delay = 15 * 60_000L;
            String summary;
            try {
                car.refreshStatus(false);
                car.sendAbrp();
                summary = car.enforceLimit();
                delay = ChargeGuard.nextDelayMs(car);
            } catch (Exception e) {
                summary = "controllo non riuscito";
                car.log.debug("Controllo ricarica non riuscito: " + e);
            }
            if (car.limitOn()) ChargeGuard.schedule(this, delay);
            car.log.debug("Controllo ricarica: " + summary + ", prossimo tra " + delay / 60_000 + " min");
            stopForeground(STOP_FOREGROUND_REMOVE);
            ChargeGuard.showSummary(this, car, summary, delay);
            stopSelf(startId);
        }).start();
        return START_NOT_STICKY;
    }
}
