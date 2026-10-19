package com.septier.alarms.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class EventReceipts {
    public record Receipt(String eventId, long historyId, String fingerprint) {}
    private final JdbcTemplate jdbc;
    public EventReceipts(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<Receipt> find(String id) {
        return jdbc.query("SELECT event_id, history_id, fingerprint FROM event_receipt WHERE event_id = ?",
            (r, n) -> new Receipt(r.getString(1), r.getLong(2), r.getString(3)), id).stream().findFirst();
    }
    public void insert(String id, long historyId, String fingerprint) {
        int inserted = jdbc.update("INSERT INTO event_receipt(event_id, history_id, fingerprint) VALUES (?,?,?) ON CONFLICT DO NOTHING",
            id, historyId, fingerprint);
        if (inserted == 0) {
            var current = find(id).orElseThrow();
            if (current.historyId() != historyId || !current.fingerprint().equals(fingerprint))
                throw new com.septier.alarms.domain.exception.AlarmConflict("eventId already belongs to a different event");
        }
    }
}
