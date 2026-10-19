package com.septier.alarms.domain.model;



import jakarta.persistence.*;

@Entity
@Table(name = "active_alarm", uniqueConstraints = @UniqueConstraint(columnNames = {"element_id", "alarm_code"}))
public class ActiveAlarm {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 128) private String elementId;
    @Column(nullable = false, length = 64) private String alarmCode;
    @OneToOne(optional = false) @JoinColumn(name = "history_id", nullable = false) private AlarmEvent event;
    protected ActiveAlarm() {}
    public ActiveAlarm(AlarmEvent event) { elementId = event.getElementId(); alarmCode = event.getAlarmCode(); this.event = event; }
    public void update(AlarmEvent event) { this.event = event; }
    AlarmEvent event() { return event; }
}
