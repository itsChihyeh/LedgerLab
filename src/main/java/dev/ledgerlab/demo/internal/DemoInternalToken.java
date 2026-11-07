package dev.ledgerlab.demo.internal;

/** A random secret created at startup and never logged or served; see {@link DemoGateFilter}. */
record DemoInternalToken(String value) {
    @Override
    public String toString() {
        return "DemoInternalToken[hidden]";
    }
}
