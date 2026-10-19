package com.septier.alarms.domain.exception;

/** Explicit operational failures that should not be disguised as transient database errors. */
public class ApiFailure extends RuntimeException {
    private final int status;
    public ApiFailure(int status, String detail) { super(detail); this.status = status; }
    public int status() { return status; }
}
