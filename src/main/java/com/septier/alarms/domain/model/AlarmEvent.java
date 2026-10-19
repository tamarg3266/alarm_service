package com.septier.alarms.domain.model;


import jakarta.persistence.*;

@Entity
@Table(name = "alarm_history")
public class AlarmEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 128) private String eventId;
    @Column(nullable = false, length = 128) private String elementId;
    @Column(nullable = false, length = 64) private String alarmCode;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Severity severity;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private AlarmStatus status;
    @Column(name = "event_timestamp", nullable = false) private Long timestamp;
    @Column(length = 2048) private String description;
    @Column(nullable = false) private java.time.Instant acceptedAt = java.time.Instant.now();
    protected AlarmEvent() {}
    public AlarmEvent(String eventId, String elementId, String alarmCode, Severity severity,
                      AlarmStatus status, Long timestamp, String description) {
        this.eventId = eventId; this.elementId = elementId; this.alarmCode = alarmCode;
        this.severity = severity; this.status = status; this.timestamp = timestamp; this.description = description;
    }
    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getElementId() { return elementId; }
    public String getAlarmCode() { return alarmCode; }
    public Severity getSeverity() { return severity; }
    public AlarmStatus getStatus() { return status; }
    public Long getTimestamp() { return timestamp; }
    public String getDescription() { return description; }
    public java.time.Instant getAcceptedAt() { return acceptedAt; }
}
