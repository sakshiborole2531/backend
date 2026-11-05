package com.unichem.inventory.repo;

import com.unichem.inventory.domain.ActionType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface ActionTypeRepository extends JpaRepository<ActionType, String> {
    List<ActionType> findAllByOrderBySortOrderAscNameAsc();
}
