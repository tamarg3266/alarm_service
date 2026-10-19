package com.septier.alarms.application.command;

import com.septier.alarms.domain.model.Severity;
import com.septier.alarms.domain.model.AlarmStatus;

import jakarta.validation.constraints.*;

public record AlarmRequest(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_.:-]{1,128}") String eventId,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_.:-]{1,128}") String elementId,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_.:-]{1,64}") String alarmCode,
    @NotNull Severity severity,
    @NotNull AlarmStatus status,
    @NotNull @PositiveOrZero Long timestamp,
    @Size(max = 2048) @Pattern(regexp = "[^\\x00]*", message = "must not contain a NUL character") String description
) {}
