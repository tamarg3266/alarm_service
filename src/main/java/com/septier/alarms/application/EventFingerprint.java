package com.septier.alarms.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.septier.alarms.application.command.AlarmRequest;
import com.septier.alarms.domain.model.AlarmEvent;
import org.springframework.stereotype.Component;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Hash a fixed field order, with JSON escaping and explicit nulls; independent of request property order. */
@Component
public class EventFingerprint {
    private final ObjectMapper json;
    public EventFingerprint(ObjectMapper json) { this.json = json; }
    public String of(AlarmRequest r) {
        try {
            Object[] fields = {r.eventId(), r.elementId(), r.alarmCode(), r.severity().name(),
                r.status().name(), r.timestamp(), r.description()};
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(fields)));
        } catch (Exception ex) { throw new IllegalStateException("Cannot fingerprint event", ex); }
    }
    public String of(AlarmEvent e) {
        return of(new AlarmRequest(e.getEventId(), e.getElementId(), e.getAlarmCode(), e.getSeverity(),
            e.getStatus(), e.getTimestamp(), e.getDescription()));
    }
}
