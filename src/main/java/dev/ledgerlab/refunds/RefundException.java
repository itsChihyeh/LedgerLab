package dev.ledgerlab.refunds;

public final class RefundException extends RuntimeException {
    private final String code;

    public RefundException(String code) {
        super(code);
        this.code = code;
    }

    public RefundException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
