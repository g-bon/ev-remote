package it.gbon.evremote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lettura del JSON di stato REST (/vehicles/{id}/status), con gli stessi percorsi dell'integrazione HA. */
final class Status {
    private Status() {}

    static JSONObject electric(JSONObject st) {
        JSONArray en = st == null ? null : st.optJSONArray("energies");
        if (en == null) return null;
        for (int i = 0; i < en.length(); i++) {
            JSONObject e = en.optJSONObject(i);
            if (e != null && "Electric".equals(e.optString("type"))) return e;
        }
        return null;
    }

    static JSONObject charging(JSONObject st) {
        JSONObject el = electric(st);
        JSONObject ext = el == null ? null : el.optJSONObject("extension");
        JSONObject e = ext == null ? null : ext.optJSONObject("electric");
        return e == null ? null : e.optJSONObject("charging");
    }

    static JSONObject aircon(JSONObject st) {
        if (st == null) return null;
        JSONObject p = st.optJSONObject("preconditioning");
        if (p == null) p = st.optJSONObject("preconditionning");
        return p == null ? null : p.optJSONObject("airConditioning");
    }

    static Double level(JSONObject st) {
        JSONObject el = electric(st);
        return el != null && el.has("level") ? el.optDouble("level") : null;
    }

    static String chargingStatus(JSONObject st) {
        JSONObject c = charging(st);
        return c == null ? "" : c.optString("status", "");
    }

    /** true chiusa, false aperta, null sconosciuto (doorsState.lockedStates). */
    static Boolean locked(JSONObject st) {
        JSONObject d = st == null ? null : st.optJSONObject("doorsState");
        JSONArray ls = d == null ? null : d.optJSONArray("lockedStates");
        if (ls == null || ls.length() == 0) return null;
        String all = ls.toString();
        if (all.contains("\"Unlocked\"")) return false;
        if (all.contains("Locked")) return true;
        return null;
    }

    static long lockedAt(JSONObject st) {
        JSONObject d = st == null ? null : st.optJSONObject("doorsState");
        long t = d == null ? 0 : parseTime(d.optString("createdAt", null));
        return t > 0 ? t : parseTime(st == null ? null : st.optString("updatedAt", null));
    }

    static List<String> openDoors(JSONObject st) {
        List<String> out = new ArrayList<>();
        JSONObject d = st == null ? null : st.optJSONObject("doorsState");
        JSONArray op = d == null ? null : d.optJSONArray("opening");
        if (op == null) return out;
        for (int i = 0; i < op.length(); i++) {
            JSONObject o = op.optJSONObject(i);
            if (o == null || !"Open".equals(o.optString("state"))) continue;
            switch (o.optString("identifier")) {
                case "Driver": out.add("conducente"); break;
                case "Passenger": out.add("passeggero"); break;
                case "RearLeft": out.add("posteriore sx"); break;
                case "RearRight": out.add("posteriore dx"); break;
                case "Trunk": out.add("bagagliaio"); break;
                default: out.add(o.optString("identifier")); break;
            }
        }
        return out;
    }

    /** {lat, lon, epochMillis} da lastPosition (GeoJSON: coordinates = [lon, lat, alt]), o null. */
    static double[] position(JSONObject st) {
        JSONObject p = st == null ? null : st.optJSONObject("lastPosition");
        JSONObject g = p == null ? null : p.optJSONObject("geometry");
        JSONArray c = g == null ? null : g.optJSONArray("coordinates");
        if (c == null || c.length() < 2) return null;
        JSONObject props = p.optJSONObject("properties");
        long t = props == null ? 0 : parseTime(props.optString("updatedAt", props.optString("createdAt", null)));
        return new double[]{c.optDouble(1), c.optDouble(0), t};
    }

    static int[] parsePt(String pt) {
        if (pt == null) return null;
        Matcher m = Pattern.compile("^PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?$").matcher(pt);
        if (!m.matches()) return null;
        return new int[]{m.group(1) == null ? 0 : Integer.parseInt(m.group(1)), m.group(2) == null ? 0 : Integer.parseInt(m.group(2))};
    }

    static long parseTime(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (Exception e) {
            try {
                return java.time.LocalDateTime.parse(iso).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    static String clock(long millis) {
        ZonedDateTime t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
        boolean today = t.toLocalDate().equals(LocalDate.now());
        return t.format(DateTimeFormatter.ofPattern(today ? "HH:mm" : "dd/MM HH:mm"));
    }
}
