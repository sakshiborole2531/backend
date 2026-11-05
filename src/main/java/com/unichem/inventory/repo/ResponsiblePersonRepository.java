package com.unichem.inventory.repo;

import com.unichem.inventory.domain.ResponsiblePerson;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface ResponsiblePersonRepository extends JpaRepository<ResponsiblePerson, Long> {
    Optional<ResponsiblePerson> findByNameIgnoreCase(String name);
}
