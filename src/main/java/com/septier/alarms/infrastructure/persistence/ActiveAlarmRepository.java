package com.septier.alarms.infrastructure.persistence;

import com.septier.alarms.domain.model.AlarmEvent;
import com.septier.alarms.domain.model.ActiveAlarm;
import com.septier.alarms.domain.model.Severity;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface ActiveAlarmRepository extends JpaRepository<ActiveAlarm, Long> {
    Optional<ActiveAlarm> findByElementIdAndAlarmCode(String elementId, String alarmCode);
    @Query("select a.event from ActiveAlarm a where (:element is null or a.elementId = :element) order by a.elementId, a.alarmCode")
    Page<AlarmEvent> active(@Param("element") String element, Pageable pageable);
    @Query("select a.event from ActiveAlarm a where (:element is null or a.elementId = :element) order by a.elementId, a.alarmCode")
    List<AlarmEvent> snapshotRows(@Param("element") String element, Pageable pageable);
    interface SeverityCount { Severity getSeverity(); long getTotal(); }
    @Query("select a.event.severity as severity, count(a) as total from ActiveAlarm a group by a.event.severity")
    List<SeverityCount> counts();
}
