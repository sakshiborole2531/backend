package com.unichem.inventory.repo;

import com.unichem.inventory.domain.ActionType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface ActionTypeRepository extends JpaRepository<ActionType, String> {
    List<ActionType> findByActiveTrue();
    List<ActionType> findAllByOrderBySortOrderAscNameAsc();
    List<ActionType> findByActiveTrueOrderBySortOrderAscNameAsc();
    @Query("SELECT a FROM ActionType a WHERE a.active = true ORDER BY a.sortOrder ASC, a.name ASC")
    List<ActionType> findByActiveTrueOrderByOrderAsc();
    @Query("SELECT at.name FROM ActionType at WHERE at.active = true ORDER BY at.sortOrder ASC, at.name ASC")
    List<String> findAllActiveActionTypeNames();
}
