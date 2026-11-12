package com.unichem.inventory.service;

import com.unichem.inventory.domain.InventoryItem;
import com.unichem.inventory.domain.InventorySnapshot;
import com.unichem.inventory.domain.SnapshotRow;
import com.unichem.inventory.dto.CalcItem;
import com.unichem.inventory.dto.Thresholds;
import com.unichem.inventory.repo.InventoryItemRepository;
import com.unichem.inventory.repo.InventorySnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Year-wise tracking: one aggregated snapshot per month, compared across financial years. */
@Service
@RequiredArgsConstructor
public class SnapshotService {
    private final InventorySnapshotRepository repo;
    private final InventoryItemRepository inventory;
    private final CalcService calc;
    private final SettingsService settings;
    private final CurrentUserService current;

    public String fyOf(LocalDate d) {
        int fs = settings.thresholds().fyStart();
        int y = d.getYear(), s = d.getMonthValue() >= fs ? y : y - 1;
        return fs == 1 ? String.valueOf(y) : s + "-" + String.valueOf(s + 1).substring(2);
    }

    /** Imported / manual snapshots win over automatic ones within the same month. */
    @Transactional
    public boolean take(String source, LocalDate asOf, List<CalcItem> list) {
        LocalDate d = asOf == null ? LocalDate.now() : asOf;
        String period = d.toString().substring(0, 7);
        Optional<InventorySnapshot> ex = repo.findByPeriod(period);
        if (ex.isPresent() && "auto".equals(source) && Set.of("import", "summary", "manual").contains(ex.get().getSource()) && !ex.get().getSnapDate().equals(LocalDate.now()))
            return false;
        Thresholds t = settings.thresholds();
        if (list == null) {
            List<InventoryItem> all = inventory.findAll();
            if (all.isEmpty()) return false;
            list = all.stream().map(i -> calc.calc(i, t, d)).toList();
        }
        if (!"sample".equals(source)) repo.findAll().stream().filter(s -> "sample".equals(s.getSource())).forEach(repo::delete);
        ex.ifPresent(s -> { repo.delete(s); repo.flush(); });
        InventorySnapshot s = new InventorySnapshot();
        s.setPeriod(period); s.setSnapDate(d); s.setFy(fyOf(d)); s.setSource(source);
        Map<String, SnapshotRow> rows = new LinkedHashMap<>();
        for (CalcItem x : list) {
            String k = x.bu() + "|" + x.plant() + "|" + x.matType() + "|" + (x.dept() == null ? "" : x.dept());
            SnapshotRow r = rows.computeIfAbsent(k, kk -> { SnapshotRow n = new SnapshotRow(); n.setSnapshot(s); n.setBuCode(x.bu()); n.setPlant(x.plant()); n.setCategory(x.matType()); n.setDepartment(x.dept()); return n; });
            r.setBatches(r.getBatches() + 1);
            r.setValU(add(r.getValU(), x.uVal())); r.setValQ(add(r.getValQ(), x.qVal())); r.setValB(add(r.getValB(), x.bVal()));
            if (x.age() != null && x.age() > t.agingDays()) r.setValAged(add(r.getValAged(), x.value()));
            if (x.rsl() != null && x.rsl() >= 0 && x.rsl() <= t.expiryDays()) r.setValExp(add(r.getValExp(), x.value()));
            if (x.rsl() != null && x.rsl() < 0) r.setValExpd(add(r.getValExpd(), x.value()));
            if ("Non-Moving".equals(x.consStatus()) || "Long Lying".equals(x.consStatus())) r.setValIdle(add(r.getValIdle(), x.value()));
        }
        s.getRows().addAll(rows.values());
        repo.save(s);
        return true;
    }

    private static BigDecimal add(BigDecimal a, double v) { return a.add(BigDecimal.valueOf(Math.round(v * 100) / 100.0)); }

    /** Daily at 01:15 — make sure the current month has a snapshot. */
    @Scheduled(cron = "0 15 1 * * *")
    @Transactional
    public void ensureMonthly() {
        if (repo.findByPeriod(LocalDate.now().toString().substring(0, 7)).isEmpty()) take("auto", null, null);
    }

    /** Year-wise comparison within the user's data scope (closing = last snapshot in FY, avg = mean of month-ends). */
    @Transactional(readOnly = true)
    public Map<String, Object> yearwise(String basis, String bu, String plant) {
        if (bu != null && !bu.isBlank() && !"All".equalsIgnoreCase(bu.trim())) {
            current.requireBuScope(bu);
        }
        if (plant != null && !plant.isBlank() && !"All".equalsIgnoreCase(plant.trim())) {
            current.requireScope(plant);
        }
        Set<String> allowed = current.allowedPlants();
        Set<String> inactive = current.inactivePlants();
        List<InventorySnapshot> all = repo.findAllWithRows();
        Map<String, List<InventorySnapshot>> byFy = new TreeMap<>();
        all.forEach(s -> byFy.computeIfAbsent(s.getFy(), k -> new ArrayList<>()).add(s));
        List<Map<String, Object>> months = new ArrayList<>();
        for (InventorySnapshot s : all) {
            Map<String, Object> m = metrics(filter(s.getRows(), allowed, inactive, bu, plant));
            m.put("period", s.getPeriod()); m.put("date", s.getSnapDate()); m.put("fy", s.getFy()); m.put("source", s.getSource());
            months.add(m);
        }
        List<Map<String, Object>> years = new ArrayList<>();
        String curFy = fyOf(LocalDate.now());
        for (var e : byFy.entrySet()) {
            List<InventorySnapshot> list = e.getValue();
            Map<String, Object> m;
            if ("avg".equals(basis)) {
                List<Map<String, Object>> ms = list.stream().map(s -> metrics(filter(s.getRows(), allowed, inactive, bu, plant))).toList();
                m = average(ms);
            } else m = metrics(filter(list.get(list.size() - 1).getRows(), allowed, inactive, bu, plant));
            m.put("fy", e.getKey()); m.put("date", list.get(list.size() - 1).getSnapDate()); m.put("months", list.size()); m.put("partial", e.getKey().equals(curFy));
            years.add(m);
        }
        return Map.of("years", years, "months", months, "basis", basis == null ? "close" : basis);
    }

    private List<SnapshotRow> filter(List<SnapshotRow> rows, Set<String> allowed, Set<String> inactive, String bu, String plant) {
        return rows.stream().filter(r -> !inactive.contains(r.getPlant()) && (allowed == null || allowed.contains(r.getPlant()))
                && (bu == null || "All".equals(bu) || bu.equals(r.getBuCode())) && (plant == null || "All".equals(plant) || plant.equals(r.getPlant()))).toList();
    }

    private Map<String, Object> metrics(List<SnapshotRow> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        double v = 0, u = 0, q = 0, b = 0, aged = 0, exp = 0, expd = 0, idle = 0, cons = 0; int n = 0;
        Map<String, Double> cat = new LinkedHashMap<>(), dept = new LinkedHashMap<>(), bu = new LinkedHashMap<>(), plant = new LinkedHashMap<>();
        for (SnapshotRow r : rows) {
            double rv = r.getValU().doubleValue() + r.getValQ().doubleValue() + r.getValB().doubleValue();
            v += rv; u += r.getValU().doubleValue(); q += r.getValQ().doubleValue(); b += r.getValB().doubleValue();
            aged += r.getValAged().doubleValue(); exp += r.getValExp().doubleValue(); expd += r.getValExpd().doubleValue(); idle += r.getValIdle().doubleValue(); n += r.getBatches();
            if ("Consumable".equals(r.getCategory())) { cons += rv; if (r.getDepartment() != null) dept.merge(r.getDepartment(), rv, Double::sum); }
            cat.merge(r.getCategory(), rv, Double::sum); bu.merge(r.getBuCode(), rv, Double::sum); plant.merge(r.getPlant(), rv, Double::sum);
        }
        m.put("v", v); m.put("u", u); m.put("q", q); m.put("b", b); m.put("aged", aged); m.put("exp", exp); m.put("expd", expd);
        m.put("idle", idle); m.put("cons", cons); m.put("n", n); m.put("cat", cat); m.put("dept", dept); m.put("bu", bu); m.put("plant", plant);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> average(List<Map<String, Object>> ms) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("v", "u", "q", "b", "aged", "exp", "expd", "idle", "cons", "n"))
            out.put(k, ms.stream().mapToDouble(m -> ((Number) m.get(k)).doubleValue()).average().orElse(0));
        for (String g : List.of("cat", "dept", "bu", "plant")) {
            Map<String, Double> acc = new LinkedHashMap<>();
            ms.forEach(m -> ((Map<String, Double>) m.get(g)).forEach((k, v) -> acc.merge(k, v / ms.size(), Double::sum)));
            out.put(g, acc);
        }
        return out;
    }

    @Transactional
    public void delete(String period) { repo.findByPeriod(period).ifPresent(repo::delete); }
}
