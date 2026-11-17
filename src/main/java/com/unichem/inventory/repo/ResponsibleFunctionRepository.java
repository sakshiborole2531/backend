package com.unichem.inventory.repo;

import com.unichem.inventory.domain.ResponsibleFunction;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface ResponsibleFunctionRepository extends JpaRepository<ResponsibleFunction, String> {
    List<ResponsibleFunction> findByActiveTrue();
    List<ResponsibleFunction> findAllByOrderBySortOrderAscNameAsc();
    List<ResponsibleFunction> findByActiveTrueOrderBySortOrderAscNameAsc();
    @Query("SELECT f FROM ResponsibleFunction f WHERE f.active = true ORDER BY f.sortOrder ASC, f.name ASC")
    List<ResponsibleFunction> findByActiveTrueOrderByOrderAsc();
}
