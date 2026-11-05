package com.unichem.inventory.repo;

import com.unichem.inventory.domain.InventorySnapshot;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface InventorySnapshotRepository extends JpaRepository<InventorySnapshot, Long> {
    Optional<InventorySnapshot> findByPeriod(String period);
    List<InventorySnapshot> findAllByOrderBySnapDateAsc();
    @Query("select distinct s from InventorySnapshot s left join fetch s.rows order by s.snapDate")
    List<InventorySnapshot> findAllWithRows();
}
