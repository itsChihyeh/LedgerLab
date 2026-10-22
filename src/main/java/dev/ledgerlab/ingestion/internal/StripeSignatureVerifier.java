package dev.ledgerlab.ingestion.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
final class StripeSignatureVerifier {
    private final StripeProperties properties;
    private final Clock clock;

    StripeSignatureVerifier(StripeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    Instant verify(byte[] body, @Nullable String header) {
        if (header == null) {
            throw new InvalidSignatureException();
        }
        String timestamp = null;
        ArrayList<byte[]> signatures = new ArrayList<>();
        for (String part : header.split(",", -1)) {
            String[] pair = part.trim().split("=", -1);
            if (pair.length != 2 || pair[0].isEmpty() || pair[1].isEmpty()) {
                throw new InvalidSignatureException();
            }
            if (pair[0].equals("t")) {
                if (timestamp != null || !pair[1].matches("[0-9]+")) {
                    throw new InvalidSignatureException();
                }
                timestamp = pair[1];
            } else if (pair[0].equals("v1")) {
                if (!pair[1].matches("[0-9a-fA-F]{64}")) {
                    throw new InvalidSignatureException();
                }
                signatures.add(HexFormat.of().parseHex(pair[1]));
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new InvalidSignatureException();
        }
        long signedAt;
        try {
            signedAt = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new InvalidSignatureException();
        }
        long now = clock.instant().getEpochSecond();
        if (signedAt < now - 300 || signedAt > now + 300) {
            throw new InvalidSignatureException();
        }
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            expected = mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
        boolean matches = false;
        for (byte[] signature : signatures) {
            matches |= MessageDigest.isEqual(expected, signature);
        }
        if (!matches) {
            throw new InvalidSignatureException();
        }
        return Instant.ofEpochSecond(signedAt);
    }
}
