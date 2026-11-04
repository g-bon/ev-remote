package it.gbon.evremote;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stato e comandi dell'auto, condivisi tra l'interfaccia e il controllo del limite di ricarica in background. */
final class Car {
    interface Listener {
        void onChange();
    }

    static final int DEFAULT_LIMIT = 80;
    static final int LIMIT_MIN = 15, LIMIT_MAX = 95;

    private static final Map<String, String> CODES = new HashMap<>();
    static {
        CODES.put("0", "Completato");
        CODES.put("1", "Completato");
        CODES.put("300", "L'auto non ha risposto in tempo");
        CODES.put("422", "Privacy attiva sull'auto");
        CODES.put("500", "Errore dell'auto o del server");
        CODES.put("502", "Comando duplicato");
        CODES.put("506", "Operatore telefonico non supportato");
        CODES.put("507", "Nessuna copertura GPS");
        CODES.put("timeout", "Nessuna risposta entro il tempo massimo");
        CODES.put("failed", "Fallito");
        CODES.put("not_compatible", "Comando non compatibile con l'auto");
    }

    private static Car instance;

    final Store s;
    final Api api;
    final Remote remote;
    final AppLog log;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    volatile JSONObject status;
    volatile int liveClima = -1;
    volatile long liveClimaAt;
    volatile Boolean liveLocked;
    volatile long liveLockedAt;
    volatile Boolean liveOn;
    volatile long liveOnAt;

    static synchronized Car get(Context c) {
        if (instance == null) instance = new Car(c.getApplicationContext());
        return instance;
    }

    private Car(Context c) {
        s = new Store(c);
        log = AppLog.get(c);
        Api.debug = log::debug;
        api = new Api(s);
        remote = new Remote(api, s);
        remote.debug = log::debug;
        remote.addListener(this::onEvent);
        try {
            String last = s.get("last_status");
            if (last != null) status = new JSONObject(last);
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void changed() {
        for (Listener l : listeners) l.onChange();
    }

    void notifyChanged() {
        changed();
    }

    // ---------------- Stato ----------------

    JSONObject refreshStatus(boolean verbose) throws Exception {
        long t0 = System.currentTimeMillis();
        JSONObject st = api.status(s.get("vehicle_id"));
        status = st;
        s.put("last_status", st.toString());
        String msg = String.format(Locale.ITALY, "Stato aggiornato (%.1f s)", (System.currentTimeMillis() - t0) / 1000.0);
        if (verbose) log.info(msg);
        else log.debug(msg);
        changed();
        return st;
    }

    private void onEvent(JSONObject e) {
        JSONObject p = e.optJSONObject("precond_state");
        if (p != null && p.has("status")) {
            int code = p.optInt("status", -1);
            if (code != liveClima) log.info("Evento dall'auto: clima " + climaLabel(code).toLowerCase(Locale.ITALY));
            liveClima = code;
            liveClimaAt = System.currentTimeMillis();
        }
        if (e.has("sev_state")) {
            int sev = e.optInt("sev_state", -1);
            Boolean on = sev == 1 || sev == 2 ? Boolean.TRUE : sev == 0 ? Boolean.FALSE : null;
            if (on != null) {
                liveOn = on;
                liveOnAt = System.currentTimeMillis();
            }
        }
        JSONObject d = e.optJSONObject("doors_state");
        if (d != null && d.has("doors_locking_state")) {
            int code = d.optInt("doors_locking_state", -1);
            Boolean locked = code == 1 || code == 7 ? Boolean.TRUE : code == 0 || code == 3 ? Boolean.FALSE : null;
            if (locked != null) {
                if (!locked.equals(liveLocked)) log.info("Evento dall'auto: " + (locked ? "auto chiusa" : "auto aperta"));
                liveLocked = locked;
                liveLockedAt = System.currentTimeMillis();
            }
        }
        changed();
    }

    static String climaLabel(int code) {
        switch (code) {
            case 0: return "Spento";
            case 1: return "Acceso";
            case 2: return "Terminato";
            default: return "codice " + code;
        }
    }

    /** Stato delle serrature più recente tra evento MQTT e REST: true chiusa, false aperta, null sconosciuto. */
    Boolean locked() {
        if (liveLocked != null && liveLockedAt > Status.lockedAt(status)) return liveLocked;
        return Status.locked(status);
    }

    /** Auto accesa: evento MQTT (sev_state) se più recente, altrimenti ignition.type del REST. null se sconosciuto. */
    Boolean isOn() {
        JSONObject ign = status == null ? null : status.optJSONObject("ignition");
        long restAt = ign == null ? 0 : Status.parseTime(ign.optString("createdAt", null));
        if (liveOn != null && liveOnAt > restAt) return liveOn;
        if (ign == null || !ign.has("type")) return null;
        return !"Stop".equals(ign.optString("type"));
    }

    long lockedSince() {
        return liveLocked != null && liveLockedAt > Status.lockedAt(status) ? liveLockedAt : Status.lockedAt(status);
    }

    // ---------------- Comandi ----------------

    private void progress(String code, long sec) {
        String what;
        switch (code) {
            case "connected": what = "connesso al server comandi"; break;
            case "900": what = "accettato dal server"; break;
            case "901": what = "l'auto dormiva, la sto svegliando"; break;
            case "902": what = "verifica dello stato"; break;
            case "903": what = "inoltrato all'auto"; break;
            default: what = "risposta " + code; break;
        }
        log.info("  +" + sec + " s  " + what);
    }

    /** Testo dell'esito. activate: comando di accensione del clima (il codice 4 significa auto aperta). */
    static String describe(String service, Remote.Result r, boolean activate) {
        if (r.ok()) return "Completato";
        int e = r.error();
        boolean precond = "/ThermalPrecond".equals(service);
        if (precond && activate && ("4".equals(r.code) || e == 4)) return "auto aperta: chiudila per usare il clima da remoto";
        if (e >= 0 && precond) {
            String[] m = {"auto accesa: spegnila per usare il clima da remoto", "già in corso", "problema di comunicazione",
                    "stop annullato: auto accesa", "il clima non era acceso"};
            if (e < m.length) return m[e] + " (errore " + e + ")";
        }
        if (e >= 0 && service.startsWith("/VehCharge") && !service.equals("/VehCharge/state")) {
            String[] m = {"sistema di ricarica in standby", "ricarica già in corso", "problema di comunicazione con la ricarica", "auto accesa"};
            if (e < m.length) return m[e] + " (errore " + e + ")";
        }
        String c = CODES.get(r.code);
        return (c != null ? c + " (codice " + r.code : "codice " + r.code) + (e >= 0 ? ", errore " + e : "") + (c != null ? ")" : "");
    }

    /** Nuovo tentativo solo per problemi di comunicazione: un motivo preciso dell'auto (resp_data.error) è definitivo, tranne il 2. */
    private static boolean retriable(Remote.Result r) {
        int e = r.error();
        if (e >= 0) return e == 2;
        return "timeout".equals(r.code) || "300".equals(r.code) || "500".equals(r.code) || "failed".equals(r.code);
    }

    void wake() throws Exception {
        if (Boolean.TRUE.equals(isOn())) {
            log.info("Sveglia non necessaria: l'auto risulta accesa");
            return;
        }
        if (!s.allow("wakeup", 6, 20 * 60 * 1000L)) {
            log.info("Sveglia saltata: limite di 6 ogni 20 minuti");
            return;
        }
        log.info("Sveglio l'auto…");
        Remote.Result r = remote.send("/VehCharge/state", new JSONObject().put("action", "state"), 90, this::progress);
        log.info((r.ok() ? "Auto sveglia" : "Sveglia: " + describe("/VehCharge/state", r, false)) + " (" + r.seconds + " s)");
    }

    /** Programmi del clima attuali, da reinviare con il comando (come fa l'app ufficiale). */
    private JSONObject programs() throws Exception {
        JSONObject out = new JSONObject();
        for (int i = 1; i <= 4; i++) {
            out.put("program" + i, new JSONObject().put("day", new JSONArray("[0,0,0,0,0,0,0]")).put("hour", 34).put("minute", 7).put("on", 0));
        }
        JSONObject ac = Status.aircon(status);
        JSONArray progs = ac == null ? null : ac.optJSONArray("programs");
        if (progs == null) return out;
        String[] days = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        for (int i = 0; i < progs.length(); i++) {
            JSONObject p = progs.optJSONObject(i);
            if (p == null) continue;
            JSONObject occ = p.optJSONObject("occurence");
            JSONArray day = occ == null ? null : occ.optJSONArray("day");
            int[] hm = Status.parsePt(p.optString("start", null));
            if (day == null || day.length() == 0 || hm == null) continue;
            JSONArray flags = new JSONArray();
            String list = day.toString();
            for (String d : days) flags.put(list.contains("\"" + d + "\"") ? 1 : 0);
            out.put("program" + p.optInt("slot"), new JSONObject().put("day", flags).put("hour", hm[0]).put("minute", hm[1])
                    .put("on", p.optBoolean("enabled") ? 1 : 0));
        }
        return out;
    }

    void climaOn() throws Exception {
        Double level = Status.level(status);
        if (level != null && level < 20 && !"InProgress".equals(Status.chargingStatus(status))) {
            log.info("Attenzione: batteria sotto il 20% e auto non in carica, l'auto potrebbe rifiutare il clima");
        }
        if (Boolean.TRUE.equals(isOn())) {
            log.info("Attenzione: l'auto risulta accesa. Il clima da remoto funziona solo ad auto spenta e chiusa");
        }
        if (Boolean.FALSE.equals(locked())) {
            log.info("Attenzione: l'auto risulta aperta (dato delle " + Status.clock(lockedSince())
                    + "). Il clima da remoto funziona solo con l'auto chiusa");
        }
        JSONObject params = new JSONObject().put("asap", "activate").put("programs", programs());
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (attempt == 1) wake();
            log.info("Accendo il clima" + (attempt > 1 ? " (tentativo " + attempt + " di 3)" : "") + "…");
            long sentAt = System.currentTimeMillis();
            Remote.Result r = remote.send("/ThermalPrecond", params, 150, this::progress);
            if (r.ok() || r.error() == 1) {
                log.info((r.ok() ? "L'auto ha accettato il comando (codice " + r.code + ", " + r.seconds + " s)"
                        : "L'auto dice che il clima è già in corso") + ". Attendo la conferma…");
                boolean confirmed = liveClima == 1 && liveClimaAt >= sentAt;
                if (!confirmed) {
                    confirmed = remote.awaitEvent(e -> {
                        JSONObject p = e.optJSONObject("precond_state");
                        return p != null && p.optInt("status", -1) == 1;
                    }, 90) != null;
                }
                log.info(confirmed ? "Confermato dall'auto: clima acceso"
                        : "Nessuna conferma dall'auto entro 90 s: controlla lo stato tra qualche minuto");
                return;
            }
            log.info("Non riuscito: " + describe("/ThermalPrecond", r, true) + " (" + r.seconds + " s)");
            if (!retriable(r)) return;
            if (attempt < 3) {
                log.info("Riprovo tra 20 secondi");
                Thread.sleep(20000);
            }
        }
        log.info("Clima non acceso dopo 3 tentativi");
    }

    void climaOff() throws Exception {
        JSONObject params = new JSONObject().put("asap", "deactivate").put("programs", programs());
        for (int attempt = 1; attempt <= 2; attempt++) {
            log.info("Spengo il clima…");
            Remote.Result r = remote.send("/ThermalPrecond", params, 150, this::progress);
            if (r.ok() || r.error() == 4) {
                log.info((r.ok() ? "Clima spento" : "Il clima era già spento") + " (" + r.seconds + " s)");
                return;
            }
            log.info("Non riuscito: " + describe("/ThermalPrecond", r, false) + " (" + r.seconds + " s)");
            if (!retriable(r)) return;
            wake();
        }
    }

    /** /VehCharge con l'orario della ricarica programmata: "immediate" avvia, "delayed" ferma. */
    Remote.Result charge(boolean start, boolean withWake) throws Exception {
        JSONObject chg = Status.charging(status);
        int[] hm = chg == null ? null : Status.parsePt(chg.optString("nextDelayedTime", null));
        if (hm == null) {
            log.info("Orario della ricarica programmata non disponibile: aggiorna lo stato e riprova");
            return null;
        }
        JSONObject params = new JSONObject()
                .put("program", new JSONObject().put("hour", hm[0]).put("minute", hm[1]))
                .put("type", start ? "immediate" : "delayed");
        if (withWake) wake();
        log.info(start ? "Avvio la ricarica…" : "Fermo la ricarica…");
        Remote.Result r = remote.send("/VehCharge", params, 150, this::progress);
        log.info((r.ok() ? (start ? "Ricarica avviata" : "Ricarica fermata") : "Non riuscito: " + describe("/VehCharge", r, false))
                + " (" + r.seconds + " s)");
        return r;
    }

    // ---------------- ABRP (come send_abrp_data di Home Assistant) ----------------

    /** Builds without an ABRP API key (forks, local builds) have ABRP disabled. */
    static boolean abrpAvailable() {
        return !BuildConfig.ABRP_API_KEY.isEmpty();
    }

    boolean abrpOn() {
        String t = s.get("abrp_token");
        return abrpAvailable() && s.getBool("abrp_on", false) && t != null && !t.trim().isEmpty();
    }

    /** Telemetria ABRP dall'ultimo stato. utc = ora del dato Stellantis, non l'ora attuale. */
    JSONObject abrpTelemetry() throws Exception {
        JSONObject st = status;
        JSONObject t = new JSONObject();
        if (st == null) return t;
        JSONObject el = Status.electric(st), chg = Status.charging(st);
        long at = Status.parseTime(el != null && el.has("updatedAt") ? el.optString("updatedAt") : st.optString("updatedAt", null));
        double[] pos = Status.position(st);
        if (at <= 0) at = System.currentTimeMillis();
        t.put("utc", at / 1000);
        if (el != null && el.has("level")) t.put("soc", el.optDouble("level"));
        if (el != null && el.has("autonomy")) t.put("est_battery_range", el.optDouble("autonomy"));
        boolean charging = chg != null && "InProgress".equals(chg.optString("status"));
        t.put("is_charging", charging);
        t.put("is_dcfc", charging && "Quick".equals(chg.optString("chargingMode")));
        JSONObject ign = st.optJSONObject("ignition");
        t.put("is_parked", ign != null && "Stop".equals(ign.optString("type")));
        // Posizione e velocità solo se aggiornate insieme al resto: alcune auto le lasciano ferme per mesi.
        long fresh = 30 * 60_000L;
        JSONObject kin = st.optJSONObject("kinetic");
        long kinAt = kin == null ? 0 : Status.parseTime(kin.optString("createdAt", null));
        if (kin != null && kin.has("speed") && at - kinAt < fresh) t.put("speed", kin.optDouble("speed"));
        if (pos != null && at - (long) pos[2] < fresh) {
            t.put("lat", pos[0]);
            t.put("lon", pos[1]);
            JSONObject lp = st.optJSONObject("lastPosition");
            JSONArray c = lp.getJSONObject("geometry").getJSONArray("coordinates");
            if (c.length() >= 3) t.put("elevation", c.optDouble(2));
            JSONObject props = lp.optJSONObject("properties");
            if (props != null && props.has("heading")) t.put("heading", props.optDouble("heading"));
        }
        JSONObject env = st.optJSONObject("environment");
        JSONObject air = env == null ? null : env.optJSONObject("air");
        if (air != null && air.has("temp")) t.put("ext_temp", air.optDouble("temp"));
        JSONObject odo = st.optJSONObject("odometer");
        if (odo != null && odo.has("mileage")) t.put("odometer", odo.optDouble("mileage"));
        JSONObject ext = el == null ? null : el.optJSONObject("extension");
        JSONObject elx = ext == null ? null : ext.optJSONObject("electric");
        JSONObject bat = elx == null ? null : elx.optJSONObject("battery");
        JSONObject health = bat == null ? null : bat.optJSONObject("health");
        if (health != null && health.has("capacity")) t.put("soh", health.optDouble("capacity"));
        else if (health != null && health.has("resistance")) t.put("soh", health.optDouble("resistance"));
        return t;
    }

    /** Invia l'ultimo stato ad ABRP se l'invio è attivo. Ritorna true se ABRP ha risposto ok. */
    boolean sendAbrp() {
        if (!abrpOn() || status == null) return false;
        try {
            JSONObject tlm = abrpTelemetry();
            if (!tlm.has("soc")) return false;
            String url = "https://api.iternio.com/1/tlm/send?api_key=" + java.net.URLEncoder.encode(BuildConfig.ABRP_API_KEY, "UTF-8")
                    + "&token=" + java.net.URLEncoder.encode(s.get("abrp_token").trim(), "UTF-8")
                    + "&tlm=" + java.net.URLEncoder.encode(tlm.toString(), "UTF-8");
            String resp = Api.http("POST", url, new HashMap<>(), "");
            boolean ok = "ok".equals(new JSONObject(resp).optString("status"));
            log.debug("ABRP <- " + resp + " per " + tlm);
            if (!ok) log.info("ABRP ha rifiutato i dati: " + resp);
            s.putLong("abrp_last", System.currentTimeMillis());
            s.putLong("abrp_last_data", tlm.optLong("utc") * 1000);
            s.put("abrp_last_result", ok ? "ok" : "errore");
            changed();
            return ok;
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            log.info("Invio ad ABRP non riuscito: " + (m.contains("Unauthorized Token") ? "token ABRP non valido, ricontrollalo" : m));
            s.putLong("abrp_last", System.currentTimeMillis());
            s.put("abrp_last_result", "errore");
            changed();
            return false;
        }
    }

    // ---------------- Limite di ricarica (come _auto_stop_charge_at_limit di Home Assistant) ----------------

    int limit() {
        return (int) s.getLong("charge_limit", DEFAULT_LIMIT);
    }

    boolean limitOn() {
        return s.getBool("charge_limit_on", true);
    }

    /** Ferma la ricarica una volta per sessione quando la batteria raggiunge il limite. Ritorna una descrizione dello stato. */
    synchronized String enforceLimit() throws Exception {
        JSONObject st = status;
        JSONObject chg = Status.charging(st);
        if (chg == null) return "stato di ricarica sconosciuto";
        if ("Partial".equals(chg.optString("type"))) return "limite 80% nativo dell'auto attivo";
        if (!"InProgress".equals(chg.optString("status"))) {
            s.putBool("limit_stop_sent", false);
            return "non in carica";
        }
        if (s.getBool("limit_stop_sent", false)) return "stop al limite già inviato";
        Double level = Status.level(st);
        if (!limitOn() || level == null) return "in carica";
        if ((int) Math.floor(level) < limit()) return "in carica, sotto il limite";
        if (Status.parsePt(chg.optString("nextDelayedTime", null)) == null) return "limite raggiunto ma orario di ricarica sconosciuto";
        log.info("Limite di ricarica " + limit() + "% raggiunto (batteria " + Math.round(level) + "%)");
        Remote.Result r = charge(false, false);
        if (r != null && r.ok()) {
            s.putBool("limit_stop_sent", true);
            return "ricarica fermata al limite";
        }
        log.info("Riprovo al prossimo controllo");
        return "stop al limite non riuscito";
    }
}
