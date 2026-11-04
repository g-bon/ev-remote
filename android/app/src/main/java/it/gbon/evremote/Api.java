package it.gbon.evremote;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** API REST Stellantis (OAuth dell'app del marchio, veicoli, stato, token MQTT). */
final class Api {
    static final String API = "https://api.groupe-psa.com";
    static final long MQTT_REFRESH_TTL_MS = 3L * 24 * 3600 * 1000;

    static final class HttpError extends IOException {
        final int code;
        final String body;

        HttpError(int code, String body) {
            super("HTTP " + code + ": " + (body.length() > 300 ? body.substring(0, 300) : body));
            this.code = code;
            this.body = body;
        }
    }

    static final class NeedsSetup extends IOException {
        NeedsSetup(String m) { super(m); }
    }

    static java.util.function.Consumer<String> debug = x -> {};

    private final Store s;

    Api(Store s) {
        this.s = s;
    }

    private static String enc(String v) {
        try {
            return URLEncoder.encode(v, "UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String redirectUri() {
        return BuildConfig.SCHEME + "://oauth2redirect/" + BuildConfig.CULTURE;
    }

    static String authorizeUrl() {
        return BuildConfig.OAUTH_URL + "/am/oauth2/authorize?client_id=" + BuildConfig.CLIENT_ID
                + "&response_type=code&redirect_uri=" + enc(redirectUri())
                + "&scope=openid%20profile%20email&locale=" + BuildConfig.LOCALE;
    }

    private static String clientQuery() {
        return "?client_id=" + BuildConfig.CLIENT_ID + "&locale=" + BuildConfig.LOCALE;
    }

    static String http(String method, String url, Map<String, String> headers, String body) throws IOException {
        long t0 = System.currentTimeMillis();
        String shown = method + " " + url.split("\\?")[0];
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        try {
            if (body != null || "POST".equals(method)) {
                c.setDoOutput(true);
                try (OutputStream o = c.getOutputStream()) {
                    o.write((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            String text;
            try (InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
                text = Otp.readAll(in);
            }
            debug.accept("HTTP " + shown + " -> " + code + " (" + (System.currentTimeMillis() - t0) + " ms)"
                    + (code / 100 != 2 ? " " + (text.length() > 500 ? text.substring(0, 500) : text) : ""));
            if (code / 100 != 2) throw new HttpError(code, text);
            return text;
        } catch (HttpError e) {
            throw e;
        } catch (IOException e) {
            debug.accept("HTTP " + shown + " errore dopo " + (System.currentTimeMillis() - t0) + " ms: " + e);
            throw e;
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject obj(String text) throws IOException {
        try {
            return text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (JSONException e) {
            throw new IOException("Risposta non valida: " + e.getMessage());
        }
    }

    // ---- OAuth ----

    private Map<String, String> tokenHeaders() {
        String basic = Base64.encodeToString((BuildConfig.CLIENT_ID + ":" + BuildConfig.CLIENT_SECRET)
                .getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("Authorization", "Basic " + basic);
        return h;
    }

    private void saveOauth(JSONObject j) throws JSONException {
        s.put("oauth_access", j.getString("access_token"));
        s.put("oauth_refresh", j.getString("refresh_token"));
        s.putLong("oauth_expires", System.currentTimeMillis() + j.getLong("expires_in") * 1000);
    }

    void exchangeCode(String code) throws IOException, JSONException {
        String url = BuildConfig.OAUTH_URL + "/am/oauth2/access_token?redirect_uri=" + enc(redirectUri())
                + "&grant_type=authorization_code&code=" + enc(code);
        saveOauth(obj(http("POST", url, tokenHeaders(), null)));
    }

    synchronized void refreshOauth() throws IOException, JSONException {
        String refresh = s.get("oauth_refresh");
        if (refresh == null) throw new NeedsSetup("Accesso " + BuildConfig.BRAND + " richiesto");
        String url = BuildConfig.OAUTH_URL + "/am/oauth2/access_token?grant_type=refresh_token&refresh_token=" + enc(refresh);
        try {
            saveOauth(obj(http("POST", url, tokenHeaders(), null)));
        } catch (HttpError e) {
            if (e.code == 400 || e.code == 401) throw new NeedsSetup("Accesso " + BuildConfig.BRAND + " scaduto: rifai il login");
            throw e;
        }
    }

    synchronized String accessToken() throws IOException, JSONException {
        if (s.get("oauth_access") == null || s.getLong("oauth_expires") - 120_000 < System.currentTimeMillis()) refreshOauth();
        return s.get("oauth_access");
    }

    private Map<String, String> otpHeaders() throws IOException, JSONException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + accessToken());
        h.put("User-Agent", "okhttp/4.8.0");
        h.put("Accept", "application/hal+json");
        h.put("x-introspect-realm", BuildConfig.REALM);
        return h;
    }

    private Map<String, String> carHeaders() throws IOException, JSONException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + accessToken());
        h.put("x-introspect-realm", BuildConfig.REALM);
        return h;
    }

    /** GET/POST verso le API con un nuovo tentativo dopo il refresh del token OAuth su 401. */
    private String call(String method, String url, boolean otpStyle, String body, String contentType) throws IOException, JSONException {
        for (int attempt = 0; ; attempt++) {
            Map<String, String> h = otpStyle ? otpHeaders() : carHeaders();
            if (contentType != null) h.put("Content-Type", contentType);
            try {
                return http(method, url, h, body);
            } catch (HttpError e) {
                if (e.code != 401 || attempt > 0) throw e;
                refreshOauth();
            }
        }
    }

    // ---- Account e veicolo ----

    String customerId() throws IOException, JSONException {
        Map<String, String> h = otpHeaders();
        h.put("x-transaction-id", "1234");
        JSONArray a = new JSONArray(http("GET", API + "/applications/cvs/v4/mauv/car-associations" + clientQuery(), h, null));
        if (a.length() == 0 || !a.getJSONObject(0).has("customer")) throw new IOException("Dati utente mancanti");
        return a.getJSONObject(0).getString("customer");
    }

    void requestSms() throws IOException, JSONException {
        call("POST", API + "/applications/cvs/v4/mobile/smsCode" + clientQuery(), true, null, null);
    }

    JSONObject vehicles() throws IOException, JSONException {
        return obj(call("GET", API + "/connectedcar/v4/user/vehicles" + clientQuery(), false, null, null));
    }

    JSONObject status(String vehicleId) throws IOException, JSONException {
        return obj(call("GET", API + "/connectedcar/v4/user/vehicles/" + vehicleId + "/status" + clientQuery(), false, null, null));
    }

    /** Salva il primo veicolo dell'account e il customer id per MQTT. */
    void loadAccount() throws IOException, JSONException {
        JSONObject embedded = vehicles().optJSONObject("_embedded");
        JSONArray vs = embedded == null ? null : embedded.optJSONArray("vehicles");
        if (vs == null || vs.length() == 0) throw new IOException("Nessun veicolo sull'account");
        JSONObject v = vs.getJSONObject(0);
        s.put("vin", v.getString("vin"));
        s.put("vehicle_id", v.getString("id"));
        if (!v.optString("brand").isEmpty()) s.put("brand", v.optString("brand"));
        JSONArray pics = v.optJSONArray("pictures");
        if (pics != null && pics.length() > 0) s.put("picture_url", pics.getString(0));
        s.put("customer_id", customerId());
    }

    // ---- Token MQTT ----

    private JSONObject mqttTokenRequest(JSONObject body) throws IOException, JSONException {
        return obj(call("POST", API + "/connectedcar/v4/virtualkey/remoteaccess/token" + clientQuery(), true,
                body.toString(), "application/json"));
    }

    private void saveMqtt(JSONObject j) throws JSONException {
        s.put("mqtt_access", j.getString("access_token"));
        s.putLong("mqtt_expires", System.currentTimeMillis() + j.getLong("expires_in") * 1000);
        if (j.has("refresh_token")) {
            s.put("mqtt_refresh", j.getString("refresh_token"));
            s.putLong("mqtt_refresh_expires", System.currentTimeMillis() + MQTT_REFRESH_TTL_MS);
        }
    }

    /** Token MQTT valido: refresh token finché vale, altrimenti un nuovo OTP (max 6 al giorno). */
    synchronized String mqttAccessToken(boolean force) throws Exception {
        if (!force && s.get("mqtt_access") != null && s.getLong("mqtt_expires") - 180_000 > System.currentTimeMillis()) {
            return s.get("mqtt_access");
        }
        if (s.get("mqtt_refresh") != null && s.getLong("mqtt_refresh_expires") > System.currentTimeMillis()) {
            try {
                JSONObject body = new JSONObject().put("grant_type", "refresh_token").put("refresh_token", s.get("mqtt_refresh"));
                JSONObject j = mqttTokenRequest(body);
                if (j.has("access_token")) {
                    saveMqtt(j);
                    return s.get("mqtt_access");
                }
            } catch (HttpError e) {
                // refresh token rifiutato: si passa all'OTP
            }
        }
        return mqttTokenWithOtp();
    }

    synchronized String mqttTokenWithOtp() throws Exception {
        Otp otp = s.otp();
        if (otp == null) throw new NeedsSetup("Comandi remoti non attivati");
        if (!s.allow("otp", 6, 24L * 3600 * 1000)) throw new IOException("Limite di 6 codici OTP al giorno raggiunto, riprova domani");
        String code = otp.nextCode();
        s.saveOtp(otp);
        JSONObject j = mqttTokenRequest(new JSONObject().put("grant_type", "password").put("password", code));
        if (!j.has("access_token")) throw new IOException("Token MQTT non ricevuto");
        saveMqtt(j);
        return s.get("mqtt_access");
    }
}
