package it.gbon.evremote;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class OtpHarness {
    public static void main(String[] a) throws Exception {
        LinkedList<String> queue = new LinkedList<>(Files.readAllLines(Paths.get(System.getenv().getOrDefault("OTP_TEST_OUT", "/tmp/otptest-run"), "responses.txt")));
        queue.removeIf(String::isEmpty);
        List<String> urls = new ArrayList<>();
        int[] counter = {0};
        OtpCrypto.rng = n -> Arrays.copyOf(OtpCrypto.sha256(("rng" + counter[0]++).getBytes(StandardCharsets.UTF_8)), n);

        Otp o = new Otp();
        o.deviceId = "0123456789abcdef";
        o.iwalea = "f".repeat(32);
        o.transport = url -> { urls.add(url); return queue.removeFirst(); };
        o.activate("123456", "4321");
        String code1 = o.nextCode();

        Otp o2 = Otp.fromMap(o.toMap());
        o2.transport = o.transport;
        String code2 = o2.nextCode();

        urls.add("CODE1 " + code1);
        urls.add("CODE2 " + code2);
        Files.write(Paths.get(System.getenv().getOrDefault("OTP_TEST_OUT", "/tmp/otptest-run"), "java_urls.txt"), urls);
        System.out.println("java ok " + code1 + " " + code2 + " " + (urls.size() - 2) + " richieste");
    }
}
