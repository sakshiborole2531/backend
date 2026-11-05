package com.unichem.inventory.repo;

import com.unichem.inventory.domain.MaterialTypeMap;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface MaterialTypeMapRepository extends JpaRepository<MaterialTypeMap, String> {
    List<MaterialTypeMap> findAllByOrderBySortOrderAscSapCodeAsc();
    List<MaterialTypeMap> findByActiveTrueOrderBySortOrderAscSapCodeAsc();
    @Query("SELECT m FROM MaterialTypeMap m WHERE m.active = true ORDER BY m.sortOrder ASC, m.sapCode ASC")
    List<MaterialTypeMap> findByActiveTrueOrderByOrderAsc();
}
