package dev.ledgerlab.ingestion.internal;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
final class StripeWebhookController {
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
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Stripe signature");
        }
        IncomingEvent event = parser.parse(body);
        inbox.receive(event, body, signedAt);
        committed.afterCommit();
    }
}
