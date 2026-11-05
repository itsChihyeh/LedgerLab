package dev.ledgerlab.demo.internal;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param simulatorBaseUrl control interface of the fake Stripe / bank process, for example {@code http://simulator:8081}
 * @param appBaseUrl where the application's own public API answers; blank means this server on its local port
 * @param maxStreams concurrent SSE connections; more get 503
 * @param timelinePoll how often new events, entries and transitions are read for the timeline
 * @param stepTimeout how long one step of an action may wait for asynchronous work (webhooks, workers)
 * @param httpTimeout read timeout of every call to the simulator or the loopback API
 */
@ConfigurationProperties("ledgerlab.demo")
public record DemoProperties(
        String simulatorBaseUrl,
        @Nullable String appBaseUrl,
        @DefaultValue("200") int maxStreams,
        @DefaultValue("500ms") Duration timelinePoll,
        @DefaultValue("60s") Duration stepTimeout,
        @DefaultValue("20s") Duration httpTimeout) {
    public DemoProperties {
        if (simulatorBaseUrl == null || simulatorBaseUrl.isBlank() || maxStreams < 1) {
            throw new IllegalArgumentException("Invalid demo configuration");
        }
    }
}
