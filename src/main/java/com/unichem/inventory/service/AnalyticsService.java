package com.unichem.inventory.service;

import com.unichem.inventory.domain.ActionItem;
import com.unichem.inventory.domain.BusinessUnit;
import com.unichem.inventory.domain.Plant;
import com.unichem.inventory.dto.CalcItem;
import com.unichem.inventory.dto.Thresholds;
import com.unichem.inventory.repo.BusinessUnitRepository;
import com.unichem.inventory.repo.PlantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import static com.unichem.inventory.domain.Constants.*;

/** Reporting APIs: dashboard aggregates, comparisons, FIFO/FEFO compliance and the plant × category matrix. */
@Service
@RequiredArgsConstructor
public class AnalyticsService {
    private final InventoryService inventory;
    private final ActionService actions;
    private final SettingsService settings;
    private final PlantRepository plants;
    private final BusinessUnitRepository bus;
    private final CurrentUserService current;

    static double sum(Collection<CalcItem> l, ToDoubleFunction<CalcItem> f) { return l.stream().mapToDouble(f).sum(); }

    public Map<String, Object> agg(List<CalcItem> list, Thresholds t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", list.size());
        m.put("materials", list.stream().map(CalcItem::code).distinct().count());
        m.put("v", sum(list, CalcItem::value)); m.put("u", sum(list, CalcItem::uVal)); m.put("q", sum(list, CalcItem::qVal)); m.put("b", sum(list, CalcItem::bVal));
        List<CalcItem> aged = list.stream().filter(x -> x.age() != null && x.age() > t.agingDays()).toList();
        List<CalcItem> exp = list.stream().filter(x -> x.rsl() != null && x.rsl() >= 0 && x.rsl() <= t.expiryDays()).toList();
        List<CalcItem> expd = list.stream().filter(x -> x.rsl() != null && x.rsl() < 0).toList();
        List<CalcItem> cons = list.stream().filter(x -> "Consumable".equals(x.matType())).toList();
        List<CalcItem> idle = cons.stream().filter(x -> "Non-Moving".equals(x.consStatus()) || "Long Lying".equals(x.consStatus())).toList();
        List<CalcItem> exc = list.stream().filter(x -> !x.realFlags().isEmpty()).toList();
        m.put("aged", sum(aged, CalcItem::value)); m.put("agedN", aged.size());
        m.put("exp", sum(exp, CalcItem::value)); m.put("expN", exp.size());
        m.put("expd", sum(expd, CalcItem::value)); m.put("expdN", expd.size());
        m.put("cons", sum(cons, CalcItem::value)); m.put("consN", cons.size());
        m.put("idle", sum(idle, CalcItem::value)); m.put("idleN", idle.size());
        m.put("exc", exc.size()); m.put("excV", sum(exc, CalcItem::value));
        m.put("p1", exc.stream().filter(x -> "P1".equals(x.prio())).count());
        m.put("p2", exc.stream().filter(x -> "P2".equals(x.prio())).count());
        m.put("p3", exc.stream().filter(x -> "P3".equals(x.prio())).count());
        return m;
    }

    /** FIFO / FEFO: a batch fails when a later-expiring (or later-received) batch of the same material was issued after it was received. */
    public Map<Long, String> fefo(List<CalcItem> all) {
        Map<String, List<CalcItem>> g = all.stream().collect(Collectors.groupingBy(x -> x.plant() + "|" + x.code()));
        Map<Long, String> res = new HashMap<>();
        for (List<CalcItem> arr : g.values()) {
            for (CalcItem x : arr) {
                if (arr.size() < 2 || x.unrestrictedQty() <= 0 || (x.expiryDate() == null && x.receiptDate() == null)) { res.put(x.id(), "NA"); continue; }
                boolean byExp = x.expiryDate() != null;
                LocalDate avail = x.receiptDate() != null ? x.receiptDate() : LocalDate.MIN;
                boolean viol = arr.stream().anyMatch(o -> o != x && o.lastIssueDate() != null && !o.lastIssueDate().isBefore(avail)
                        && (byExp ? (o.expiryDate() != null && o.expiryDate().isAfter(x.expiryDate()))
                                  : (o.receiptDate() != null && o.receiptDate().isAfter(x.receiptDate())))
                        && (x.lastIssueDate() == null || x.lastIssueDate().isBefore(o.lastIssueDate())));
                res.put(x.id(), viol ? "FAIL" : "OK");
            }
        }
        return res;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> dashboard(InventoryService.Filter f) {
        Thresholds t = settings.thresholds();
        List<CalcItem> all = inventory.calculated(null);
        List<CalcItem> d = all.stream().filter(f::test).toList();
        Map<Long, String> fefo = fefo(all);
        String bu = InventoryService.Filter.all(f.bu()) ? "All" : f.bu();
        String plant = InventoryService.Filter.all(f.plant()) ? "All" : f.plant();
        String level = !"All".equals(plant) ? "unit" : !"All".equals(bu) ? "bu" : "org";

        List<ActionItem> acts = actions.visible().stream()
                .filter(a -> "All".equals(bu) || bu.equals(a.getBuCode()))
                .filter(a -> "All".equals(plant) || plant.equals(a.getPlant())).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", level); out.put("bu", bu); out.put("plant", plant); out.put("thresholds", t);
        out.put("totals", agg(d, t));

        // children of the current level (drill-down cards + comparison table)
        List<Map<String, Object>> kids = new ArrayList<>();
        Set<String> visible = current.visiblePlants();
        List<Plant> plantList = plants.findAllByOrderBySortOrderAscNameAsc().stream().filter(p -> visible.contains(p.getName())).toList();
        if ("org".equals(level)) {
            List<String> buCodes = new ArrayList<>();
            List<BusinessUnit> activeBus = bus.findByActiveTrueOrderByOrderAsc();
            for (BusinessUnit b : activeBus) {
                if (b.isActive() && !buCodes.contains(b.getCode())) buCodes.add(b.getCode());
            }
            plantList.stream().map(Plant::getBuCode).distinct().forEach(b -> {
                if (!buCodes.contains(b)) buCodes.add(b);
            });
            buCodes.forEach(b -> {
                String sub = plantList.stream().filter(p -> p.getBuCode().equals(b)).map(Plant::getName).collect(Collectors.joining(" · "));
                if (sub.isEmpty()) sub = "No active plants";
                kids.add(kid(b, "bu", sub, d.stream().filter(x -> b.equals(x.bu())).toList(), acts.stream().filter(a -> b.equals(a.getBuCode())).toList(), t));
            });
        } else if ("bu".equals(level)) {
            plantList.stream().filter(p -> p.getBuCode().equals(bu)).forEach(p -> kids.add(kid(p.getName(), "unit",
                    p.getSapCode() == null ? bu : "SAP " + p.getSapCode(), d.stream().filter(x -> p.getName().equals(x.plant())).toList(),
                    acts.stream().filter(a -> p.getName().equals(a.getPlant())).toList(), t)));
        } else {
            d.stream().map(x -> x.storageLoc() == null ? "Unassigned" : x.storageLoc()).distinct().forEach(l -> kids.add(kid(l, "loc", "Storage location",
                    d.stream().filter(x -> l.equals(x.storageLoc() == null ? "Unassigned" : x.storageLoc())).toList(), List.of(), t)));
            kids.sort(Comparator.comparingDouble((Map<String, Object> k) -> (double) ((Map<?, ?>) k.get("agg")).get("v")).reversed());
        }
        out.put("children", kids);

        // Visibility — category × stock status
        out.put("byCategory", MAT_TYPES.stream().map(c -> {
            List<CalcItem> r = d.stream().filter(x -> c.equals(x.matType())).toList();
            return Map.of("category", c, "short", SHORT.get(c), "n", r.size(), "u", sum(r, CalcItem::uVal), "q", sum(r, CalcItem::qVal), "b", sum(r, CalcItem::bVal), "v", sum(r, CalcItem::value));
        }).toList());
        out.put("aging", Arrays.stream(CalcService.BUCKETS).map(b -> {
            List<CalcItem> r = d.stream().filter(x -> b.equals(x.bucket())).toList();
            return Map.of("bucket", b, "n", r.size(), "v", sum(r, CalcItem::value));
        }).toList());
        List<String> bands = List.of("Expired", "≤30", "31–90", "91–" + t.expiryDays());
        out.put("expiryBands", bands.stream().map(b -> {
            List<CalcItem> r = d.stream().filter(x -> b.equals(x.band())).toList();
            return Map.of("band", b, "n", r.size(), "v", sum(r, CalcItem::value));
        }).toList());
        out.put("consumablesByDept", DEPTS.stream().map(dp -> {
            List<CalcItem> r = d.stream().filter(x -> dp.equals(x.dept())).toList();
            long idle = r.stream().filter(x -> (x.age() != null && x.age() > t.longLyingDays()) || "Non-Moving".equals(x.consStatus())).count();
            return Map.of("dept", dp, "n", r.size(), "v", sum(r, CalcItem::value), "idle", idle);
        }).toList());

        // Identification & prioritization
        Set<String> openKeys = acts.stream().filter(a -> !"Closed".equals(a.getStage())).map(ActionItem::getItemKey).filter(Objects::nonNull).collect(Collectors.toSet());
        List<Map<String, Object>> alerts = new ArrayList<>();
        alert(alerts, "Expired / expiry ≤30 days", "Critical", d, x -> x.rsl() != null && x.rsl() <= 30, "expiry");
        alert(alerts, "Blocked batches", "Critical", d, x -> x.blockedQty() > 0, "blocked");
        alert(alerts, "High-value + aged", "High", d, x -> x.age() != null && x.age() > t.agingDays() && x.value() >= t.highValue(), "hvaged");
        alert(alerts, "Blocked / restricted + aged", "High", d, x -> x.age() != null && x.age() > t.agingDays() && x.restricted(), "braged");
        alert(alerts, "Aged > " + t.agingDays() + " days", "Medium", d, x -> x.age() != null && x.age() > t.agingDays(), "aged");
        alert(alerts, "Non-moving / long-lying consumables", "Medium", d, x -> "Non-Moving".equals(x.consStatus()) || "Long Lying".equals(x.consStatus()), "idle");
        alert(alerts, "FIFO / FEFO exceptions", "Monitor", d, x -> "FAIL".equals(fefo.get(x.id())), "fefo");
        out.put("alerts", alerts);
        out.put("topExceptions", d.stream().filter(x -> !x.realFlags().isEmpty()).sorted(Comparator.comparingInt(CalcItem::score).reversed()).limit(10)
                .map(x -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("item", x); m.put("owned", openKeys.contains(x.itemKey())); return m; }).toList());
        long ok = d.stream().filter(x -> "OK".equals(fefo.get(x.id()))).count(), fail = d.stream().filter(x -> "FAIL".equals(fefo.get(x.id()))).count();
        out.put("fefo", Map.of("ok", ok, "fail", fail, "na", d.size() - ok - fail, "pct", ok + fail == 0 ? 100.0 : ok * 100.0 / (ok + fail)));

        // Ownership → Action → Closure pipeline
        long awaiting = d.stream().filter(x -> !x.realFlags().isEmpty() && !openKeys.contains(x.itemKey())).count()
                + acts.stream().filter(a -> "Prioritized".equals(a.getStage())).count();
        long closed = acts.stream().filter(a -> "Closed".equals(a.getStage())).count();
        String month = LocalDate.now().toString().substring(0, 7);
        Map<String, Object> pipe = new LinkedHashMap<>();
        pipe.put("awaiting", awaiting);
        pipe.put("owned", acts.stream().filter(a -> "Owned".equals(a.getStage())).count());
        pipe.put("inAction", acts.stream().filter(a -> "In Action".equals(a.getStage())).count());
        pipe.put("overdue", acts.stream().filter(ActionService::overdue).count());
        pipe.put("closedMtd", acts.stream().filter(a -> "Closed".equals(a.getStage()) && a.getClosedDate() != null && a.getClosedDate().toString().startsWith(month)).count());
        pipe.put("closureRate", acts.isEmpty() ? 0 : Math.round(closed * 100.0 / acts.size()));
        pipe.put("open", acts.stream().filter(a -> !"Closed".equals(a.getStage())).count());
        out.put("pipeline", pipe);
        Map<String, long[]> byFn = new TreeMap<>();
        for (ActionItem a : acts) {
            long[] c = byFn.computeIfAbsent(a.getFunctionName(), k -> new long[3]);
            if ("Closed".equals(a.getStage())) c[2]++; else { c[0]++; if (ActionService.overdue(a)) c[1]++; }
        }
        out.put("byFunction", byFn.entrySet().stream().map(e -> Map.of("fn", e.getKey(), "open", e.getValue()[0], "overdue", e.getValue()[1], "closed", e.getValue()[2])).toList());
        out.put("upcoming", acts.stream().filter(a -> !"Closed".equals(a.getStage())).limit(6).map(actions::view).toList());
        out.put("fefoStatus", fefo);
        return out;
    }

    private Map<String, Object> kid(String key, String kind, String sub, List<CalcItem> l, List<ActionItem> acts, Thresholds t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key); m.put("kind", kind); m.put("sub", sub); m.put("agg", agg(l, t));
        m.put("open", acts.stream().filter(a -> !"Closed".equals(a.getStage())).count());
        m.put("overdue", acts.stream().filter(ActionService::overdue).count());
        return m;
    }

    private void alert(List<Map<String, Object>> out, String label, String sev, List<CalcItem> d, Predicate<CalcItem> p, String key) {
        List<CalcItem> l = d.stream().filter(p).toList();
        out.add(Map.of("label", label, "severity", sev, "n", l.size(), "v", sum(l, CalcItem::value), "key", key));
    }

    // ---------------- Matrix view ----------------
    public static final Map<String, String> MEASURES = new LinkedHashMap<>();
    static {
        MEASURES.put("value", "Total stock value (₹)"); MEASURES.put("uVal", "Unrestricted value (₹)"); MEASURES.put("qVal", "Quality inspection value (₹)");
        MEASURES.put("bVal", "Blocked value (₹)"); MEASURES.put("aged", "Aged value (₹)"); MEASURES.put("exp", "Expiring value (₹)");
        MEASURES.put("expd", "Expired value (₹)"); MEASURES.put("slow", "Slow / non-moving value (₹)"); MEASURES.put("batches", "Number of batches");
        MEASURES.put("exc", "Exceptions (batches needing action)");
    }

    private ToDoubleFunction<CalcItem> measure(String m, Thresholds t) {
        return switch (m) {
            case "uVal" -> CalcItem::uVal; case "qVal" -> CalcItem::qVal; case "bVal" -> CalcItem::bVal;
            case "aged" -> x -> x.age() != null && x.age() > t.agingDays() ? x.value() : 0;
            case "exp" -> x -> x.rsl() != null && x.rsl() >= 0 && x.rsl() <= t.expiryDays() ? x.value() : 0;
            case "expd" -> x -> x.rsl() != null && x.rsl() < 0 ? x.value() : 0;
            case "slow" -> x -> !"Normal".equals(x.movement()) ? x.value() : 0;
            case "batches" -> x -> 1;
            case "exc" -> x -> x.realFlags().isEmpty() ? 0 : 1;
            default -> CalcItem::value;
        };
    }

    /** Risk share for RAG colouring: share of value (or batches) at risk for the measure. */
    private ToDoubleFunction<CalcItem> riskNum(String m, Thresholds t) {
        return switch (m) {
            case "qVal" -> CalcItem::qVal; case "bVal" -> CalcItem::bVal;
            case "aged", "exp", "expd", "slow" -> measure(m, t);
            case "batches", "exc" -> x -> x.realFlags().isEmpty() ? 0 : 1;
            default -> x -> x.realFlags().isEmpty() ? 0 : x.value();
        };
    }

    @Transactional(readOnly = true)
    public Map<String, Object> matrix(InventoryService.Filter f, String m) {
        Thresholds t = settings.thresholds();
        List<CalcItem> d = inventory.calculated(f);
        ToDoubleFunction<CalcItem> val = measure(m, t), rn = riskNum(m, t);
        ToDoubleFunction<CalcItem> rd = ("batches".equals(m) || "exc".equals(m)) ? x -> 1 : CalcItem::value;
        Set<String> visible = current.visiblePlants();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Plant p : plants.findAllByOrderBySortOrderAscNameAsc()) {
            if (!visible.contains(p.getName())) continue;
            List<CalcItem> pr = d.stream().filter(x -> p.getName().equals(x.plant())).toList();
            Map<String, Object> cells = new LinkedHashMap<>();
            for (String c : MAT_TYPES) {
                List<CalcItem> cr = pr.stream().filter(x -> c.equals(x.matType())).toList();
                cells.put(c, cell(cr, val, rn, rd));
            }
            rows.add(Map.of("plant", p.getName(), "bu", p.getBuCode(), "cells", cells, "total", cell(pr, val, rn, rd)));
        }
        Map<String, Object> colTotals = new LinkedHashMap<>();
        for (String c : MAT_TYPES) colTotals.put(c, cell(d.stream().filter(x -> c.equals(x.matType())).toList(), val, rn, rd));
        return Map.of("measure", m, "measureLabel", MEASURES.getOrDefault(m, m), "measures", MEASURES, "columns", MAT_TYPES,
                "rows", rows, "columnTotals", colTotals, "grand", cell(d, val, rn, rd));
    }

    private Map<String, Object> cell(List<CalcItem> l, ToDoubleFunction<CalcItem> val, ToDoubleFunction<CalcItem> rn, ToDoubleFunction<CalcItem> rd) {
        double n = sum(l, rn), den = sum(l, rd);
        Map<String, Object> m = new HashMap<>();
        m.put("v", sum(l, val)); m.put("share", den == 0 ? null : n / den);
        return m;
    }

    public static <K> Map<K, Double> groupSum(List<CalcItem> l, Function<CalcItem, K> k) {
        return l.stream().collect(Collectors.groupingBy(k, LinkedHashMap::new, Collectors.summingDouble(CalcItem::value)));
    }
}
