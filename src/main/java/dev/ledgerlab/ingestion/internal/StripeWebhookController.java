package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.shared.LogContext;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
final class StripeWebhookController {
    private static final Logger LOG = LoggerFactory.getLogger(StripeWebhookController.class);

    private final StripeSignatureVerifier verifier;
    private final StripeEventParser parser;
    private final InboxIntake inbox;
    private final IntakeCommitted committed;

    StripeWebhookController(
            StripeSignatureVerifier verifier, StripeEventParser parser, InboxIntake inbox, IntakeCommitted committed) {
        this.verifier = verifier;
        this.parser = parser;
        this.inbox = inbox;
        this.committed = committed;
    }

    @PostMapping("/webhooks/stripe")
    void receive(
            @RequestBody(required = false) byte @Nullable [] rawBody,
            @RequestHeader(value = "Stripe-Signature", required = false) @Nullable String signature) {
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        Instant signedAt;
        try {
            signedAt = verifier.verify(body, signature);
        } catch (InvalidSignatureException e) {
            rejected("invalid_signature");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Stripe signature");
        }
        IncomingEvent event;
        try {
            event = parser.parse(body);
        } catch (ResponseStatusException e) {
            rejected("invalid_envelope");
            throw e;
        }
        try (LogContext ignored = LogContext.with(LogContext.EVENT_ID, event.id())) {
            String outcome = inbox.receive(event, body, signedAt);
            committed.afterCommit();
            LOG.atInfo()
                    .addKeyValue("event.action", "webhook.received")
                    .addKeyValue("eventType", event.type())
                    .addKeyValue("duplicate", !outcome.equals("NEW"))
                    .addKeyValue("outcome", outcome)
                    .log("Webhook event received");
        }
    }

    // Only a fixed reason: never the signature header, the body or the secret (AC-12.3).
    private static void rejected(String reason) {
        LOG.atWarn()
                .addKeyValue("event.action", "webhook.rejected")
                .addKeyValue("reason", reason)
                .log("Webhook rejected");
    }
}
