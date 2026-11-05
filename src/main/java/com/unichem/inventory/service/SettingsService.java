package com.unichem.inventory.service;

import com.unichem.inventory.domain.AppSetting;
import com.unichem.inventory.dto.Thresholds;
import com.unichem.inventory.repo.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SettingsService {
    private final AppSettingRepository repo;

    public Map<String, String> all() {
        Map<String, String> m = new TreeMap<>();
        repo.findAll().forEach(s -> m.put(s.getKey(), s.getValue()));
        return m;
    }

    public String get(String key, String def) { return repo.findById(key).map(AppSetting::getValue).orElse(def); }
    public int getInt(String key, int def) { try { return Integer.parseInt(get(key, String.valueOf(def))); } catch (NumberFormatException e) { return def; } }

    public Thresholds thresholds() {
        Map<String, String> m = all();
        java.util.function.BiFunction<String, Integer, Integer> i = (k, d) -> { try { return Integer.parseInt(m.getOrDefault(k, "" + d)); } catch (Exception e) { return d; } };
        double hv; try { hv = Double.parseDouble(m.getOrDefault("highValue", "500000")); } catch (Exception e) { hv = 500000; }
        return new Thresholds(i.apply("expiryDays", 180), i.apply("agingDays", 180), i.apply("longLyingDays", 365),
                i.apply("nonMovingDays", 180), i.apply("slowMovingDays", 90), i.apply("consAgingDays", 90),
                i.apply("qualityHoldDays", 30), hv, i.apply("fyStart", 4));
    }

    private static final Set<String> NUMERIC = Set.of("expiryDays", "agingDays", "longLyingDays", "nonMovingDays", "slowMovingDays",
            "consAgingDays", "qualityHoldDays", "highValue", "fyStart", "policy.minLen", "policy.maxFail", "policy.lockMin", "policy.idleMin");

    @Transactional
    public Map<String, String> update(Map<String, String> values, String by) {
        values.forEach((k, v) -> {
            if (v == null) return;
            if (NUMERIC.contains(k)) {
                double d;
                try { d = Double.parseDouble(v); } catch (NumberFormatException e) { throw new IllegalArgumentException(k + " must be a number"); }
                if (d <= 0) throw new IllegalArgumentException(k + " must be greater than zero");
                if (k.equals("fyStart") && !(d == 1 || d == 4)) throw new IllegalArgumentException("fyStart must be 1 (January) or 4 (April)");
            }
            AppSetting s = repo.findById(k).orElseGet(() -> { AppSetting n = new AppSetting(); n.setKey(k); return n; });
            s.setValue(v.trim()); s.setUpdatedAt(OffsetDateTime.now()); s.setUpdatedBy(by);
            repo.save(s);
        });
        return all();
    }
}
