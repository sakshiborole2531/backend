package com.unichem.inventory.repo;

import com.unichem.inventory.domain.Plant;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface PlantRepository extends JpaRepository<Plant, Long> {
    Optional<Plant> findByName(String name);
    Optional<Plant> findBySapCode(String sapCode);
    List<Plant> findAllByOrderBySortOrderAscNameAsc();
    List<Plant> findByActiveTrueOrderBySortOrderAscNameAsc();
    @Query("SELECT p FROM Plant p WHERE p.active = true ORDER BY p.sortOrder ASC, p.name ASC")
    List<Plant> findByActiveTrueOrderByOrderAsc();
    List<Plant> findByBusinessUnitIdOrderBySortOrderAscNameAsc(Long businessUnitId);
    boolean existsByBusinessUnitId(Long businessUnitId);
}
