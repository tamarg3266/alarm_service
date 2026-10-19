package com.septier.alarms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.septier.alarms.application.*;
import com.septier.alarms.application.command.AlarmRequest;
import com.septier.alarms.domain.model.*;
import com.septier.alarms.infrastructure.persistence.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"alarm.max-history-id=5", "alarm.snapshots.max-count=3", "alarm.snapshots.max-records=4"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class GapIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AlarmService service;
    @Autowired HistoryRetention retention;
    @Autowired ActiveAlarmRepository active;
    @Autowired AlarmHistoryRepository history;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() throws Exception {
        active.deleteAll(); history.deleteAll();
        jdbc.update("DELETE FROM event_receipt"); jdbc.update("DELETE FROM query_snapshot");
        jdbc.execute("ALTER TABLE alarm_history ALTER COLUMN id RESTART WITH 1");
        Path dir = Path.of(".tools/test-archives");
        if (Files.exists(dir)) try (var paths = Files.list(dir)) { for (var p : paths.toList()) Files.delete(p); }
    }
    private AlarmRequest event(String id, AlarmStatus status) {
        return new AlarmRequest(id, "EDGE-1", "CELL_DOWN", Severity.CRITICAL, status, 1L, "synthetic");
    }
    @Test void snapshotDoesNotShiftAndCanBeReadAfterUnderlyingRowsChange() throws Exception {
        service.accept(event("a", AlarmStatus.RAISED)); service.accept(event("b", AlarmStatus.RAISED));
        var result = mvc.perform(get("/api/alarms/history").param("size", "1")).andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        String token = body.get("snapshotId").asText();
        assertThat(body.get("totalElements").asInt()).isEqualTo(2);
        service.accept(event("c", AlarmStatus.CLEARED));
        mvc.perform(get("/api/alarms/history").param("snapshotId", token).param("page", "1").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].eventId").value("a"))
            .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/alarms/active").param("snapshotId", token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/alarms/history").param("page", "1")).andExpect(status().isBadRequest());
        jdbc.update("UPDATE query_snapshot SET expires_at=?", Timestamp.from(Instant.now().minusSeconds(1)));
        mvc.perform(get("/api/alarms/history").param("snapshotId", token)).andExpect(status().isGone());
    }
    @Test void snapshotCapacityAndResultSizeAreExplicitlyBounded() throws Exception {
        for (int i=0; i<3; i++) mvc.perform(get("/api/alarms/history")).andExpect(status().isOk());
        mvc.perform(get("/api/alarms/history")).andExpect(status().isTooManyRequests());
        jdbc.update("DELETE FROM query_snapshot");
        for (int i=0; i<5; i++) service.accept(event("e"+i, AlarmStatus.RAISED));
        mvc.perform(get("/api/alarms/history")).andExpect(status().isPayloadTooLarge());
    }
    @Test void archivePreservesPayloadAndReplayWithoutResurrectingState() throws Exception {
        var raise = event("raised", AlarmStatus.RAISED);
        var first = service.accept(raise); service.accept(event("clear", AlarmStatus.CLEARED));
        jdbc.update("UPDATE alarm_history SET accepted_at=?", Timestamp.from(Instant.now().minusSeconds(40L*86400)));
        assertThat(retention.archiveBatch()).isEqualTo(2);
        assertThat(history.count()).isZero(); assertThat(active.count()).isZero();
        var replay = service.accept(raise);
        assertThat(replay.duplicate()).isTrue(); assertThat(replay.historyId()).isEqualTo(first.historyId());
        assertThat(active.count()).isZero();
        Path archive;
        try (var paths = Files.list(Path.of(".tools/test-archives"))) { archive = paths.findFirst().orElseThrow(); }
        try (var stream = new GZIPInputStream(Files.newInputStream(archive))) {
            String content = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(content).contains("raised", "clear", "synthetic");
        }
    }
    @Test void activeHistoryIsNeverArchivedAndLegacyReceiptsAreBackfilled() throws Exception {
        var request = event("legacy", AlarmStatus.RAISED);
        service.accept(request); jdbc.update("DELETE FROM event_receipt");
        jdbc.update("UPDATE alarm_history SET accepted_at=?", Timestamp.from(Instant.now().minusSeconds(40L*86400)));
        assertThat(retention.archiveBatch()).isZero();
        assertThat(service.accept(request).duplicate()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_receipt", Long.class)).isEqualTo(1);
    }
    @Test void archiveFilesystemFailurePreservesDatabaseHistory() throws Exception {
        service.accept(event("old", AlarmStatus.RAISED)); service.accept(event("clear", AlarmStatus.CLEARED));
        jdbc.update("UPDATE alarm_history SET accepted_at=?", Timestamp.from(Instant.now().minusSeconds(40L*86400)));
        Path dir = Path.of(".tools/test-archives");
        Files.deleteIfExists(dir); Files.writeString(dir, "directory unavailable");
        try {
            assertThatThrownBy(() -> retention.archiveBatch()).isInstanceOf(java.io.IOException.class);
            assertThat(history.count()).isEqualTo(2);
        } finally { Files.deleteIfExists(dir); Files.createDirectories(dir); }
    }
    @Test void exhaustedLifetimeQuotaRejectsAtomicallyButStillAllowsReplay() throws Exception {
        var old = event("first", AlarmStatus.RAISED); service.accept(old);
        jdbc.execute("ALTER TABLE alarm_history ALTER COLUMN id RESTART WITH 6");
        mvc.perform(post("/api/alarms").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(event("over-quota", AlarmStatus.RAISED))))
            .andExpect(status().isInsufficientStorage());
        assertThat(history.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_receipt", Long.class)).isEqualTo(1);
        assertThat(service.accept(old).duplicate()).isTrue();
    }
    @Test void nulDescriptionIsRejectedBeforeDatabaseAccess() throws Exception {
        var request = new AlarmRequest("nul", "EDGE-1", "CELL_DOWN", Severity.MAJOR, AlarmStatus.RAISED, 1L, "bad\0text");
        mvc.perform(post("/api/alarms").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
            .andExpect(status().isBadRequest());
        assertThat(history.count()).isZero();
    }
    @Test void simultaneousRaiseAndClearHaveAValidSerializedOutcome() throws Exception {
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var raise = pool.submit(() -> { start.await(); return service.accept(event("raise", AlarmStatus.RAISED)); });
            var clear = pool.submit(() -> { start.await(); try { service.accept(event("clear", AlarmStatus.CLEARED)); return true; }
                catch (com.septier.alarms.domain.exception.AlarmConflict ex) { return false; } });
            start.countDown(); raise.get(10, TimeUnit.SECONDS); boolean cleared = clear.get(10, TimeUnit.SECONDS);
            assertThat(history.count()).isEqualTo(cleared ? 2 : 1);
            assertThat(active.count()).isEqualTo(cleared ? 0 : 1);
        } finally { start.countDown(); pool.shutdownNow(); }
    }
}
