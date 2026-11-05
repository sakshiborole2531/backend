package com.unichem.inventory.repo;

import com.unichem.inventory.domain.AccessRequest;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface AccessRequestRepository extends JpaRepository<AccessRequest, Long> {
    List<AccessRequest> findAllByOrderByRequestedAtDesc();
    boolean existsByUsernameIgnoreCaseAndStatus(String username, String status);
}
