package dev.ledgerlab.demo.internal;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The public demo's front door (sections 3 and 4, AC-9.3, AC-9.4). Runs before everything else and decides from the
 * request line alone: routes not on {@link DemoPathWhitelist} are answered 404 as if they did not exist (not 403,
 * which would confirm they do); the seed run and the webhook only answer a direct local caller; actions and streams are rate limited with 429 and {@code Retry-After}. A refused
 * request never reaches a controller, so it cannot write anything.
 *
 * <p>The scenarios drive the application's own write API over loopback; those calls carry a random per-process
 * token that only this process knows and are let through, which is how the actions work while the same routes stay
 * closed to the public.
 */
@Component
@Profile("demo")
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // after the request-id filter in `shared`, before everything else
final class DemoGateFilter extends OncePerRequestFilter {
    static final String INTERNAL_HEADER = "X-Ledgerlab-Demo-Internal";

    private static final List<String> PROXY_HEADERS = List.of("X-Forwarded-For", "Forwarded", "X-Real-IP");

    private final DemoRateLimiter limiter;
    private final ClientAddress clients;
    private final List<Cidr> webhookSources;
    private final List<Cidr> adminSources;
    private final byte[] internalToken;

    DemoGateFilter(DemoRateLimiter limiter, DemoProperties properties, DemoInternalToken token) {
        this.limiter = limiter;
        this.clients = new ClientAddress(
                properties.trustedProxies().stream().map(Cidr::parse).toList());
        this.webhookSources =
                properties.webhookAllowedSources().stream().map(Cidr::parse).toList();
        this.adminSources =
                properties.adminAllowedSources().stream().map(Cidr::parse).toList();
        this.internalToken = token.value().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isInternal(request)) {
            chain.doFilter(request, response);
            return;
        }
        switch (DemoPathWhitelist.classify(request.getMethod(), request.getRequestURI())) {
            case BLOCKED -> response.sendError(HttpServletResponse.SC_NOT_FOUND);
            case OPEN -> chain.doFilter(request, response);
            case WEBHOOK -> {
                if (directFrom(request, webhookSources)) chain.doFilter(request, response);
                else response.sendError(HttpServletResponse.SC_NOT_FOUND);
            }
            case ADMIN -> {
                if (directFrom(request, adminSources)) chain.doFilter(request, response);
                else response.sendError(HttpServletResponse.SC_NOT_FOUND);
            }
            case ACTION -> {
                DemoRateLimiter.Verdict verdict = limiter.tryAction(clientOf(request));
                if (verdict.allowed()) chain.doFilter(request, response);
                else tooMany(response, verdict.retryAfterSeconds(), "RATE_LIMITED");
            }
            case STREAM -> stream(request, response, chain);
        }
    }

    private void stream(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = clientOf(request);
        if (!limiter.tryStream(client)) {
            tooMany(response, 5, "TOO_MANY_STREAMS");
            return;
        }
        boolean handedOver = false;
        try {
            chain.doFilter(request, response);
            if (request.isAsyncStarted()) {
                request.getAsyncContext().addListener(new ReleaseOnEnd(limiter, client));
                handedOver = true;
            }
        } catch (IllegalStateException alreadyEnded) {
            // The stream ended before the listener could be attached; the slot is released below.
        } finally {
            if (!handedOver) limiter.releaseStream(client);
        }
    }

    private String clientOf(HttpServletRequest request) {
        List<String> forwarded = Collections.list(request.getHeaders("X-Forwarded-For"));
        return clients.of(request.getRemoteAddr(), forwarded);
    }

    /**
     * True when the connection address is in {@code sources} and the request carries no proxy header. A reverse
     * proxy on the same machine connects from loopback too, but it adds these headers; a direct caller (the
     * stack's relay, the operator's script) does not.
     */
    private boolean directFrom(HttpServletRequest request, List<Cidr> sources) {
        for (String header : PROXY_HEADERS) {
            if (request.getHeader(header) != null) return false;
        }
        var address = Cidr.literal(request.getRemoteAddr());
        if (address == null) return false;
        for (Cidr source : sources) {
            if (source.contains(address)) return true;
        }
        return false;
    }

    private boolean isInternal(HttpServletRequest request) {
        String presented = request.getHeader(INTERNAL_HEADER);
        return presented != null && MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), internalToken);
    }

    private static void tooMany(HttpServletResponse response, long retryAfterSeconds, String error) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }

    /** Gives the stream slot back exactly once, however the connection ends. */
    private static final class ReleaseOnEnd implements AsyncListener {
        private final DemoRateLimiter limiter;
        private final String client;
        private final AtomicBoolean released = new AtomicBoolean();

        ReleaseOnEnd(DemoRateLimiter limiter, String client) {
            this.limiter = limiter;
            this.client = client;
        }

        private void release() {
            if (released.compareAndSet(false, true)) limiter.releaseStream(client);
        }

        @Override
        public void onComplete(AsyncEvent event) {
            release();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            release();
        }

        @Override
        public void onError(AsyncEvent event) {
            release();
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }
}
