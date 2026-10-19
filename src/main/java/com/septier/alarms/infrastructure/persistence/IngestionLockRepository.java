package com.septier.alarms.infrastructure.persistence;


import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface IngestionLockRepository extends JpaRepository<IngestionLock, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from IngestionLock l where l.id = 1")
    IngestionLock acquire();
}
