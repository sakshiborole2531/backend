package com.unichem.inventory.repo;

import com.unichem.inventory.domain.StorageLocation;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface StorageLocationRepository extends JpaRepository<StorageLocation, Long> {
    List<StorageLocation> findAllByOrderByCodeAsc();
    List<StorageLocation> findAllByOrderBySortOrderAscCodeAsc();
    List<StorageLocation> findByActiveTrueOrderBySortOrderAscCodeAsc();
    @Query("SELECT s FROM StorageLocation s WHERE s.active = true ORDER BY s.sortOrder ASC, s.code ASC")
    List<StorageLocation> findByActiveTrueOrderByOrderAsc();
}
