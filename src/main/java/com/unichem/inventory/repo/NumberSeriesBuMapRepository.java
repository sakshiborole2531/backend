package com.unichem.inventory.repo;

import com.unichem.inventory.domain.NumberSeriesBuMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NumberSeriesBuMapRepository extends JpaRepository<NumberSeriesBuMap, Long> {
    List<NumberSeriesBuMap> findAllByOrderBySortOrderAscSeriesPatternAsc();
    List<NumberSeriesBuMap> findByActiveTrueOrderBySortOrderAscSeriesPatternAsc();
    Optional<NumberSeriesBuMap> findBySeriesPatternIgnoreCaseAndBusinessUnitIgnoreCaseAndActiveTrue(String seriesPattern, String businessUnit);
}
