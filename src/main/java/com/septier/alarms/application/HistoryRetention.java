package com.septier.alarms.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.septier.alarms.api.dto.AlarmView;
import com.septier.alarms.infrastructure.persistence.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

@Service
public class HistoryRetention {
    private final AlarmHistoryRepository history;
    private final IngestionLockRepository maintenanceLock;
    private final EventReceipts receipts;
    private final KeyLocks locks;
    private final EventFingerprint fingerprints;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Path directory;
    private final int days, batchSize;
    private final long maxBytes;
    public HistoryRetention(AlarmHistoryRepository history, IngestionLockRepository maintenanceLock,
            EventReceipts receipts, KeyLocks locks, EventFingerprint fingerprints, JdbcTemplate jdbc, ObjectMapper json,
            @Value("${alarm.retention.days}") int days, @Value("${alarm.retention.batch-size}") int batchSize,
            @Value("${alarm.retention.archive-directory}") String directory,
            @Value("${alarm.retention.max-archive-bytes}") long maxBytes) {
        this.history = history; this.maintenanceLock = maintenanceLock; this.receipts = receipts; this.locks = locks;
        this.fingerprints = fingerprints; this.jdbc = jdbc; this.json = json;
        this.days = days; this.batchSize = batchSize; this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }
    /** Archive-before-delete: a crash can duplicate an export, but cannot lose the only history copy. */
    @Transactional(timeout = 30, rollbackFor = Exception.class)
    public int archiveBatch() throws IOException {
        if (days < 1 || batchSize < 1 || batchSize > 5000) throw new IllegalStateException("Invalid retention policy");
        if (maintenanceLock.acquire() == null) throw new IllegalStateException("Maintenance lock missing");
        jdbc.update("DELETE FROM query_snapshot WHERE expires_at <= ?", Timestamp.from(Instant.now()));
        var ids = jdbc.query("SELECT h.id FROM alarm_history h WHERE h.accepted_at < ? "
            + "AND NOT EXISTS (SELECT 1 FROM active_alarm a WHERE a.history_id=h.id) "
            + "ORDER BY h.id LIMIT ? FOR UPDATE SKIP LOCKED",
            (r,n) -> r.getLong(1), Timestamp.from(Instant.now().minus(Duration.ofDays(days))), batchSize);
        if (ids.isEmpty()) return 0;
        var events = history.findAllById(ids);
        Files.createDirectories(directory);
        List<Path> files;
        try (var paths = Files.list(directory)) { files = paths.filter(Files::isRegularFile).toList(); }
        long used = 0;
        for (var file : files) used = Math.addExact(used, Files.size(file));
        if (files.size() >= 10000 || used >= maxBytes) throw new IOException("Archive capacity reached; offload archives before further retention");
        Path temp = Files.createTempFile(directory, "pending-", ".tmp");
        boolean moved = false;
        try {
            try (var gzip = new GZIPOutputStream(Files.newOutputStream(temp));
                 var writer = new BufferedWriter(new OutputStreamWriter(gzip, StandardCharsets.UTF_8))) {
                for (var event : events) {
                    locks.acquire("event:" + event.getEventId());
                    writer.write(json.writeValueAsString(AlarmView.from(event))); writer.newLine();
                    receipts.insert(event.getEventId(), event.getId(), fingerprints.of(event));
                }
            }
            if (Files.size(temp) > maxBytes - used) throw new IOException("Archive byte capacity reached");
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temp, directory.resolve("history-" + UUID.randomUUID() + ".jsonl.gz"), StandardCopyOption.ATOMIC_MOVE);
            moved = true;
            history.deleteAllInBatch(events);
            return events.size();
        } finally { if (!moved) Files.deleteIfExists(temp); }
    }
}
