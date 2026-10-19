package com.septier.alarms.application;

import com.septier.alarms.application.command.AlarmRequest;
import com.septier.alarms.domain.exception.AlarmConflict;
import com.septier.alarms.domain.model.AlarmEvent;
import com.septier.alarms.domain.model.ActiveAlarm;
import com.septier.alarms.domain.model.Severity;
import com.septier.alarms.domain.model.AlarmStatus;
import com.septier.alarms.infrastructure.persistence.AlarmHistoryRepository;
import com.septier.alarms.infrastructure.persistence.ActiveAlarmRepository;
import com.septier.alarms.infrastructure.persistence.IngestionLockRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;
import java.util.*;

@Service
public class AlarmService {
    private final AlarmHistoryRepository history;
    private final ActiveAlarmRepository active;
    private final com.septier.alarms.infrastructure.persistence.KeyLocks locks;
    private final com.septier.alarms.infrastructure.persistence.EventReceipts receipts;
    private final EventFingerprint fingerprints;
    private final long maxHistoryId;
    public AlarmService(AlarmHistoryRepository history, ActiveAlarmRepository active,
            com.septier.alarms.infrastructure.persistence.KeyLocks locks,
            com.septier.alarms.infrastructure.persistence.EventReceipts receipts, EventFingerprint fingerprints,
            @org.springframework.beans.factory.annotation.Value("${alarm.max-history-id}") long maxHistoryId) {
        this.history = history; this.active = active; this.locks = locks;
        this.receipts = receipts; this.fingerprints = fingerprints; this.maxHistoryId = maxHistoryId;
    }
    public record Acceptance(String eventId, long historyId, boolean duplicate) {}

    @Transactional(timeout = 10)
    public Acceptance accept(AlarmRequest request) {
        locks.acquire("event:" + request.eventId());
        String fingerprint = fingerprints.of(request);
        var receipt = receipts.find(request.eventId());
        if (receipt.isPresent()) {
            if (!receipt.get().fingerprint().equals(fingerprint))
                throw new AlarmConflict("eventId already belongs to a different event");
            return new Acceptance(request.eventId(), receipt.get().historyId(), true);
        }
        // V1 events have no receipt yet. Backfill lazily without modifying their state.
        var existing = history.findByEventId(request.eventId());
        if (existing.isPresent()) {
            if (!fingerprints.of(existing.get()).equals(fingerprint))
                throw new AlarmConflict("eventId already belongs to a different event");
            receipts.insert(request.eventId(), existing.get().getId(), fingerprint);
            return new Acceptance(request.eventId(), existing.get().getId(), true);
        }
        locks.acquire("element:" + request.elementId());
        var current = active.findByElementIdAndAlarmCode(request.elementId(), request.alarmCode());
        if (request.status() == AlarmStatus.CLEARED && current.isEmpty())
            throw new AlarmConflict("Cannot clear an alarm that is not active");
        var event = history.saveAndFlush(new AlarmEvent(request.eventId(), request.elementId(), request.alarmCode(),
            request.severity(), request.status(), request.timestamp(), request.description()));
        // An identity sequence bounds lifetime admission without a shared row lock on all elements.
        // Rollbacks consume IDs too: conservative capacity, never an unbounded receipt registry.
        if (event.getId() > maxHistoryId)
            throw new com.septier.alarms.domain.exception.ApiFailure(507, "Event capacity reached; an operator must increase MAX_HISTORY_ID or provision more storage");
        receipts.insert(request.eventId(), event.getId(), fingerprint);
        if (request.status() == AlarmStatus.CLEARED) active.delete(current.orElseThrow());
        else if (current.isPresent()) current.get().update(event);
        else active.save(new ActiveAlarm(event));
        return new Acceptance(event.getEventId(), event.getId(), false);
    }
    @Transactional(readOnly = true)
    public Page<AlarmEvent> active(String element, Pageable page) { return active.active(element, page); }
    @Transactional(readOnly = true)
    public Page<AlarmEvent> history(String element, Pageable page) { return history.history(element, page); }
    @Transactional(readOnly = true)
    public Map<Severity, Long> statistics() {
        Map<Severity, Long> result = new EnumMap<>(Severity.class);
        for (var severity : Severity.values()) result.put(severity, 0L);
        active.counts().forEach(c -> result.put(c.getSeverity(), c.getTotal()));
        return result;
    }
}
