package com.unichem.inventory.dto;

/** Exception thresholds that drive identification, prioritization and every KPI. */
public record Thresholds(int expiryDays, int agingDays, int longLyingDays, int nonMovingDays, int slowMovingDays,
                         int consAgingDays, int qualityHoldDays, double highValue, int fyStart) {}
