package dev.ledgerlab.settlement;

public final class BatchRecheckException extends RuntimeException {
    private final int status;
    private final String code;

    public BatchRecheckException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
