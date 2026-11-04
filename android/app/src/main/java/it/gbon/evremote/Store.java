package it.gbon.evremote;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Token, dati del veicolo e stato OTP nelle SharedPreferences private, con i testi cifrati da {@link Vault}. */
final class Store {
    private final SharedPreferences p;

    Store(Context c) {
        p = c.getSharedPreferences("evremote", Context.MODE_PRIVATE);
    }

    String get(String k) {
        return Vault.decrypt(p.getString(k, null));
    }

    long getLong(String k) {
        return p.getLong(k, 0);
    }

    long getLong(String k, long def) {
        return p.getLong(k, def);
    }

    boolean getBool(String k, boolean def) {
        return p.getBoolean(k, def);
    }

    void putBool(String k, boolean v) {
        p.edit().putBoolean(k, v).commit();
    }

    void put(String k, String v) {
        p.edit().putString(k, Vault.encrypt(v)).commit();
    }

    void putLong(String k, long v) {
        p.edit().putLong(k, v).commit();
    }

    void clear() {
        p.edit().clear().commit();
    }

    boolean loggedIn() {
        return get("oauth_refresh") != null && get("vin") != null;
    }

    boolean remoteReady() {
        return get("otp") != null && get("customer_id") != null;
    }

    Otp otp() {
        String j = get("otp");
        if (j == null) return null;
        try {
            JSONObject o = new JSONObject(j);
            Map<String, String> m = new HashMap<>();
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                m.put(k, o.isNull(k) ? null : o.getString(k));
            }
            return Otp.fromMap(m);
        } catch (JSONException e) {
            return null;
        }
    }

    void saveOtp(Otp o) {
        put("otp", new JSONObject(o.toMap()).toString());
    }

    /** Limite a finestra mobile: true (e registra l'uso) se in windowMs ci sono meno di max usi. */
    synchronized boolean allow(String key, int max, long windowMs) {
        long now = System.currentTimeMillis();
        List<String> kept = new ArrayList<>();
        String old = get("rate_" + key);
        if (old != null) {
            for (String t : old.split(",")) {
                if (!t.isEmpty() && now - Long.parseLong(t) < windowMs) kept.add(t);
            }
        }
        if (kept.size() >= max) return false;
        kept.add(Long.toString(now));
        put("rate_" + key, String.join(",", kept));
        return true;
    }
}
