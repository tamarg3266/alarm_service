package com.septier.alarms;

import com.septier.alarms.application.AlarmService;
import com.septier.alarms.application.command.AlarmRequest;
import com.septier.alarms.domain.model.*;
import com.septier.alarms.domain.exception.AlarmConflict;
import com.septier.alarms.infrastructure.persistence.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class AlarmIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AlarmService service;
    @Autowired ActiveAlarmRepository active;
    @Autowired AlarmHistoryRepository history;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach void clean() {
        active.deleteAll(); history.deleteAll();
        jdbc.update("DELETE FROM event_receipt"); jdbc.update("DELETE FROM query_snapshot");
    }
    private AlarmRequest event(String id, String element, AlarmStatus status, long timestamp) {
        return new AlarmRequest(id, element, "CELL_DOWN", Severity.CRITICAL, status, timestamp, "Synthetic alarm");
    }
    private org.springframework.test.web.servlet.ResultActions postEvent(AlarmRequest e) throws Exception {
        return mvc.perform(post("/api/alarms").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(e)));
    }
    @Test void raiseUpdateClearAndReplayKeepHistoryAndStateConsistent() throws Exception {
        var raised = event("e1", "BTS-1", AlarmStatus.RAISED, 100);
        postEvent(raised).andExpect(status().isCreated()).andExpect(jsonPath("$.duplicate").value(false));
        postEvent(raised).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        // Lower timestamp still wins: database acceptance order is the explicit policy.
        postEvent(event("e2", "BTS-1", AlarmStatus.RAISED, 50)).andExpect(status().isCreated());
        mvc.perform(get("/api/alarms/active")).andExpect(jsonPath("$.content[0].eventId").value("e2"));
        var cleared = event("e3", "BTS-1", AlarmStatus.CLEARED, 20);
        postEvent(cleared).andExpect(status().isCreated());
        postEvent(cleared).andExpect(status().isOk());
        // Replaying an old raise must never resurrect the alarm.
        postEvent(raised).andExpect(status().isOk());
        assertThat(active.count()).isZero();
        assertThat(history.count()).isEqualTo(3);
        mvc.perform(get("/api/alarms/statistics")).andExpect(jsonPath("$.CRITICAL").value(0))
            .andExpect(jsonPath("$.INFO").value(0));
    }
    @Test void unknownClearAndReusedIdAreAtomicConflicts() throws Exception {
        postEvent(event("missing", "BTS-1", AlarmStatus.CLEARED, 1)).andExpect(status().isConflict());
        assertThat(history.count()).isZero();
        postEvent(event("e1", "BTS-1", AlarmStatus.RAISED, 1)).andExpect(status().isCreated());
        postEvent(event("e1", "BTS-2", AlarmStatus.RAISED, 1)).andExpect(status().isConflict());
        assertThat(history.count()).isEqualTo(1);
        assertThat(active.count()).isEqualTo(1);
    }
    @Test void filteredPaginationAndStatistics() throws Exception {
        postEvent(event("e1", "BTS-1", AlarmStatus.RAISED, 1));
        postEvent(event("e2", "BTS-2", AlarmStatus.RAISED, 2));
        mvc.perform(get("/api/alarms/elements/BTS-1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].elementId").value("BTS-1"));
        mvc.perform(get("/api/alarms/history").param("elementId", "BTS-2").param("size", "1"))
            .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].eventId").value("e2"));
        mvc.perform(get("/api/alarms/statistics")).andExpect(jsonPath("$.CRITICAL").value(2));
    }
    @Test void invalidInputIsRejected() throws Exception {
        var valid = json.writeValueAsString(event("e1", "BTS-1", AlarmStatus.RAISED, 1));
        for (String body : List.of("{}", "{", valid + " {}", valid.replace("\"eventId\":\"e1\"", "\"eventId\":\"e1\",\"eventId\":\"e2\""), valid.replace("CRITICAL", "INVALID"),
                valid.replace("\"timestamp\":1", "\"timestamp\":1.9"),
                valid.replace("\"CRITICAL\"", "0"), valid.replace("\"timestamp\":1", "\"timestamp\":\"1\""),
                valid.replace("\"timestamp\":1", "\"timestamp\":-1"), valid.replace("\"eventId\":\"e1\"", "\"eventId\":\" \""),
                valid.replace("Synthetic alarm", "x".repeat(2049)), valid.substring(0, valid.length()-1) + ",\"extra\":1}")) {
            mvc.perform(post("/api/alarms").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/alarms/active").param("size", "501")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/alarms/history").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/alarms").contentType(MediaType.APPLICATION_JSON).content("x".repeat(16385)))
            .andExpect(status().isPayloadTooLarge());
        assertThat(history.count()).isZero();
    }
    @Test void simultaneousDuplicateRequestsCreateExactlyOneEvent() throws Exception {
        var event = event("same", "BTS-1", AlarmStatus.RAISED, 1);
        var results = race(12, i -> service.accept(event));
        assertThat(results.stream().filter(r -> !r.duplicate()).count()).isEqualTo(1);
        assertThat(history.count()).isEqualTo(1);
        assertThat(active.count()).isEqualTo(1);
    }
    @Test void simultaneousRaisesPreserveHistoryAndSingleActiveState() throws Exception {
        race(12, i -> service.accept(event("e" + i, "BTS-1", AlarmStatus.RAISED, i)));
        assertThat(history.count()).isEqualTo(12);
        assertThat(active.count()).isEqualTo(1);
        var latest = history.findAll().stream().max(Comparator.comparing(AlarmEvent::getId)).orElseThrow();
        assertThat(service.active(null, org.springframework.data.domain.PageRequest.of(0, 10)).getContent().get(0).getId())
            .isEqualTo(latest.getId());
    }
    @Test void simultaneousClearsAcceptOnlyOneAndDoNotRecordRejectedEvents() throws Exception {
        service.accept(event("raise", "BTS-1", AlarmStatus.RAISED, 1));
        var results = race(8, i -> {
            try { service.accept(event("clear" + i, "BTS-1", AlarmStatus.CLEARED, 2)); return true; }
            catch (AlarmConflict ex) { return false; }
        });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(history.count()).isEqualTo(2);
        assertThat(active.count()).isZero();
    }
    private <T> List<T> race(int count, java.util.function.IntFunction<T> operation) throws Exception {
        var executor = Executors.newFixedThreadPool(count);
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException(); return operation.apply(index); }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> result = new ArrayList<>();
            for (var future : futures) result.add(future.get(30, TimeUnit.SECONDS));
            return result;
        } finally { start.countDown(); executor.shutdownNow(); }
    }
}
