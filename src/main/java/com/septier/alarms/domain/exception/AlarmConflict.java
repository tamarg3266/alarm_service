package com.septier.alarms.domain.exception;


public class AlarmConflict extends RuntimeException {
    public AlarmConflict(String message) { super(message); }
}
