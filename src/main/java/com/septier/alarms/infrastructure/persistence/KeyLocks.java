package com.septier.alarms.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Durable exact-key locks. Always acquire event first, then element; never the reverse. */
@Component
public class KeyLocks {
    private final JdbcTemplate jdbc;
    public KeyLocks(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void acquire(String key) {
        jdbc.update("INSERT INTO ingestion_key_lock(lock_key) VALUES (?) ON CONFLICT DO NOTHING", key);
        jdbc.queryForObject("SELECT lock_key FROM ingestion_key_lock WHERE lock_key = ? FOR UPDATE", String.class, key);
    }
}
