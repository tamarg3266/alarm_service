package com.septier.alarms.application;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component @EnableScheduling
@ConditionalOnProperty(name = "alarm.retention.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(RetentionScheduler.class);
    private final HistoryRetention retention;
    public RetentionScheduler(HistoryRetention retention) { this.retention = retention; }
    @Scheduled(fixedDelayString = "${alarm.retention.interval-ms}", initialDelayString = "${alarm.retention.interval-ms}")
    public void run() {
        try { int count = retention.archiveBatch(); if (count > 0) LOG.info("Archived {} history events", count); }
        catch (Exception ex) { LOG.error("History archival failed; database rows preserved. Operator action required: {}", ex.getClass().getSimpleName()); }
    }
}
