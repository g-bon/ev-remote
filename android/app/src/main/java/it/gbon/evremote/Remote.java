package it.gbon.evremote;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

/** Comandi remoti via il broker MQTT Stellantis (mwa.mpsa.com:8885). */
final class Remote implements MqttCallback {
    static final String SERVER = "ssl://mwa.mpsa.com:8885";
    static final String REQ_TOPIC = "psa/RemoteServices/from/cid/";
    static final String RESP_TOPIC = "psa/RemoteServices/to/cid/";
    static final String EVENT_TOPIC = "psa/RemoteServices/events/MPHRTServices/";
    static final List<String> IN_PROGRESS = Arrays.asList("900", "901", "902", "903");

    /** Evento dell'auto: istantanea completa dello stato (precond_state, charging_state, ...). */
    interface EventListener {
        void onEvent(JSONObject payload);
    }

    interface Progress {
        void on(String code, long seconds);
    }

    static final class Result {
        final String code;
        final JSONObject respData;
        long seconds;

        Result(String code, JSONObject respData) {
            this.code = code;
            this.respData = respData;
        }

        boolean ok() {
            return "0".equals(code) || "1".equals(code);
        }

        int error() {
            return respData == null ? -1 : respData.optInt("error", -1);
        }
    }

    private final Api api;
    private final Store s;
    private final Map<String, BlockingQueue<JSONObject>> waiting = new ConcurrentHashMap<>();
    private final List<EventListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private MqttClient client;
    private String connectedToken;
    java.util.function.Consumer<String> debug = x -> {};

    Remote(Api api, Store s) {
        this.api = api;
        this.s = s;
    }

    private synchronized void connect(boolean forceToken) throws Exception {
        String token = api.mqttAccessToken(forceToken);
        if (client != null && client.isConnected() && token.equals(connectedToken)) return;
        disconnect();
        // Il broker Stellantis rifiuta gli id client arbitrari: id vuoto (assegnato dal broker), come paho-mqtt Python.
        MqttClient c = new MqttClient(SERVER, "", new MemoryPersistence());
        MqttConnectOptions o = new MqttConnectOptions();
        o.setUserName("IMA_OAUTH_ACCESS_TOKEN");
        o.setPassword(token.toCharArray());
        o.setCleanSession(true);
        o.setKeepAliveInterval(120);
        o.setConnectionTimeout(30);
        o.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        o.setSocketFactory(SSLSocketFactory.getDefault());
        c.setCallback(this);
        c.connect(o);
        c.subscribe(RESP_TOPIC + s.get("customer_id") + "/#", 0);
        c.subscribe(EVENT_TOPIC + s.get("vin"), 0);
        client = c;
        connectedToken = token;
        debug.accept("MQTT connesso a " + SERVER);
    }

    void addListener(EventListener l) {
        listeners.add(l);
    }

    void removeListener(EventListener l) {
        listeners.remove(l);
    }

    /** Connessione (e iscrizione agli eventi) senza inviare comandi. */
    void ensureConnected() throws Exception {
        connect(false);
    }

    /** Attende un evento dell'auto che soddisfi la condizione; null se non arriva entro timeoutSec. */
    JSONObject awaitEvent(java.util.function.Predicate<JSONObject> condition, int timeoutSec) throws InterruptedException {
        BlockingQueue<JSONObject> q = new LinkedBlockingQueue<>();
        EventListener l = e -> { if (condition.test(e)) q.add(e); };
        listeners.add(l);
        try {
            return q.poll(timeoutSec, TimeUnit.SECONDS);
        } finally {
            listeners.remove(l);
        }
    }

    synchronized void disconnect() {
        if (client == null) return;
        try {
            client.disconnectForcibly(2000);
            client.close();
        } catch (Exception ignored) {
        }
        client = null;
    }

    /** Pubblica il comando e attende l'esito finale (o "timeout"). Su 400 rinnova il token e riprova una volta. */
    Result send(String service, JSONObject params, int timeoutSec, Progress progress) throws Exception {
        long start = System.currentTimeMillis();
        Result r = sendOnce(service, params, timeoutSec, progress, start);
        r.seconds = (System.currentTimeMillis() - start) / 1000;
        return r;
    }

    private Result sendOnce(String service, JSONObject params, int timeoutSec, Progress progress, long start) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            connect(attempt > 0);
            if (progress != null) progress.on("connected", (System.currentTimeMillis() - start) / 1000);
            SimpleDateFormat ts = new SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.ROOT);
            String id = UUID.randomUUID().toString().replace("-", "") + ts.format(new Date());
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            JSONObject msg = new JSONObject()
                    .put("access_token", s.get("mqtt_access"))
                    .put("customer_id", s.get("customer_id"))
                    .put("correlation_id", id)
                    .put("req_date", iso.format(new Date()))
                    .put("vin", s.get("vin"))
                    .put("req_parameters", params);
            BlockingQueue<JSONObject> q = new LinkedBlockingQueue<>();
            waiting.put(id, q);
            boolean retry = false;
            try {
                MqttMessage m = new MqttMessage(msg.toString().getBytes(StandardCharsets.UTF_8));
                m.setQos(0);
                synchronized (this) {
                    client.publish(REQ_TOPIC + s.get("customer_id") + service, m);
                }
                debug.accept("MQTT -> " + service + " " + new JSONObject(msg.toString()).put("access_token", "<nascosto>"));
                long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
                while (true) {
                    long left = deadline - System.currentTimeMillis();
                    JSONObject r = left > 0 ? q.poll(left, TimeUnit.MILLISECONDS) : null;
                    if (r == null) return new Result("timeout", null);
                    String code = r.has("return_code") ? r.optString("return_code") : r.optString("process_code", null);
                    if (code == null || code.isEmpty()) continue;
                    if (progress != null) progress.on(code, (System.currentTimeMillis() - start) / 1000);
                    if (IN_PROGRESS.contains(code)) continue;
                    if ("400".equals(code)) {
                        if ("[authorization.denied.cvs.response.no.matching.service.key]".equals(r.optString("reason"))) {
                            return new Result("not_compatible", null);
                        }
                        retry = true;
                        break;
                    }
                    if ("113".equals(code)) return new Result("failed", null);
                    return new Result(code, r.optJSONObject("resp_data"));
                }
            } finally {
                waiting.remove(id);
            }
            if (!retry) break;
        }
        return new Result("failed", null);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            debug.accept("MQTT <- " + topic.substring(topic.lastIndexOf('/') + 1) + " " + payload);
            JSONObject d = new JSONObject(payload);
            if (topic.startsWith(EVENT_TOPIC)) {
                for (EventListener l : listeners) l.onEvent(d);
                return;
            }
            BlockingQueue<JSONObject> q = waiting.get(d.optString("correlation_id"));
            if (q != null) q.add(d);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        debug.accept("MQTT connessione persa: " + cause);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }
}
