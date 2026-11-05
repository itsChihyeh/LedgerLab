package dev.ledgerlab.demo.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** An IPv4 or IPv6 network in CIDR notation, or a single address, without any name resolution. */
final class Cidr {
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]+");

    private final byte[] network;
    private final int prefix;

    private Cidr(byte[] network, int prefix) {
        this.network = network;
        this.prefix = prefix;
    }

    static Cidr parse(String text) {
        String trimmed = text.trim();
        int slash = trimmed.indexOf('/');
        String host = slash < 0 ? trimmed : trimmed.substring(0, slash);
        InetAddress address = literal(host);
        if (address == null) throw new IllegalArgumentException("Not an IP address or CIDR: " + text);
        int bits = address.getAddress().length * 8;
        int prefix = bits;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(trimmed.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Not a CIDR prefix length: " + text, e);
            }
            if (prefix < 0 || prefix > bits) throw new IllegalArgumentException("CIDR prefix out of range: " + text);
        }
        return new Cidr(address.getAddress(), prefix);
    }

    /** Parses an IP literal; anything else (a host name, junk, an empty string) is null and never resolved. */
    static @Nullable InetAddress literal(String text) {
        String value = text.trim();
        boolean looksLiteral = IPV4.matcher(value).matches()
                || (value.indexOf(':') >= 0 && IPV6.matcher(value).matches());
        if (!looksLiteral) return null;
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    boolean contains(InetAddress address) {
        byte[] other = address.getAddress();
        if (other.length != network.length) return false;
        int full = prefix / 8;
        for (int i = 0; i < full; i++) {
            if (other[i] != network[i]) return false;
        }
        int rest = prefix % 8;
        if (rest == 0) return true;
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (other[full] & mask) == (network[full] & mask);
    }
}
