package com.unichem.inventory.repo;

import com.unichem.inventory.domain.AuditLog;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    @Query("select a from AuditLog a where (lower(concat(a.username,' ',coalesce(a.name,''),' ',a.action,' ',coalesce(a.detail,''))) like lower(concat('%',:q,'%'))) order by a.at desc")
    org.springframework.data.domain.Page<AuditLog> search(@Param("q") String q, org.springframework.data.domain.Pageable p);
    List<AuditLog> findTop15ByUsernameOrderByAtDesc(String username);
}
