package com.unichem.inventory.dto;

import java.time.LocalDate;
import java.util.List;

/** An inventory record with every system-generated parameter computed as of a date. */
public record CalcItem(
        Long id, String bu, String plant, String matType, String dept, String subCat, String code, String name,
        String batch, String uom, String storageLoc,
        double unrestrictedQty, double qualityQty, double blockedQty, double unitCost,
        LocalDate receiptDate, LocalDate lyingSince, LocalDate mfgDate, LocalDate expiryDate, String dateType,
        LocalDate lastIssueDate, String blockReason, String remarks,
        double qty, double value, double uVal, double qVal, double bVal,
        LocalDate lying, Integer age, Integer rsl, Integer dsi, Integer idle, Integer slTotal, Integer slUsed,
        String movement, String bucket, String band, List<String> statuses, boolean restricted, String consStatus,
        List<String> flags, int score, String prio, String itemKey) {

    public List<String> realFlags() { return flags.stream().filter(f -> !"HV".equals(f)).toList(); }
}
