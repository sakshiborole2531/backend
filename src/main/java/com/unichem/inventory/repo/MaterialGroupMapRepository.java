package com.unichem.inventory.repo;

import com.unichem.inventory.domain.MaterialGroupMap;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface MaterialGroupMapRepository extends JpaRepository<MaterialGroupMap, String> {

}
