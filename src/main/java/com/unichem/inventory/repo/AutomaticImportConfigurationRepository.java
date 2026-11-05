package com.unichem.inventory.repo;

import com.unichem.inventory.domain.AutomaticImportConfiguration;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AutomaticImportConfigurationRepository extends JpaRepository<AutomaticImportConfiguration, Long> {
    Optional<AutomaticImportConfiguration> findByOrganizationId(Long organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM AutomaticImportConfiguration c WHERE c.organizationId = :orgId")
    Optional<AutomaticImportConfiguration> findByOrganizationIdForUpdate(@Param("orgId") Long organizationId);

    List<AutomaticImportConfiguration> findByEnabledTrue();
}
