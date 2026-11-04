package dev.ledgerlab.payouts;

import org.jspecify.annotations.Nullable;

public final class PayoutException extends RuntimeException {
    private final String code;
    private final @Nullable String reason;

    public PayoutException(String code) {
        this(code, (String) null);
    }

    public PayoutException(String code, @Nullable String reason) {
        super(code);
        this.code = code;
        this.reason = reason;
    }

    public PayoutException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
        this.reason = null;
    }

    public String code() {
        return code;
    }

    public @Nullable String reason() {
        return reason;
    }
}
