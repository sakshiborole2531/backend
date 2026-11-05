package com.unichem.inventory.repo;

import com.unichem.inventory.domain.SyncLog;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface SyncLogRepository extends JpaRepository<SyncLog, Long> {
    List<SyncLog> findTop30ByOrderByAtDesc();
}
