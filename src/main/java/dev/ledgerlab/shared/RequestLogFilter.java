package dev.ledgerlab.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * First filter in the chain (docs/design/observability.md section 1.2, 1.3): gives every request a {@code requestId}
 * (the caller's {@code X-Request-Id} when it is well formed, otherwise a new {@code req_} id), echoes it in the
 * response, and writes one {@code http.request} line when the request ends. It never reads the body or the
 * headers other than {@code X-Request-Id}.
 *
 * <p>The id comes from this filter's own {@link IdGenerator}, not the application bean: that one is seeded in tests
 * and in the empty-database rebuild, and drawing from it here would change every later business id (AC-4.13).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class RequestLogFilter extends OncePerRequestFilter {
    static final String HEADER = "X-Request-Id";
    static final String UNMATCHED = "UNMATCHED";

    private static final Logger LOG = LoggerFactory.getLogger(RequestLogFilter.class);
    private static final Pattern ACCEPTABLE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final Clock clock;
    private final IdGenerator requestIds;

    RequestLogFilter(Clock clock) {
        this.clock = clock;
        this.requestIds = IdGenerator.secure(clock);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String requestId = supplied != null && ACCEPTABLE.matcher(supplied).matches() ? supplied : generate();
        response.setHeader(HEADER, requestId);
        long started = clock.millis();
        try (LogContext ignored = LogContext.with(LogContext.REQUEST_ID, requestId)) {
            try {
                chain.doFilter(request, response);
            } finally {
                if (!request.getRequestURI().startsWith("/actuator")) {
                    LOG.atInfo()
                            .addKeyValue("event.action", "http.request")
                            .addKeyValue("http.method", request.getMethod())
                            .addKeyValue("http.route", route(request))
                            .addKeyValue("http.status", response.getStatus())
                            .addKeyValue("durationMs", clock.millis() - started)
                            .log("HTTP request handled");
                }
            }
        }
    }

    private String generate() {
        return requestIds.next("req");
    }

    private static String route(HttpServletRequest request) {
        @Nullable Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        // "/**" is the static-resource fallback that answers every path no controller claims: no route at all.
        return pattern instanceof String template && !template.equals("/**") ? template : UNMATCHED;
    }
}
