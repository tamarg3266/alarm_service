package com.septier.alarms.infrastructure.persistence;

import com.septier.alarms.domain.model.AlarmEvent;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface AlarmHistoryRepository extends JpaRepository<AlarmEvent, Long> {
    Optional<AlarmEvent> findByEventId(String eventId);
    @Query("select e from AlarmEvent e where (:element is null or e.elementId = :element) order by e.id desc")
    Page<AlarmEvent> history(@Param("element") String element, Pageable pageable);
    @Query("select e from AlarmEvent e where (:element is null or e.elementId = :element) order by e.id desc")
    java.util.List<AlarmEvent> snapshotRows(@Param("element") String element, Pageable pageable);
}
