package com.unichem.inventory.repo;

import com.unichem.inventory.domain.MaterialGroupMap;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface MaterialGroupMapRepository extends JpaRepository<MaterialGroupMap, String> {
    List<MaterialGroupMap> findByActiveTrue();
    List<MaterialGroupMap> findAllByOrderBySortOrderAscSapCodeAsc();
    List<MaterialGroupMap> findByActiveTrueOrderBySortOrderAscSapCodeAsc();
    @Query("SELECT g FROM MaterialGroupMap g WHERE g.active = true ORDER BY g.sortOrder ASC, g.sapCode ASC")
    List<MaterialGroupMap> findByActiveTrueOrderByOrderAsc();
}
