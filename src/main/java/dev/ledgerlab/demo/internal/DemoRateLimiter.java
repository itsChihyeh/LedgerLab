package dev.ledgerlab.demo.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-memory limits for the public demo (section 3, AC-9.4): a token bucket per client address and one for the
 * whole site for actions, and a cap on concurrent streams per address. Nothing is persisted and no address is
 * written anywhere; the buckets disappear when idle.
 */
@Component
@Profile("demo")
final class DemoRateLimiter {
    private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();
    private static final int PRUNE_ABOVE = 10_000;

    /** Allowed, or refused with the whole seconds after which the caller may try again. */
    record Verdict(boolean allowed, long retryAfterSeconds) {
        static final Verdict OK = new Verdict(true, 0);
    }

    /** Refill is continuous: {@code perMinute} tokens per minute, never more than {@code perMinute} stored. */
    private static final class Bucket {
        private final long cost;
        private final long max;
        private long level;
        private Instant last;

        Bucket(int perMinute, Instant now) {
            this.cost = WINDOW_NANOS / perMinute;
            this.max = cost * perMinute;
            this.level = max;
            this.last = now;
        }

        synchronized Verdict take(Instant now) {
            refill(now);
            if (level >= cost) {
                level -= cost;
                return Verdict.OK;
            }
            long missing = cost - level;
            return new Verdict(false, Math.max(1, (missing + 999_999_999L) / 1_000_000_000L));
        }

        synchronized void giveBack() {
            level = Math.min(max, level + cost);
        }

        synchronized boolean idleAndFull(Instant now) {
            refill(now);
            return level >= max;
        }

        private void refill(Instant now) {
            long elapsed = Duration.between(last, now).toNanos();
            if (elapsed > 0) {
                level = Math.min(max, level + elapsed);
                last = now;
            }
        }
    }

    private final Clock clock;
    private final int perIpPerMinute;
    private final int streamsPerIp;
    private final Bucket site;
    private final ConcurrentHashMap<String, Bucket> perIp = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> streams = new ConcurrentHashMap<>();

    DemoRateLimiter(Clock clock, DemoProperties properties) {
        this.clock = clock;
        this.perIpPerMinute = properties.actionsPerIpPerMinute();
        this.streamsPerIp = properties.streamsPerIp();
        this.site = new Bucket(properties.actionsPerMinute(), clock.instant());
    }

    /** One action from this address: its own bucket first, so a refused address never spends the site's tokens. */
    Verdict tryAction(String address) {
        Instant now = clock.instant();
        if (perIp.size() > PRUNE_ABOVE) perIp.values().removeIf(bucket -> bucket.idleAndFull(now));
        Bucket own = perIp.computeIfAbsent(address, key -> new Bucket(perIpPerMinute, now));
        Verdict mine = own.take(now);
        if (!mine.allowed()) return mine;
        Verdict all = site.take(now);
        if (!all.allowed()) own.giveBack();
        return all;
    }

    boolean tryStream(String address) {
        boolean[] granted = {false};
        streams.compute(address, (key, open) -> {
            int current = open == null ? 0 : open;
            if (current >= streamsPerIp) return open;
            granted[0] = true;
            return current + 1;
        });
        return granted[0];
    }

    void releaseStream(String address) {
        streams.computeIfPresent(address, (key, open) -> open <= 1 ? null : open - 1);
    }

    int openStreams(String address) {
        return streams.getOrDefault(address, 0);
    }
}
