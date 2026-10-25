package dev.ledgerlab.corrections;

import org.jspecify.annotations.Nullable;

public final class CorrectionException extends RuntimeException {
    private final int status;
    private final String code;
    private final @Nullable String correctionId;

    public CorrectionException(int status, String code) {
        this(status, code, null);
    }

    public CorrectionException(int status, String code, @Nullable String correctionId) {
        super(code);
        this.status = status;
        this.code = code;
        this.correctionId = correctionId;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public @Nullable String correctionId() {
        return correctionId;
    }
}
