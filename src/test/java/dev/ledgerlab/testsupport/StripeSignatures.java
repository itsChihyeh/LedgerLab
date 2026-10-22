package dev.ledgerlab.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Independent test-side implementation of Stripe's wire signature. */
public final class StripeSignatures {
    public static final String SECRET = "whsec_test_only";

    private StripeSignatures() {}

    public static String sign(byte[] body, long timestamp) {
        return sign(body, timestamp, SECRET);
    }

    public static String sign(byte[] body, long timestamp, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }
}
