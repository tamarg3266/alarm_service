package com.septier.alarms.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.septier.alarms.api.dto.AlarmView;
import com.septier.alarms.domain.exception.ApiFailure;
import com.septier.alarms.infrastructure.persistence.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Persist bounded, short-lived result snapshots so pages cannot drift between requests or replicas. */
@Service
public class QuerySnapshots {
    public record Result(List<AlarmView> content, int page, int size, long totalElements,
                         String snapshotId, Instant expiresAt) {}
    private final AlarmHistoryRepository history;
    private final ActiveAlarmRepository active;
    private final IngestionLockRepository quotaLock;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final int ttl, maxCount, maxRecords, maxBytes;
    public QuerySnapshots(AlarmHistoryRepository history, ActiveAlarmRepository active,
            IngestionLockRepository quotaLock, JdbcTemplate jdbc, ObjectMapper json,
            @Value("${alarm.snapshots.ttl-seconds}") int ttl,
            @Value("${alarm.snapshots.max-count}") int maxCount,
            @Value("${alarm.snapshots.max-records}") int maxRecords,
            @Value("${alarm.snapshots.max-bytes}") int maxBytes) {
        this.history = history; this.active = active; this.quotaLock = quotaLock;
        this.jdbc = jdbc; this.json = json; this.ttl = ttl; this.maxCount = maxCount;
        this.maxRecords = maxRecords; this.maxBytes = maxBytes;
    }
    @Transactional(timeout = 10)
    public Result read(boolean historical, String element, int page, int size, String token) {
        String key = (historical ? "history:" : "active:") + Objects.toString(element, "*");
        List<AlarmView> rows;
        Instant expires;
        if (token == null) {
            if (page != 0) throw new ApiFailure(400, "Start at page 0; use its snapshotId for later pages");
            if (quotaLock.acquire() == null) throw new IllegalStateException("Snapshot quota lock missing");
            jdbc.update("DELETE FROM query_snapshot WHERE expires_at <= ?", Timestamp.from(Instant.now()));
            if (jdbc.queryForObject("SELECT count(*) FROM query_snapshot", Long.class) >= maxCount)
                throw new ApiFailure(429, "Snapshot capacity reached; reuse a snapshotId or wait for expiry");
            var limit = PageRequest.of(0, maxRecords + 1);
            rows = (historical ? history.snapshotRows(element, limit) : active.snapshotRows(element, limit))
                .stream().map(AlarmView::from).toList();
            if (rows.size() > maxRecords) throw new ApiFailure(413, "Too many matching alarms for a snapshot; filter by elementId");
            String payload = encode(rows);
            if (payload.getBytes(StandardCharsets.UTF_8).length > maxBytes)
                throw new ApiFailure(413, "Snapshot exceeds byte capacity; filter by elementId");
            token = UUID.randomUUID().toString(); expires = Instant.now().plusSeconds(ttl);
            jdbc.update("INSERT INTO query_snapshot(token,query_key,expires_at,payload) VALUES (?,?,?,?)",
                token, key, Timestamp.from(expires), payload);
        } else {
            var stored = jdbc.query("SELECT query_key, expires_at, payload FROM query_snapshot WHERE token = ?",
                (r,n) -> new Stored(r.getString(1), r.getTimestamp(2).toInstant(), r.getString(3)), token);
            if (stored.isEmpty() || !stored.get(0).expires().isAfter(Instant.now()))
                throw new ApiFailure(410, "Snapshot expired or unavailable; restart at page 0 without snapshotId");
            var value = stored.get(0);
            if (!value.key().equals(key)) throw new ApiFailure(400, "snapshotId belongs to a different query");
            expires = value.expires(); rows = decode(value.payload());
        }
        int from = (int)Math.min((long)page * size, rows.size());
        int to = Math.min(from + size, rows.size());
        return new Result(List.copyOf(rows.subList(from, to)), page, size, rows.size(), token, expires);
    }
    private record Stored(String key, Instant expires, String payload) {}
    private String encode(List<AlarmView> rows) {
        try { return json.writeValueAsString(rows); }
        catch (Exception ex) { throw new IllegalStateException("Cannot encode query snapshot", ex); }
    }
    private List<AlarmView> decode(String payload) {
        try { return json.readValue(payload, new TypeReference<List<AlarmView>>() {}); }
        catch (Exception ex) { throw new IllegalStateException("Cannot decode query snapshot", ex); }
    }
}
