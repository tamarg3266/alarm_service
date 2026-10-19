package com.septier.alarms.api;

import com.septier.alarms.api.dto.AlarmView;

import com.septier.alarms.application.AlarmService;
import com.septier.alarms.application.command.AlarmRequest;
import com.septier.alarms.domain.model.AlarmEvent;
import com.septier.alarms.domain.model.Severity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.data.domain.*;
import java.util.*;

@RestController @RequestMapping("/api/alarms") @Validated
public class AlarmController {
    private final AlarmService service;
    private final com.septier.alarms.application.QuerySnapshots snapshots;
    public AlarmController(AlarmService service, com.septier.alarms.application.QuerySnapshots snapshots) { this.service = service; this.snapshots = snapshots; }
    public record Accepted(String eventId, long historyId, boolean duplicate) {}
    @PostMapping
    public ResponseEntity<Accepted> accept(@Valid @RequestBody AlarmRequest request) {
        var result = service.accept(request);
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
            .body(new Accepted(result.eventId(), result.historyId(), result.duplicate()));
    }
    @GetMapping("/active")
    public com.septier.alarms.application.QuerySnapshots.Result active(@RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
                         @RequestParam(defaultValue = "100") @Min(1) @Max(500) int size,
                         @RequestParam(required = false) @Pattern(regexp = "[0-9a-fA-F-]{36}") String snapshotId) {
        return snapshots.read(false, null, page, size, snapshotId);
    }
    @GetMapping("/elements/{elementId}")
    public com.septier.alarms.application.QuerySnapshots.Result element(@PathVariable @Pattern(regexp = "[A-Za-z0-9_.:-]{1,128}") String elementId,
                          @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
                          @RequestParam(defaultValue = "100") @Min(1) @Max(500) int size,
                         @RequestParam(required = false) @Pattern(regexp = "[0-9a-fA-F-]{36}") String snapshotId) {
        return snapshots.read(false, elementId, page, size, snapshotId);
    }
    @GetMapping("/history")
    public com.septier.alarms.application.QuerySnapshots.Result history(@RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9_.:-]{1,128}") String elementId,
                          @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
                          @RequestParam(defaultValue = "100") @Min(1) @Max(500) int size,
                         @RequestParam(required = false) @Pattern(regexp = "[0-9a-fA-F-]{36}") String snapshotId) {
        return snapshots.read(true, elementId, page, size, snapshotId);
    }
    @GetMapping("/statistics") public Map<Severity, Long> statistics() { return service.statistics(); }
}
