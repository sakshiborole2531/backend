package com.unichem.inventory.repo;

import com.unichem.inventory.domain.InventoryItem;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {
    Optional<InventoryItem> findByPlantAndCodeAndBatch(String plant, String code, String batch);
    @Modifying @Query("delete from InventoryItem i where i.plant in :plants")
    int deleteByPlantIn(@Param("plants") Collection<String> plants);

    @Query("SELECT COUNT(b) FROM InventoryItem b WHERE b.unrestrictedQty > 0")
    long countUnrestrictedBatches();

    @Query("SELECT COUNT(b) FROM InventoryItem b WHERE b.qualityQty > 0")
    long countQualityBatches();

    @Query("SELECT COUNT(b) FROM InventoryItem b WHERE b.blockedQty > 0")
    long countBlockedBatches();

    @Query("SELECT COUNT(b) FROM InventoryItem b WHERE b.unrestrictedQty > 0 AND b.plant IN :plants")
    long countUnrestrictedBatchesByPlantIn(@Param("plants") Collection<String> plants);

    @Query("SELECT COUNT(b) FROM InventoryItem b WHERE b.qualityQty > 0 AND b.plant IN :plants")
    long countQualityBatchesByPlantIn(@Param("plants") Collection<String> plants);

    @Query("SELECT COUNT(b) FROM InventoryItem b WHERE b.blockedQty > 0 AND b.plant IN :plants")
    long countBlockedBatchesByPlantIn(@Param("plants") Collection<String> plants);
}
