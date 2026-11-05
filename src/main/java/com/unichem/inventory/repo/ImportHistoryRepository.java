package com.unichem.inventory.repo;

import com.unichem.inventory.domain.ImportHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportHistoryRepository extends JpaRepository<ImportHistory, Long> {
    Page<ImportHistory> findAllByOrderByImportStartedAtDesc(Pageable pageable);
    Page<ImportHistory> findByConfigurationIdOrderByImportStartedAtDesc(Long configurationId, Pageable pageable);
    List<ImportHistory> findTop50ByOrderByImportStartedAtDesc();
}
