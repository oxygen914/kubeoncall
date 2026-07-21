package com.kubeoncall.alarm.readmodel;

/**
 * Domain exception raised by {@link AlarmCommandService} for command-path failures (not found,
 * invalid lifecycle, optimistic-lock conflict, service unavailable). Kept in the alarm readmodel
 * package so the command service does not depend on the web layer; the v1 exception handler maps it
 * to the unified {@code {error, meta}} envelope with a stable code.
 */
public class AlarmCommandException extends RuntimeException {

    public enum Code {
        NOT_FOUND(404),
        CONFLICT(409),
        RESOURCE_VERSION_CONFLICT(409),
        SERVICE_UNAVAILABLE(503),
        INVALID(400);

        private final int httpStatus;

        Code(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }

    private final Code code;

    public AlarmCommandException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
