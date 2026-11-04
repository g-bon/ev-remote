package it.gbon.evremote;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Client OTP inWebo (otp.mpsa.com), porting di otp.py di psa_car_controller /
 * homeassistant-stellantis-vehicles. Lo stato va salvato con {@link #toMap()} dopo ogni uso.
 */
public final class Otp {
    public static final class OtpException extends Exception {
        public OtpException(String m) { super(m); }
    }

    public interface Transport {
        String get(String url) throws IOException;
    }

    static final String HOST = "https://otp.mpsa.com/iwws/MAC";
    static final String MACID = "bb8e981582b0f31353108fb020bead1c";
    static final String VERSION = "Generator-1.0/0.2.11";
    static final String ACTIVATE = "activate", OTP = "otp", MS = "ms";

    String deviceId, iwalea, codepin, smsCode, kiw, kfact;
    String mode = ACTIVATE, action = "", challenge = "", defi;
    String iwid = "", iwTsync = "0", iwK0 = "", iwK1 = "", iwsecid, iwsecval;

    Transport transport = Otp::httpGet;

    public static Otp create() {
        Otp o = new Otp();
        o.deviceId = OtpCrypto.hex(OtpCrypto.randomBytes(8));
        o.iwalea = OtpCrypto.hex(OtpCrypto.randomBytes(16));
        return o;
    }

    public Map<String, String> toMap() {
        Map<String, String> m = new HashMap<>();
        m.put("deviceId", deviceId); m.put("iwalea", iwalea); m.put("codepin", codepin);
        m.put("kiw", kiw); m.put("kfact", kfact); m.put("action", action); m.put("challenge", challenge);
        m.put("defi", defi); m.put("iwid", iwid); m.put("iwTsync", iwTsync); m.put("iwK0", iwK0);
        m.put("iwK1", iwK1); m.put("iwsecid", iwsecid); m.put("iwsecval", iwsecval);
        return m;
    }

    public static Otp fromMap(Map<String, String> m) {
        Otp o = new Otp();
        o.deviceId = m.get("deviceId"); o.iwalea = m.get("iwalea"); o.codepin = m.get("codepin");
        o.kiw = m.get("kiw"); o.kfact = m.get("kfact"); o.action = m.get("action"); o.challenge = m.get("challenge");
        o.defi = m.get("defi"); o.iwid = m.get("iwid"); o.iwTsync = m.get("iwTsync"); o.iwK0 = m.get("iwK0");
        o.iwK1 = m.get("iwK1"); o.iwsecid = m.get("iwsecid"); o.iwsecval = m.get("iwsecval");
        return o;
    }

    String serial() {
        return deviceId + "/_/" + iwalea;
    }

    String kma() {
        return OtpCrypto.sha256Hex(codepin + ";" + serial()).substring(0, 32);
    }

    Map<String, String> r() {
        String iw = iwK0;
        String r2 = "synchro".equals(action) ? challenge + ";" + iw + ";" + codepin : challenge + ";" + iw + ";";
        Map<String, String> m = new LinkedHashMap<>();
        m.put("R0", OtpCrypto.sha256Hex(challenge + ";" + iw + ";" + serial()));
        m.put("R1", OtpCrypto.sha256Hex(challenge + ";" + iw + ";" + iwK1));
        m.put("R2", OtpCrypto.sha256Hex(r2));
        return m;
    }

    private Map<String, String> base(String act, String md) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("action", act);
        p.put("mode", md);
        p.put("id", iwid);
        p.put("lastsync", iwTsync);
        return p;
    }

    /** Risposta XML: tag figli del nodo radice. Le chiavi presenti con valore vuoto restano con valore null. */
    Map<String, String> request(Map<String, String> params, String root) throws IOException, OtpException {
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null) continue;
            if (q.length() > 0) q.append('&');
            q.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        String raw = transport.get(HOST + "?" + q);
        int i = raw.indexOf("?>");
        if (i >= 0) raw = raw.substring(i + 2);
        if (raw.contains("<!DOCTYPE") || raw.contains("<!ENTITY")) throw new OtpException("Bad response from server: DTD not allowed");
        try {
            Element el = secureFactory().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(raw.trim().getBytes(StandardCharsets.UTF_8))).getDocumentElement();
            if (!root.equals(el.getTagName())) throw new OtpException("Bad response from server: " + el.getTagName());
            Map<String, String> out = new LinkedHashMap<>();
            NodeList children = el.getChildNodes();
            for (int c = 0; c < children.getLength(); c++) {
                Node n = children.item(c);
                if (n.getNodeType() != Node.ELEMENT_NODE || out.containsKey(n.getNodeName())) continue;
                String text = n.getTextContent() == null ? "" : n.getTextContent().trim();
                out.put(n.getNodeName(), text.isEmpty() ? null : text);
            }
            return out;
        } catch (OtpException e) {
            throw e;
        } catch (Exception e) {
            throw new OtpException("Bad response from server");
        }
    }

    /** Parser without DTDs or external entities (XXE). Android's parser rejects unknown features: ignore those errors. */
    static DocumentBuilderFactory secureFactory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (ParserConfigurationException ignored) {
        }
        try {
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        } catch (ParserConfigurationException ignored) {
        }
        try {
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (ParserConfigurationException ignored) {
        }
        try {
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException ignored) {
        }
        try {
            f.setXIncludeAware(false);
        } catch (UnsupportedOperationException ignored) {
        }
        f.setExpandEntityReferences(false);
        return f;
    }

    private static boolean has(Map<String, String> xml, String k) {
        String v = xml.get(k);
        return v != null && !v.isEmpty();
    }

    void synchro(Map<String, String> xml, String key) {
        if (has(xml, "id")) iwid = xml.get("id");
        if (has(xml, "K0")) iwK0 = OtpCrypto.hex(OtpCrypto.aesEcb(Cipher.DECRYPT_MODE, key, OtpCrypto.unhex(xml.get("K0"))));
        if (has(xml, "K1")) iwK1 = OtpCrypto.hex(OtpCrypto.aesEcb(Cipher.DECRYPT_MODE, key, OtpCrypto.unhex(xml.get("K1"))));
        if (has(xml, "dK1")) iwK1 = OtpCrypto.sha256Hex(iwK1 + ";" + xml.get("dK1")).substring(0, 32);
        if (has(xml, "s_n")) iwTsync = xml.get("Tsync");
    }

    void activationStart() throws IOException, OtpException {
        Map<String, String> p = base("ActionSetup", mode);
        p.put("version", VERSION);
        p.put("macid", MACID);
        if (OTP.equals(mode)) p.put("sid", iwsecid);
        else p.put("code", smsCode);
        Map<String, String> xml = request(p, "ActionSetup");
        if (!"OK".equals(xml.get("err"))) throw new OtpException(String.valueOf(xml.get("err")));
        if (ACTIVATE.equals(mode)) {
            kfact = xml.get("Kfact");
            kiw = OtpCrypto.decodeOaep(xml.get("Kiw"), kfact);
        } else {
            challenge = xml.get("challenge");
        }
    }

    /** @return "OK", "TWICE" oppure il codice d'errore del server. */
    String activationFinalize() throws IOException, OtpException {
        Map<String, String> p = base("ActionFinalize", mode);
        p.put("version", VERSION);
        p.put("lang", "fr");
        p.put("ack", "");
        p.put("macid", MACID);
        if (OTP.equals(mode)) {
            p.put("keytype", "0");
            p.put("sid", iwsecid);
        } else {
            p.put("serial", serial());
            p.put("code", smsCode);
            p.put("Kma", OtpCrypto.hex(OtpCrypto.oaepEncrypt(kiw, OtpCrypto.unhex(kma()), null)));
            p.put("pin", OtpCrypto.hex(OtpCrypto.oaepEncrypt(kiw, codepin.getBytes(StandardCharsets.UTF_8), null)));
            p.put("name", "Android SDK built for x86_64 / UNKNOWN");
        }
        p.putAll(r());
        Map<String, String> xml = request(p, "ActionFinalize");
        if (!"OK".equals(xml.get("err"))) return String.valueOf(xml.get("err"));
        synchro(xml, kma());

        if (OTP.equals(mode)) {
            if (!xml.containsKey("defi") || xml.get("defi") == null) throw new OtpException("Missing 'defi' in OTP response");
            defi = xml.get("defi");
            return xml.containsKey("J") ? "TWICE" : "OK";
        }

        if (!xml.containsKey("ms_n")) return "OK";
        if (Integer.parseInt(xml.get("ms_n")) > 1) throw new OtpException("ms_n > 1 non supportato");

        challenge = xml.get("challenge");
        action = "synchro";
        String tempKey = OtpCrypto.decodeOaep(xml.get("ms_key"), kfact);
        byte[] random = OtpCrypto.randomBytes(16);
        byte[] kpub = OtpCrypto.oaepEncrypt(tempKey, random, null);
        iwsecval = OtpCrypto.hex(OtpCrypto.aesEcb(Cipher.ENCRYPT_MODE, kma(), random));
        iwsecid = xml.get("s_id");

        Map<String, String> q = new LinkedHashMap<>();
        q.put("action", "ActionFinalize");
        q.put("mode", MS);
        q.put("ms_id0", xml.get("ms_id"));
        q.put("ms_val0", OtpCrypto.hex(kpub));
        q.put("macid", MACID);
        q.put("id", iwid);
        q.put("lastsync", iwTsync);
        q.put("ms_n", "1");
        q.putAll(r());
        synchro(request(q, "ActionFinalize"), kma());
        return "OK";
    }

    /** Prima attivazione con il codice SMS e il PIN dell'app. */
    public void activate(String sms, String pin) throws IOException, OtpException {
        smsCode = sms;
        codepin = pin;
        mode = ACTIVATE;
        activationStart();
        String res = activationFinalize();
        if (!"OK".equals(res)) throw new OtpException(res);
        if (iwsecval == null) throw new OtpException("Attivazione incompleta (nessuna chiave di servizio)");
    }

    /** Nuovo codice OTP (Stellantis ne accetta circa 6 al giorno). */
    public String nextCode() throws IOException, OtpException {
        mode = OTP;
        activationStart();
        String res = activationFinalize();
        if ("TWICE".equals(res)) {
            mode = OTP;
            activationStart();
            res = activationFinalize();
        }
        if (!"OK".equals(res)) throw new OtpException("Can't get otp code: " + res);
        return OtpCrypto.otpCode(iwK1, defi, iwsecval);
    }

    static String httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Connection", "Keep-Alive");
        c.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 8.0.0; Android SDK built for x86_64 Build/OSR1.180418.004)");
        try (InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream()) {
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
