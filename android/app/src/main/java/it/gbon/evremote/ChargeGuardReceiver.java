package it.gbon.evremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Allarme del controllo ricarica, riavvio del telefono e aggiornamento dell'app. */
public class ChargeGuardReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ChargeGuard.ACTION_CHECK.equals(intent.getAction())) {
            ChargeGuard.ensureScheduled(context, true);
            return;
        }
        try {
            context.startForegroundService(new Intent(context, ChargeGuardService.class));
        } catch (Exception e) {
            AppLog.get(context).debug("Controllo ricarica non avviato: " + e);
            ChargeGuard.schedule(context, 15 * 60_000L);
        }
    }
}
