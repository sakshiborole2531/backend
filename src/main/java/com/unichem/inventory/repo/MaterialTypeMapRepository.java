package com.unichem.inventory.repo;

import com.unichem.inventory.domain.MaterialTypeMap;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface MaterialTypeMapRepository extends JpaRepository<MaterialTypeMap, Long> {
    List<MaterialTypeMap> findAllByOrderBySortOrderAscSapCodeAsc();
    List<MaterialTypeMap> findByActiveTrueOrderBySortOrderAscSapCodeAsc();
    @Query("SELECT m FROM MaterialTypeMap m WHERE m.active = true ORDER BY m.sortOrder ASC, m.sapCode ASC")
    List<MaterialTypeMap> findByActiveTrueOrderByOrderAsc();

    @Query("SELECT m FROM MaterialTypeMap m WHERE LOWER(m.sapCode) = LOWER(:code)")
    List<MaterialTypeMap> findAllBySapCodeIgnoreCase(@Param("code") String code);

    @Query("SELECT m FROM MaterialTypeMap m WHERE LOWER(m.sapCode) = LOWER(:code)")
    Optional<MaterialTypeMap> findBySapCodeIgnoreCase(@Param("code") String code);

    @Query("SELECT m FROM MaterialTypeMap m WHERE LOWER(m.sapCode) = LOWER(:code) " +
            "AND LOWER(m.category) = LOWER(:category) " +
            "AND ((:businessUnit IS NULL AND (m.businessUnit IS NULL OR TRIM(m.businessUnit) = '')) " +
            "     OR LOWER(COALESCE(m.businessUnit, '')) = LOWER(COALESCE(:businessUnit, '')))")
    Optional<MaterialTypeMap> findByCompositeKey(
            @Param("code") String code,
            @Param("category") String category,
            @Param("businessUnit") String businessUnit);
}
