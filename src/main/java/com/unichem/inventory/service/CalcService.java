package com.unichem.inventory.service;

import com.unichem.inventory.domain.InventoryItem;
import com.unichem.inventory.dto.CalcItem;
import com.unichem.inventory.dto.Thresholds;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Calculation & exception engine — a faithful port of the dashboard's calc():
 * inventory age, remaining shelf life, movement, aging bucket, expiry band, consumable status,
 * exception flags (Identification) and risk score / P1-P3 (Prioritization).
 */
@Service
public class CalcService {

    public static final String[] BUCKETS = {"0–30", "31–90", "91–180", "181–365", ">365"};

    public static String bucketOf(Integer age) {
        if (age == null) return null;
        int v = Math.max(0, age);
        if (v <= 30) return BUCKETS[0];
        if (v <= 90) return BUCKETS[1];
        if (v <= 180) return BUCKETS[2];
        if (v <= 365) return BUCKETS[3];
        return BUCKETS[4];
    }

    public static String bandOf(Integer rsl, int expiryDays) {
        if (rsl == null) return null;
        if (rsl < 0) return "Expired";
        if (rsl <= 30) return "≤30";
        if (rsl <= 90) return "31–90";
        if (rsl <= expiryDays) return "91–" + expiryDays;
        return "Beyond";
    }

    private static Integer days(LocalDate from, LocalDate to) {
        return from == null || to == null ? null : (int) ChronoUnit.DAYS.between(from, to);
    }

    private static double d(BigDecimal b) { return b == null ? 0 : b.doubleValue(); }

    public CalcItem calc(InventoryItem it, Thresholds t) { return calc(it, t, LocalDate.now()); }

    public CalcItem calc(InventoryItem it, Thresholds t, LocalDate asOf) {
        double u = d(it.getUnrestrictedQty()), q = d(it.getQualityQty()), b = d(it.getBlockedQty()), c = d(it.getUnitCost());
        double qty = u + q + b;
        LocalDate lying = it.getLyingSince() != null ? it.getLyingSince() : it.getReceiptDate();
        Integer age = days(lying, asOf);                        // Inventory Age = today − Lying Since (GR posting)
        Integer rsl = days(asOf, it.getExpiryDate());           // Remaining shelf life = SLED/BBD − today
        Integer dsi = days(it.getLastIssueDate(), asOf);        // Days since last consumption
        Integer idle = dsi != null ? dsi : age;                 // no consumption → idle since GR
        Integer slTotal = days(it.getMfgDate(), it.getExpiryDate());
        Integer slUsed = null;
        if (slTotal != null && slTotal > 0) {
            slUsed = (int) Math.max(0, Math.min(100, Math.round(days(it.getMfgDate(), asOf) * 100.0 / slTotal)));
        }
        String movement = idle == null ? "Normal" : idle > t.nonMovingDays() ? "Non-moving" : idle > t.slowMovingDays() ? "Slow-moving" : "Normal";
        List<String> statuses = new ArrayList<>();
        if (u > 0) statuses.add("Unrestricted");
        if (q > 0) statuses.add("Quality");
        if (b > 0) statuses.add("Blocked");
        boolean restricted = q > 0 || b > 0;
        double value = qty * c;

        String consStatus = null;
        boolean cons = "Consumable".equals(it.getMatType());
        if (cons) {
            consStatus = "Non-moving".equals(movement) ? "Non-Moving"
                    : (age != null && age > t.longLyingDays()) ? "Long Lying"
                    : (age != null && age > t.consAgingDays()) ? "Aging" : "Normal";
        }

        // Identification — exception flags
        List<String> f = new ArrayList<>();
        if (rsl != null && rsl < 0) f.add("EXPD"); else if (rsl != null && rsl <= t.expiryDays()) f.add("EXP");
        if (b > 0) f.add("BLK");
        if (q > 0 && age != null && age > t.qualityHoldDays()) f.add("QH");
        if ("Non-moving".equals(movement)) f.add("NM"); else if ("Slow-moving".equals(movement)) f.add("SLOW");
        if (cons && age != null && age > t.longLyingDays()) f.add("LL");
        if (age != null && age > t.agingDays()) f.add("AGED");
        if (!f.isEmpty() && value >= t.highValue()) f.add("HV");

        // Prioritization — risk score (urgency × value) → P1 / P2 / P3
        double s = 0;
        if (f.contains("EXPD")) s += 60;
        if (f.contains("EXP")) s += rsl <= 30 ? 50 : rsl <= 90 ? 35 : 20;
        if (f.contains("BLK")) s += 25;
        if (f.contains("QH")) s += 10;
        if (f.contains("NM")) s += 22;
        if (f.contains("SLOW")) s += 8;
        if (f.contains("LL")) s += 15;
        if (f.contains("AGED")) s += age > 365 ? 25 : 15;
        if (f.contains("BLK") && f.contains("AGED")) s += 10;
        if (s > 0) s += Math.min(40, value / t.highValue() * 15);
        int score = (int) Math.round(s);
        String prio = s == 0 ? null : s >= 70 ? "P1" : s >= 40 ? "P2" : "P3";

        return new CalcItem(it.getId(), it.getBuCode(), it.getPlant(), it.getMatType(), it.getDepartment(), it.getSubCategory(),
                it.getCode(), it.getName(), it.getBatch(), it.getUom(), it.getStorageLoc(), u, q, b, c,
                it.getReceiptDate(), it.getLyingSince(), it.getMfgDate(), it.getExpiryDate(), it.getDateType(),
                it.getLastIssueDate(), it.getBlockReason(), it.getRemarks(),
                qty, value, u * c, q * c, b * c, lying, age, rsl, dsi, idle, slTotal, slUsed, movement,
                bucketOf(age), bandOf(rsl, t.expiryDays()), statuses, restricted, consStatus, f, score, prio, it.itemKey());
    }

    public static String flagLabel(String f, Thresholds t) {
        return switch (f) {
            case "EXPD" -> "Expired";
            case "EXP" -> "Expiry ≤" + t.expiryDays() + "d";
            case "BLK" -> "Blocked";
            case "QH" -> "Quality hold >" + t.qualityHoldDays() + "d";
            case "NM" -> "Non-moving >" + t.nonMovingDays() + "d";
            case "SLOW" -> "Slow-moving >" + t.slowMovingDays() + "d";
            case "LL" -> "Lying >" + t.longLyingDays() + "d";
            case "AGED" -> "Aged >" + t.agingDays() + "d";
            case "HV" -> "High value";
            default -> f;
        };
    }
}
