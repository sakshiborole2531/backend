package com.unichem.inventory.repo;

import com.unichem.inventory.domain.InventoryItem;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {
    Optional<InventoryItem> findByPlantAndCodeAndBatch(String plant, String code, String batch);
    @Modifying @Query("delete from InventoryItem i where i.plant in :plants")
    int deleteByPlantIn(@Param("plants") Collection<String> plants);
}
