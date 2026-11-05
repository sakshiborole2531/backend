package com.unichem.inventory.repo;

import com.unichem.inventory.domain.AppRole;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface AppRoleRepository extends JpaRepository<AppRole, String> {

}
