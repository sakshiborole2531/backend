package com.unichem.inventory.repo;

import com.unichem.inventory.domain.BusinessUnit;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface BusinessUnitRepository extends JpaRepository<BusinessUnit, String> {
    List<BusinessUnit> findAllByOrderBySortOrderAscCodeAsc();
    List<BusinessUnit> findByActiveTrueOrderBySortOrderAscCodeAsc();
    @Query("SELECT b FROM BusinessUnit b WHERE b.active = true ORDER BY b.sortOrder ASC, b.code ASC")
    List<BusinessUnit> findByActiveTrueOrderByOrderAsc();

    @Query("SELECT b FROM BusinessUnit b WHERE b.id = :id")
    Optional<BusinessUnit> findByUnitId(@Param("id") Long id);

    @Query("SELECT b FROM BusinessUnit b WHERE LOWER(b.code) = LOWER(:code)")
    Optional<BusinessUnit> findByCodeIgnoreCase(@Param("code") String code);

    @Query("SELECT COALESCE(MAX(b.id), 0) FROM BusinessUnit b")
    Long findMaxId();
}
