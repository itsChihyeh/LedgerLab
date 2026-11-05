package dev.ledgerlab.demo.internal;

/** The two HTTP surfaces a scenario may touch: the application's own public API and the simulator's controls. */
record DemoClients(JsonClient app, JsonClient simulator) {}
