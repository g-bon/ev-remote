package it.gbon.evremote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_LOGIN = 1;
    private static final int ACCENT = Color.parseColor("#2BB5A0");
    private static final int DARK = Color.parseColor("#1E1E1E");
    private static final int BG = Color.parseColor("#F2F2F2");
    private static final int GREY = Color.parseColor("#777777");
    private static final int LIGHT = Color.parseColor("#E0E0E0");

    private interface Task {
        void run() throws Exception;
    }

    private Car car;
    private Store s;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean busy;

    private TextView subtitle, logView, limitValue, position, abrpStatus;
    private Button tripBtn;
    private EditText abrpToken;
    private Switch abrpSwitch;
    private ImageView car_image;
    private LinearLayout setupBox;
    private Button loginBtn, activateBtn, refreshBtn;
    private Switch limitSwitch;
    private SeekBar limitBar;
    private final List<Button> commandButtons = new ArrayList<>();
    private final Map<String, TextView> values = new LinkedHashMap<>();

    private final Car.Listener carListener = () -> ui.post(this::renderStatus);
    private final AppLog.Listener logListener = () -> ui.post(this::renderLog);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        car = Car.get(this);
        s = car.s;
        buildUi();
        render();
        renderLog();
        showPicture();
        car.addListener(carListener);
        car.log.addListener(logListener);
        if (s.loggedIn()) run(this::refreshAndEnforce);
    }

    @Override
    protected void onResume() {
        super.onResume();
        connectLive();
        ChargeGuard.ensureScheduled(this, false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        car.removeListener(carListener);
        car.log.removeListener(logListener);
        bg.shutdownNow();
    }

    /** Connessione MQTT in sottofondo per ricevere gli eventi dell'auto in tempo reale. */
    private void connectLive() {
        if (!s.loggedIn() || !s.remoteReady()) return;
        new Thread(() -> {
            try {
                car.remote.ensureConnected();
            } catch (Exception e) {
                car.log.info("Eventi in tempo reale non disponibili: " + friendly(e));
            }
        }).start();
    }

    private void refreshAndEnforce() throws Exception {
        car.refreshStatus(true);
        car.sendAbrp();
        if (s.remoteReady()) {
            String r = car.enforceLimit();
            car.log.debug("Limite di ricarica: " + r);
        }
    }

    // ---------------- UI ----------------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(Color.WHITE, 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        parent.addView(c, lp);
        return c;
    }

    private TextView text(String t, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private Button button(String label, int bg, int fg, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(fg);
        b.setBackground(rounded(bg, 14));
        b.setOnClickListener(l);
        b.setMinHeight(dp(52));
        return b;
    }

    private LinearLayout row(LinearLayout parent, int topMargin) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        parent.addView(r, lp);
        return r;
    }

    private void addWeighted(LinearLayout r, View v, boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (gap) lp.leftMargin = dp(10);
        r.addView(v, lp);
    }

    private TextView addValue(LinearLayout r, String key, String label, boolean gap) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.addView(text(label, 13, GREY, false));
        TextView v = text("–", 18, DARK, true);
        cell.addView(v);
        values.put(key, v);
        addWeighted(r, cell, gap);
        return v;
    }

    private void command(LinearLayout r, String label, int bg, int fg, boolean gap, View.OnClickListener l) {
        Button b = button(label, bg, fg, l);
        commandButtons.add(b);
        addWeighted(r, b, gap);
    }

    private TextView section(LinearLayout parent, String title, int topPadding) {
        TextView t = text(title, 16, DARK, true);
        t.setPadding(0, topPadding, 0, 0);
        parent.addView(t);
        return t;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);

        root.addView(text("EV Remote", 28, DARK, true));
        subtitle = text("", 13, GREY, false);
        root.addView(subtitle);

        car_image = new ImageView(this);
        car_image.setAdjustViewBounds(true);
        car_image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams carLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        carLp.topMargin = dp(8);
        carLp.bottomMargin = dp(8);
        root.addView(car_image, carLp);

        setupBox = card(root);
        section(setupBox, "Configurazione", 0);
        loginBtn = button("Accedi a " + BuildConfig.BRAND, ACCENT, Color.BLACK, v -> startActivityForResult(new Intent(this, LoginActivity.class), REQ_LOGIN));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        setupBox.addView(loginBtn, blp);
        activateBtn = button("Attiva comandi remoti (SMS)", ACCENT, Color.BLACK, v -> activate());
        setupBox.addView(activateBtn, new LinearLayout.LayoutParams(blp));

        LinearLayout st = card(root);
        LinearLayout r1 = row(st, 0);
        addValue(r1, "battery", "Batteria", false);
        addValue(r1, "autonomy", "Autonomia", true);
        LinearLayout r2 = row(st, dp(12));
        addValue(r2, "charging", "Ricarica", false);
        addValue(r2, "plugged", "Cavo", true);
        LinearLayout r3 = row(st, dp(12));
        addValue(r3, "clima", "Clima", false);
        addValue(r3, "temp", "Temperatura esterna", true);
        LinearLayout r4 = row(st, dp(12));
        addValue(r4, "doors", "Portiere", false);
        position = addValue(r4, "position", "Posizione", true);
        position.setOnClickListener(v -> openMap());
        LinearLayout r5 = row(st, dp(12));
        addValue(r5, "mileage", "Chilometri", false);
        addValue(r5, "updated", "Dati aggiornati", true);

        LinearLayout cmd = card(root);
        section(cmd, "Clima", 0);
        LinearLayout c1 = row(cmd, dp(10));
        command(c1, "Accendi clima", ACCENT, Color.BLACK, false, v -> run(car::climaOn));
        command(c1, "Spegni clima", DARK, Color.WHITE, true, v -> run(car::climaOff));
        section(cmd, "Ricarica", dp(16));
        LinearLayout c2 = row(cmd, dp(10));
        command(c2, "Avvia ricarica", Color.parseColor("#2E7D32"), Color.WHITE, false, v -> run(() -> car.charge(true, true)));
        command(c2, "Ferma ricarica", DARK, Color.WHITE, true, v -> run(() -> car.charge(false, true)));
        buildLimit(cmd);
        LinearLayout c3 = row(cmd, dp(16));
        command(c3, "Sveglia auto", LIGHT, DARK, false, v -> run(car::wake));
        refreshBtn = button("Aggiorna stato", LIGHT, DARK, v -> run(this::refreshAndEnforce));
        addWeighted(c3, refreshBtn, true);

        buildAbrp(root);

        LinearLayout more = card(root);
        section(more, "Altri dati", 0);
        LinearLayout m1 = row(more, dp(10));
        addValue(m1, "moving", "In movimento", false);
        addValue(m1, "speed", "Velocità", true);
        LinearLayout m2 = row(more, dp(12));
        addValue(m2, "engine", "Auto accesa", false);
        addValue(m2, "battery12", "Batteria 12 V", true);
        LinearLayout m3 = row(more, dp(12));
        addValue(m3, "soh_capacity", "Salute batteria (capacità)", false);
        addValue(m3, "soh_resistance", "Salute batteria (resistenza)", true);

        LinearLayout lg = card(root);
        LinearLayout lh = row(lg, 0);
        TextView lt = text("Attività", 16, DARK, true);
        addWeighted(lh, lt, false);
        TextView export = text("Esporta log", 14, Color.parseColor("#1565C0"), true);
        export.setPadding(dp(8), dp(4), 0, dp(4));
        export.setOnClickListener(v -> exportLogs());
        lh.addView(export);
        logView = text("", 12, DARK, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(0, dp(8), 0, 0);
        lg.addView(logView);

        TextView logout = text("Esci dall'account", 14, GREY, false);
        logout.setGravity(Gravity.CENTER);
        logout.setPadding(0, dp(8), 0, dp(8));
        logout.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Vuoi uscire? Andranno rifatti il login e l'attivazione dei comandi remoti con SMS.")
                .setPositiveButton("Esci", (d, w) -> logout())
                .setNegativeButton("Annulla", null).show());
        root.addView(logout);

        TextView about = text("Informazioni e licenze", 14, GREY, false);
        about.setGravity(Gravity.CENTER);
        about.setPadding(0, 0, 0, dp(8));
        about.setOnClickListener(v -> showAbout());
        root.addView(about);

        setContentView(scroll);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            car.log.info("Nessuna app per aprire " + url);
        }
    }

    private void showAbout() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(text("EV Remote " + BuildConfig.VERSION_NAME + " (" + BuildConfig.FLAVOR + ")", 16, DARK, true));
        box.addView(text("\nApp indipendente, non affiliata né approvata da Stellantis o dai suoi marchi. "
                + "Usa lo stesso back end privato delle app ufficiali, che può cambiare in qualsiasi momento.\n\n"
                + "Grazie ai due progetti open source su cui è costruita, che hanno ricostruito il protocollo Stellantis:\n"
                + "• psa_car_controller di Florian Bezannier (GPL-3.0): client OTP inWebo e API dei comandi\n"
                + "• homeassistant-stellantis-vehicles di Andrea De Giovine (MIT): protocollo aggiornato, comandi, limite di ricarica, ABRP\n"
                + "Licenza GPL-3.0. Contiene anche:\n"
                + "• Eclipse Paho MQTT 1.2.5 (EPL-2.0 / EDL-1.0)\n\n"
                + "Stellantis, Opel, Peugeot, Citroën, DS e Vauxhall sono marchi dei rispettivi proprietari.", 13, DARK, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        box.addView(button("Codice sorgente e licenze", LIGHT, DARK, v -> openUrl(BuildConfig.SOURCE_URL)), lp);
        if (!BuildConfig.KOFI_URL.isEmpty()) {
            box.addView(button("Sostieni il progetto su Ko-fi", ACCENT, Color.BLACK, v -> openUrl(BuildConfig.KOFI_URL)), new LinearLayout.LayoutParams(lp));
        }
        if (!BuildConfig.BMC_URL.isEmpty()) {
            box.addView(button("Offrimi un caffè (Buy Me a Coffee)", ACCENT, Color.BLACK, v -> openUrl(BuildConfig.BMC_URL)), new LinearLayout.LayoutParams(lp));
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setView(sv).setPositiveButton("Chiudi", null).show();
    }

    private void buildLimit(LinearLayout cmd) {
        LinearLayout r = row(cmd, dp(14));
        addWeighted(r, text("Limite di ricarica", 15, DARK, false), false);
        limitValue = text(car.limit() + " %", 18, DARK, true);
        r.addView(limitValue);

        limitBar = new SeekBar(this);
        limitBar.setMax(Car.LIMIT_MAX - Car.LIMIT_MIN);
        limitBar.setProgress(car.limit() - Car.LIMIT_MIN);
        limitBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                limitValue.setText((p + Car.LIMIT_MIN) + " %");
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                int v = bar.getProgress() + Car.LIMIT_MIN;
                s.putLong("charge_limit", v);
                s.putBool("limit_stop_sent", false);
                car.log.info("Limite di ricarica impostato al " + v + "%");
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        cmd.addView(limitBar, lp);

        limitSwitch = new Switch(this);
        limitSwitch.setText("Ferma la ricarica al limite");
        limitSwitch.setTextSize(15);
        limitSwitch.setChecked(car.limitOn());
        limitSwitch.setOnCheckedChangeListener((b, on) -> {
            s.putBool("charge_limit_on", on);
            car.log.info(on ? "Limite di ricarica attivato al " + car.limit() + "%" : "Limite di ricarica disattivato");
            if (on) {
                askBackgroundPermissions();
                ChargeGuard.ensureScheduled(this, true);
            } else {
                ChargeGuard.cancel(this);
            }
        });
        cmd.addView(limitSwitch, new LinearLayout.LayoutParams(lp));

        TextView note = text("Controllo anche ad app chiusa, più spesso vicino al limite: la batteria può superarlo di 1-3 punti.", 12, GREY, false);
        cmd.addView(note);
    }

    private void buildAbrp(LinearLayout root) {
        LinearLayout ab = card(root);
        section(ab, "ABRP – A Better Routeplanner", 0);
        abrpToken = new EditText(this);
        abrpToken.setHint("Token ABRP (Generic)");
        abrpToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        abrpToken.setSingleLine(true);
        if (s.get("abrp_token") != null) abrpToken.setText(s.get("abrp_token"));
        ab.addView(abrpToken);

        abrpSwitch = new Switch(this);
        abrpSwitch.setText("Invia i dati ad ABRP a ogni aggiornamento");
        abrpSwitch.setTextSize(15);
        abrpSwitch.setChecked(s.getBool("abrp_on", false));
        abrpSwitch.setOnCheckedChangeListener((b, on) -> {
            saveAbrpToken();
            if (on && (s.get("abrp_token") == null || s.get("abrp_token").isEmpty())) {
                car.log.info("Inserisci prima il token ABRP");
                b.setChecked(false);
                return;
            }
            s.putBool("abrp_on", on);
            car.log.info(on ? "Invio ad ABRP attivato" : "Invio ad ABRP disattivato");
            if (on) run(() -> {
                car.refreshStatus(false);
                car.log.info(car.sendAbrp() ? "Primo invio ad ABRP riuscito" : "Primo invio ad ABRP non riuscito");
            });
            else if (TripService.running) TripService.stop(this);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        ab.addView(abrpSwitch, lp);

        tripBtn = button("Avvia modalità viaggio", LIGHT, DARK, v -> {
            if (TripService.running) {
                TripService.stop(this);
                return;
            }
            saveAbrpToken();
            if (!car.abrpOn()) {
                car.log.info("Per la modalità viaggio inserisci il token e attiva l'invio ad ABRP");
                return;
            }
            askBackgroundPermissions();
            TripService.start(this);
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        ab.addView(tripBtn, blp);

        abrpStatus = text("", 13, GREY, false);
        abrpStatus.setPadding(0, dp(8), 0, 0);
        ab.addView(abrpStatus);
        ab.addView(text("Modalità viaggio: invio ogni minuto, si ferma da sola dopo 20 minuti di sosta. "
                + "Token: app ABRP → impostazioni dell'auto → Dati in tempo reale → Modifica connessioni → Generic.", 12, GREY, false));
        if (!Car.abrpAvailable()) {
            abrpToken.setEnabled(false);
            abrpSwitch.setEnabled(false);
            tripBtn.setEnabled(false);
            tripBtn.setAlpha(0.4f);
            ab.addView(text("ABRP non disponibile in questa build: manca la chiave API di Iternio.", 13, DARK, true));
        }
    }

    private void saveAbrpToken() {
        s.put("abrp_token", abrpToken.getText().toString().trim());
    }

    private void askBackgroundPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
            new AlertDialog.Builder(this)
                    .setTitle("Controllo in background")
                    .setMessage("Per fermare la ricarica al limite anche con il telefono in standby, Android deve escludere l'app dall'ottimizzazione della batteria.")
                    .setPositiveButton("Continua", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            car.log.info("Apri Impostazioni → App → EV Remote → Batteria e scegli \"Senza restrizioni\"");
                        }
                    })
                    .setNegativeButton("Non ora", null)
                    .show();
        }
    }

    private void render() {
        boolean logged = s.loggedIn();
        boolean remoteOk = logged && s.remoteReady();
        subtitle.setText(logged ? (s.get("brand") != null ? s.get("brand") + " · " : "") + "VIN " + s.get("vin") : "Non collegata");
        setupBox.setVisibility(remoteOk ? View.GONE : View.VISIBLE);
        loginBtn.setVisibility(logged ? View.GONE : View.VISIBLE);
        activateBtn.setVisibility(logged && !remoteOk ? View.VISIBLE : View.GONE);
        for (Button b : commandButtons) {
            b.setEnabled(remoteOk && !busy);
            b.setAlpha(remoteOk && !busy ? 1f : 0.4f);
        }
        refreshBtn.setEnabled(logged && !busy);
        refreshBtn.setAlpha(logged && !busy ? 1f : 0.4f);
        renderStatus();
    }

    private void renderLog() {
        logView.setText(String.join("\n", car.log.recent()));
    }

    private void renderStatus() {
        tripBtn.setText(TripService.running ? "Ferma modalità viaggio" : "Avvia modalità viaggio");
        tripBtn.setBackground(rounded(TripService.running ? ACCENT : LIGHT, 14));
        long last = s.getLong("abrp_last");
        long data = s.getLong("abrp_last_data");
        abrpStatus.setText(last > 0 ? "Ultimo invio: " + Status.clock(last) + " · " + s.get("abrp_last_result")
                + (data > 0 ? " · dato Stellantis delle " + Status.clock(data) : "") : "Nessun invio ancora");
        JSONObject st = car.status;
        if (st == null) return;
        JSONObject el = Status.electric(st), chg = Status.charging(st), ac = Status.aircon(st);
        if (el != null) {
            if (el.has("level")) set("battery", Math.round(el.optDouble("level")) + " %");
            if (el.has("autonomy")) set("autonomy", Math.round(el.optDouble("autonomy")) + " km");
        }
        if (chg != null) {
            String stt = chg.optString("status", "");
            String label = label(stt, "InProgress", "In ricarica", "Stopped", "Interrotta", "Finished", "Completata",
                    "Disconnected", "Non collegata", "Failure", "Errore");
            int[] rem = Status.parsePt(chg.optString("remainingTime", null));
            if ("InProgress".equals(stt) && rem != null) label += " (" + rem[0] + " h " + rem[1] + " min)";
            if ("Partial".equals(chg.optString("type"))) label += " · limite 80% auto";
            set("charging", label);
            if (chg.has("plugged")) set("plugged", chg.optBoolean("plugged") ? "Collegato" : "Scollegato");
        }
        long restAt = ac == null ? 0 : Status.parseTime(ac.optString("createdAt", st.optString("updatedAt", null)));
        if (car.liveClima >= 0 && car.liveClimaAt > restAt) {
            set("clima", Car.climaLabel(car.liveClima) + " · " + Status.clock(car.liveClimaAt));
        } else if (ac != null) {
            String l = label(ac.optString("status", ""), "Enabled", "Acceso", "Disabled", "Spento", "Finished", "Terminato");
            set("clima", restAt > 0 ? l + " · " + Status.clock(restAt) : l);
        }
        Boolean locked = car.locked();
        List<String> open = Status.openDoors(st);
        String doors = locked == null ? "–" : locked ? "Chiusa" : "Aperta";
        if (!open.isEmpty()) doors += " · aperto: " + String.join(", ", open);
        if (locked != null && car.lockedSince() > 0) doors += " · " + Status.clock(car.lockedSince());
        set("doors", doors);
        double[] pos = Status.position(st);
        set("position", pos == null ? "–" : "Apri mappa" + (pos[2] > 0 ? " · dato del " + Status.clock((long) pos[2]) : ""));
        position.setTextColor(pos == null ? DARK : Color.parseColor("#1565C0"));
        JSONObject air = st.optJSONObject("environment") == null ? null : st.optJSONObject("environment").optJSONObject("air");
        if (air != null && air.has("temp")) set("temp", Math.round(air.optDouble("temp")) + " °C");
        JSONObject odo = st.optJSONObject("odometer");
        if (odo != null && odo.has("mileage")) set("mileage", String.format(Locale.ITALY, "%,d km", Math.round(odo.optDouble("mileage"))));
        JSONObject kin = st.optJSONObject("kinetic");
        if (kin != null) {
            long at = Status.parseTime(kin.optString("createdAt", null));
            if (kin.has("moving")) set("moving", (kin.optBoolean("moving") ? "Sì" : "No") + (at > 0 ? " · " + Status.clock(at) : ""));
            if (kin.has("speed")) set("speed", Math.round(kin.optDouble("speed")) + " km/h");
        }
        JSONObject ign = st.optJSONObject("ignition");
        if (ign != null && ign.has("type")) {
            long at = Status.parseTime(ign.optString("createdAt", null));
            set("engine", label(ign.optString("type"), "StartUp", "Sì", "Stop", "No", "Start", "In avvio")
                    + (at > 0 ? " · " + Status.clock(at) : ""));
        }
        JSONObject b12 = st.optJSONObject("battery");
        if (b12 != null && b12.has("voltage")) set("battery12", Math.round(b12.optDouble("voltage")) + " %");
        JSONObject ext = el == null ? null : el.optJSONObject("extension");
        JSONObject elx = ext == null ? null : ext.optJSONObject("electric");
        JSONObject bat = elx == null ? null : elx.optJSONObject("battery");
        JSONObject health = bat == null ? null : bat.optJSONObject("health");
        if (health != null) {
            if (health.has("capacity")) set("soh_capacity", Math.round(health.optDouble("capacity")) + " %");
            if (health.has("resistance")) set("soh_resistance", Math.round(health.optDouble("resistance")) + " %");
        }
        long upd = Status.parseTime(el != null && el.has("updatedAt") ? el.optString("updatedAt") : st.optString("updatedAt", null));
        if (upd > 0) set("updated", Status.clock(upd));
    }

    private void set(String key, String v) {
        TextView t = values.get(key);
        if (t != null) t.setText(v);
    }

    private static String label(String value, String... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) if (pairs[i].equals(value)) return pairs[i + 1];
        return value.isEmpty() ? "–" : value;
    }

    private void openMap() {
        double[] pos = Status.position(car.status);
        if (pos == null) return;
        String geo = String.format(Locale.ROOT, "geo:%f,%f?q=%f,%f(EV Remote)", pos[0], pos[1], pos[0], pos[1]);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(geo)));
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.ROOT,
                    "https://www.google.com/maps/search/?api=1&query=%f,%f", pos[0], pos[1]))));
        }
    }

    private void exportLogs() {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Log EV Remote " + BuildConfig.VERSION_NAME)
                .putExtra(Intent.EXTRA_TEXT, car.log.export(s));
        startActivity(Intent.createChooser(send, "Esporta log"));
    }

    private void run(Task t) {
        if (busy) return;
        busy = true;
        render();
        bg.execute(() -> {
            try {
                t.run();
            } catch (Api.NeedsSetup e) {
                car.log.info(e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                car.log.info("Errore: " + friendly(e));
            } finally {
                ui.post(() -> {
                    busy = false;
                    render();
                });
            }
        });
    }

    private static String friendly(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("MAXNBTOOLS")) return "troppi dispositivi registrati sull'account (NOK:MAXNBTOOLS)";
        if (m.contains("NOK_BLOCKED")) return "attivazione bloccata da Stellantis per troppi tentativi (NOK:NOK_BLOCKED)";
        if (e instanceof java.net.UnknownHostException) return "nessuna connessione a internet";
        return e.getClass().getSimpleName() + ": " + m;
    }

    // ---------------- Login e attivazione ----------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_LOGIN || resultCode != RESULT_OK || data == null) return;
        String code = data.getStringExtra("code");
        run(() -> {
            car.log.info("Accesso in corso…");
            car.api.exchangeCode(code);
            car.api.loadAccount();
            car.log.info("Accesso riuscito");
            downloadPicture();
            car.refreshStatus(true);
        });
    }

    private void activate() {
        new AlertDialog.Builder(this)
                .setTitle("Attiva comandi remoti")
                .setMessage("Stellantis invierà un SMS con un codice al numero registrato sull'account " + BuildConfig.BRAND + ". "
                        + "Le attivazioni sono limitate: procedi solo con il telefono a portata di mano.")
                .setPositiveButton("Invia SMS", (d, w) -> run(() -> {
                    car.api.requestSms();
                    car.log.info("SMS richiesto");
                    ui.post(this::askSmsAndPin);
                }))
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void askSmsAndPin() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText sms = new EditText(this);
        sms.setHint("Codice SMS");
        sms.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText pin = new EditText(this);
        pin.setHint("PIN " + BuildConfig.BRAND);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        box.addView(sms);
        box.addView(pin);
        new AlertDialog.Builder(this)
                .setTitle("Codice SMS e PIN")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Attiva", (d, w) -> {
                    String smsCode = sms.getText().toString().trim();
                    String pinCode = pin.getText().toString().trim();
                    run(() -> {
                        car.log.info("Attivazione in corso…");
                        Otp o = Otp.create();
                        o.activate(smsCode, pinCode);
                        s.saveOtp(o);
                        car.api.mqttTokenWithOtp();
                        car.log.info("Comandi remoti attivi");
                        connectLive();
                        ui.post(() -> ChargeGuard.ensureScheduled(this, true));
                    });
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void logout() {
        ChargeGuard.cancel(this);
        new Thread(car.remote::disconnect).start();
        s.clear();
        new File(getFilesDir(), "car.png").delete();
        car.status = null;
        for (TextView v : values.values()) v.setText("–");
        car_image.setImageDrawable(null);
        car.log.clear();
        render();
    }

    // ---------------- Foto dell'auto ----------------

    private void showPicture() {
        File f = new File(getFilesDir(), "car.png");
        if (f.exists()) car_image.setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath()));
        else if (s.get("picture_url") != null) new Thread(() -> {
            try {
                downloadPicture();
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void downloadPicture() throws Exception {
        String url = s.get("picture_url");
        if (url == null) return;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        Bitmap bmp;
        try (InputStream in = c.getInputStream()) {
            bmp = BitmapFactory.decodeStream(in);
        } finally {
            c.disconnect();
        }
        if (bmp == null) return;
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "car.png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        ui.post(() -> car_image.setImageBitmap(bmp));
    }
}
