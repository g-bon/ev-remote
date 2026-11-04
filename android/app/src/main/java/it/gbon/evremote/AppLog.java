package it.gbon.evremote;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Log dell'app: "attività" (mostrata a schermo) e log dettagliato (richieste, messaggi MQTT),
 * entrambi su file per sopravvivere ai riavvii e per l'esportazione.
 */
final class AppLog {
    interface Listener {
        void onChange();
    }

    private static final int MAX_FILE = 400_000;
    private static final int SHOWN = 60;
    private static AppLog instance;

    private final File info, debug;
    private final LinkedList<String> recent = new LinkedList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    static synchronized AppLog get(Context c) {
        if (instance == null) instance = new AppLog(c.getApplicationContext());
        return instance;
    }

    private AppLog(Context c) {
        info = new File(c.getFilesDir(), "attivita.log");
        debug = new File(c.getFilesDir(), "debug.log");
        String[] old = tail(info, 20_000).split("\n");
        for (int i = old.length - 1; i >= 0 && recent.size() < SHOWN; i--) if (!old[i].isEmpty()) recent.add(old[i]);
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static String now() {
        return new SimpleDateFormat("dd/MM HH:mm:ss", Locale.ITALY).format(new Date());
    }

    /** Riga per l'utente: a schermo, nel log attività e nel log dettagliato. */
    void info(String line) {
        String l = now() + "  " + line;
        synchronized (this) {
            recent.addFirst(l);
            while (recent.size() > SHOWN) recent.removeLast();
            append(info, l);
            append(debug, l);
        }
        for (Listener x : listeners) x.onChange();
    }

    /** Riga tecnica: solo nel log dettagliato. */
    synchronized void debug(String line) {
        append(debug, now() + "  [debug] " + line);
    }

    synchronized List<String> recent() {
        return new ArrayList<>(recent);
    }

    synchronized void clear() {
        recent.clear();
        info.delete();
        debug.delete();
        for (Listener x : listeners) x.onChange();
    }

    private static void append(File f, String line) {
        try {
            if (f.length() > MAX_FILE) {
                String keep = tail(f, MAX_FILE / 2);
                try (FileOutputStream o = new FileOutputStream(f, false)) {
                    o.write(keep.getBytes(StandardCharsets.UTF_8));
                }
            }
            try (FileOutputStream o = new FileOutputStream(f, true)) {
                o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
    }

    private static String tail(File f, int maxBytes) {
        if (!f.exists()) return "";
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long start = Math.max(0, r.length() - maxBytes);
            byte[] b = new byte[(int) (r.length() - start)];
            r.seek(start);
            r.readFully(b);
            String s = new String(b, StandardCharsets.UTF_8);
            int nl = start > 0 ? s.indexOf('\n') : -1;
            return nl >= 0 ? s.substring(nl + 1) : s;
        } catch (IOException e) {
            return "";
        }
    }

    /** Testo da condividere: versione, ultimo stato e log dettagliato, con VIN, customer id e token mascherati. */
    String export(Store s) {
        StringBuilder b = new StringBuilder();
        b.append("EV Remote ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")")
                .append(", Android ").append(Build.VERSION.RELEASE).append(", ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("\nEsportato il ").append(now())
                .append("\nLimite di ricarica: ").append(s.getLong("charge_limit", 80)).append("% ")
                .append(s.getBool("charge_limit_on", true) ? "(attivo)" : "(disattivato)").append("\n\n");
        b.append("== Ultimo stato (API REST) ==\n");
        try {
            String st = s.get("last_status");
            b.append(st == null ? "nessuno" : new JSONObject(st).toString(2));
        } catch (Exception e) {
            b.append("non leggibile");
        }
        b.append("\n\n== Log dettagliato ==\n").append(tail(debug, 150_000));
        return mask(b.toString(), s);
    }

    static String mask(String text, Store s) {
        String vin = s.get("vin");
        if (vin != null && vin.length() > 8) text = text.replace(vin, vin.substring(0, 3) + "…" + vin.substring(vin.length() - 4));
        String cid = s.get("customer_id");
        if (cid != null && !cid.isEmpty()) text = text.replace(cid, "<customer_id>");
        return text.replaceAll("\"(access_token|refresh_token|password)\"\\s*:\\s*\"[^\"]*\"", "\"$1\":\"<nascosto>\"");
    }
}
