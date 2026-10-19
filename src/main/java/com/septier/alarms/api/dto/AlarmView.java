package com.septier.alarms.api.dto;

import com.septier.alarms.domain.model.AlarmEvent;
import com.septier.alarms.domain.model.AlarmStatus;
import com.septier.alarms.domain.model.Severity;

/** Stable response contract; persistence entities are never serialized by the API. */
public record AlarmView(long id, String eventId, String elementId, String alarmCode,
                        Severity severity, AlarmStatus status, long timestamp, String description) {
    public static AlarmView from(AlarmEvent event) {
        return new AlarmView(event.getId(), event.getEventId(), event.getElementId(), event.getAlarmCode(),
            event.getSeverity(), event.getStatus(), event.getTimestamp(), event.getDescription());
    }
}
