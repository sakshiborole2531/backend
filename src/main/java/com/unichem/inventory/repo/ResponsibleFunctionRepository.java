package com.unichem.inventory.repo;

import com.unichem.inventory.domain.ResponsibleFunction;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface ResponsibleFunctionRepository extends JpaRepository<ResponsibleFunction, String> {
    List<ResponsibleFunction> findAllByOrderBySortOrderAscNameAsc();
}
