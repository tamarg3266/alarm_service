package com.septier.alarms.infrastructure.persistence;


import jakarta.persistence.*;

/** A pre-seeded database row provides a lock even when an alarm does not exist yet. */
@Entity @Table(name = "ingestion_lock")
public class IngestionLock {
    @Id private Integer id;
    protected IngestionLock() {}
}
