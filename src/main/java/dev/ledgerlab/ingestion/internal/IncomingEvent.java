package dev.ledgerlab.ingestion.internal;

import java.time.Instant;

record IncomingEvent(String id, String type, String apiVersion, boolean livemode, Instant occurredAt) {}
