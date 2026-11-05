package dev.ledgerlab.demo.internal;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The address a request is attributed to for rate limiting (section 3). The connection address is the only thing
 * a caller cannot forge, so {@code X-Forwarded-For} is read only when the connection comes from a trusted proxy,
 * and then from the right: the first entry that is not itself a trusted proxy is the client. Everything to its
 * left was written by the client and is ignored.
 */
final class ClientAddress {
    private static final int MAX_KEY = 64;

    private final List<Cidr> trusted;

    ClientAddress(List<Cidr> trusted) {
        this.trusted = List.copyOf(trusted);
    }

    String of(String remoteAddress, List<String> forwardedFor) {
        InetAddress remote = Cidr.literal(remoteAddress);
        if (remote == null || !isTrusted(remote)) return key(remote, remoteAddress);
        List<String> chain = new ArrayList<>();
        for (String header : forwardedFor) {
            for (String part : header.split(",")) {
                if (!part.isBlank()) chain.add(part.trim());
            }
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            String entry = chain.get(i);
            InetAddress parsed = Cidr.literal(entry);
            if (parsed != null && isTrusted(parsed)) continue;
            return key(parsed, entry);
        }
        return key(remote, remoteAddress);
    }

    private boolean isTrusted(InetAddress address) {
        for (Cidr cidr : trusted) {
            if (cidr.contains(address)) return true;
        }
        return false;
    }

    private static String key(@Nullable InetAddress parsed, String raw) {
        if (parsed != null) return parsed.getHostAddress();
        return raw.length() > MAX_KEY ? raw.substring(0, MAX_KEY) : raw;
    }
}
