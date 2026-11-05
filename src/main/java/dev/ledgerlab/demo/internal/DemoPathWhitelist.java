package dev.ledgerlab.demo.internal;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The explicit list of what a public demo server answers (section 4, AC-9.3). Anything not matched here is
 * {@link Kind#BLOCKED}; a new controller is therefore closed until someone adds a line below. Paths that are not
 * in canonical form (path parameters, encodings, dot segments, doubled slashes) are blocked outright, so a rule
 * cannot be dodged by spelling the same route differently.
 */
final class DemoPathWhitelist {
    enum Kind {
        /** Answered as 404, as if the route did not exist. */
        BLOCKED,
        /** Answered without further checks. */
        OPEN,
        /** The seed run: only from a direct local connection without proxy headers, otherwise 404. */
        ADMIN,
        /** A scenario action: counts against the action limits. */
        ACTION,
        /** The live feed: counts against the per-address stream limit. */
        STREAM,
        /** The processor's webhook: only from an allowed source address, never through a proxy. */
        WEBHOOK
    }

    private DemoPathWhitelist() {}

    static Kind classify(String method, String path) {
        List<String> segments = segments(path);
        if (segments == null) return Kind.BLOCKED;
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");
        boolean read = get || method.equals("HEAD");

        if (segments.isEmpty()) return read ? Kind.OPEN : Kind.BLOCKED;
        String first = segments.get(0);
        int size = segments.size();

        if (first.equals("webhooks")) {
            return post && size == 2 && segments.get(1).equals("stripe") ? Kind.WEBHOOK : Kind.BLOCKED;
        }
        if (first.equals("api")) {
            if (size >= 2 && segments.get(1).equals("demo")) return demo(get, post, segments);
            // Every read-only query of the investigation module; no write on /api is on this list.
            return get && size >= 2 ? Kind.OPEN : Kind.BLOCKED;
        }
        return read && isStatic(segments) ? Kind.OPEN : Kind.BLOCKED;
    }

    private static Kind demo(boolean get, boolean post, List<String> segments) {
        int size = segments.size();
        String name = size >= 3 ? segments.get(2) : "";
        if (post && size == 4 && name.equals("actions")) return Kind.ACTION;
        if (post && size == 3 && name.equals("seed")) return Kind.ADMIN;
        if (post && size == 3 && name.equals("visit")) return Kind.OPEN;
        if (get && size == 3 && name.equals("summary")) return Kind.OPEN;
        if (get && size == 3 && name.equals("archive")) return Kind.OPEN;
        if (get && size == 3 && name.equals("stream")) return Kind.STREAM;
        return Kind.BLOCKED;
    }

    /** The built front end: the page, its hashed assets, and top-level files such as the favicon or a report. */
    private static boolean isStatic(List<String> segments) {
        String first = segments.get(0);
        if (segments.size() == 1) return first.equals("index.html") || first.indexOf('.') > 0;
        return first.equals("assets");
    }

    /** Splits a canonical path into segments, or returns null when the path is not canonical. */
    private static @Nullable List<String> segments(String path) {
        if (!path.startsWith("/")) return null;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == ';' || c == '%' || c == '\\') return null;
        }
        if (path.equals("/")) return List.of();
        String[] parts = path.substring(1).split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return null;
        }
        return List.of(parts);
    }
}
