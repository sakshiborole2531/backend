package com.unichem.inventory.service;

import com.unichem.inventory.domain.ActionItem;
import com.unichem.inventory.domain.BusinessUnit;
import com.unichem.inventory.domain.Plant;
import com.unichem.inventory.dto.CalcItem;
import com.unichem.inventory.dto.Dtos.*;
import com.unichem.inventory.dto.Thresholds;
import com.unichem.inventory.repo.BusinessUnitRepository;
import com.unichem.inventory.repo.PlantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import static com.unichem.inventory.domain.Constants.*;

/**
 * Multi-level inventory reporting & aggregation service (Business Unit-wise and Unit-wise)
 * for instant live preview JSON and A4 Landscape PDF report generation.
 */
@Service
@RequiredArgsConstructor
public class ReportService {
    private final InventoryService inventoryService;
    private final ActionService actionService;
    private final SettingsService settingsService;
    private final SnapshotService snapshotService;
    private final BusinessUnitRepository buRepo;
    private final PlantRepository plantRepo;
    private final CurrentUserService currentUser;
    private final AuditService auditService;

    public static final List<String> ALL_SECTIONS = List.of(
            "summary", "status", "aging", "expiry", "slow", "cons", "years", "actions", "detail"
    );

    @Transactional(readOnly = true)
    public ReportSummaryResponseDto summarize(ReportFilterRequest req) {
        ReportFilterRequest safeReq = req != null ? req : new ReportFilterRequest(
                "both", "both", "All", "All", null, null, null, null, null, null,
                "all", null, null, null, null, null, null, null, null, 10, null
        );

        String repType = normalizeReportType(safeReq.reportType() != null ? safeReq.reportType() : safeReq.repType());
        String dataScope = "filtered".equalsIgnoreCase(safeReq.dataScope()) ? "filtered" : "all";
        int topN = safeReq.topN() != null && safeReq.topN() > 0 ? Math.min(500, safeReq.topN()) : 10;
        Map<String, Boolean> enabledSections = resolveSections(safeReq.sections(), safeReq.sectionList());

        // Resolve BU selection
        List<String> requestedBus = resolveRequestedBus(safeReq);
        for (String b : requestedBus) {
            if (!"All".equalsIgnoreCase(b)) {
                currentUser.requireBuScope(b);
            }
        }
        String selectedBu = requestedBus.size() == 1 ? requestedBus.get(0) : "All";

        // Resolve Plant/Unit selection
        List<String> requestedUnits = resolveRequestedUnits(safeReq);
        for (String u : requestedUnits) {
            if (!"All".equalsIgnoreCase(u)) {
                currentUser.requireScope(u);
            }
        }

        // Load calculated inventory items (applying dashboard filters if dataScope == "filtered" or explicit filter params provided)
        InventoryService.Filter baseFilter = "filtered".equals(dataScope) || hasExplicitAttributeFilters(safeReq)
                ? new InventoryService.Filter(
                        null, null,
                        safeReq.cat(), safeReq.status(), safeReq.dept(), safeReq.loc(), safeReq.q(), safeReq.minValue())
                : null;

        List<CalcItem> allVisibleItems = inventoryService.calculated(baseFilter);

        // Filter items by selected BUs and selected Units
        Set<String> buFilterSet = requestedBus.stream()
                .filter(b -> b != null && !b.isBlank() && !"All".equalsIgnoreCase(b))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> unitFilterSet = requestedUnits.stream()
                .filter(u -> u != null && !u.isBlank() && !"All".equalsIgnoreCase(u))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<CalcItem> filteredItems = allVisibleItems.stream()
                .filter(x -> buFilterSet.isEmpty() || buFilterSet.stream().anyMatch(b -> b.equalsIgnoreCase(x.bu())))
                .filter(x -> unitFilterSet.isEmpty() || unitFilterSet.stream().anyMatch(u -> u.equalsIgnoreCase(x.plant())))
                .toList();

        Thresholds t = settingsService.thresholds();
        Map<String, String> allSettings = settingsService.all();
        List<ActionItem> visibleActions = actionService.visible();

        // Master data for BUs and Plants within user's visible scope
        Set<String> visiblePlantNames = currentUser.visiblePlants();
        Set<String> allowedBuCodes = currentUser.allowedBusinessUnits();
        List<BusinessUnit> buMasters = buRepo.findAllByOrderBySortOrderAscCodeAsc();
        Map<String, BusinessUnit> buMasterMap = new LinkedHashMap<>();
        for (BusinessUnit b : buMasters) {
            buMasterMap.put(b.getCode(), b);
        }

        List<Plant> plantMasters = plantRepo.findAllByOrderBySortOrderAscNameAsc().stream()
                .filter(p -> p.isActive() && visiblePlantNames.contains(p.getName()))
                .toList();
        Map<String, Plant> plantMasterMap = new LinkedHashMap<>();
        for (Plant p : plantMasters) {
            plantMasterMap.put(p.getName(), p);
        }

        // Determine active BUs to display in summary
        List<String> activeBus = determineActiveBus(buFilterSet, buMasters, allowedBuCodes, plantMasters, filteredItems);

        // Determine active Units to display in summary
        List<String> activeUnits = determineActiveUnits(unitFilterSet, activeBus, plantMasters, filteredItems);

        // 1. Build Business Unit-wise summary rows
        List<BuSummaryRowDto> buSummaryRows = new ArrayList<>();
        for (String buCode : activeBus) {
            List<CalcItem> buItems = filteredItems.stream().filter(x -> buCode.equalsIgnoreCase(x.bu())).toList();
            Set<String> buPlantsInItems = buItems.stream().map(CalcItem::plant).filter(Objects::nonNull).collect(Collectors.toSet());
            Set<String> buActiveUnits = activeUnits.stream()
                    .filter(u -> {
                        Plant p = plantMasterMap.get(u);
                        return p != null ? buCode.equalsIgnoreCase(p.getBuCode()) : buPlantsInItems.contains(u);
                    })
                    .collect(Collectors.toSet());

            ReportActionCountsDto ac = computeActionCounts(visibleActions, a ->
                    buCode.equalsIgnoreCase(a.getBuCode())
                            && (unitFilterSet.isEmpty() || (a.getPlant() != null && buActiveUnits.contains(a.getPlant())))
                            && (buPlantsInItems.isEmpty() && unitFilterSet.isEmpty()
                                || (a.getPlant() != null && (buPlantsInItems.contains(a.getPlant()) || buActiveUnits.contains(a.getPlant()))))
            );
            ReportMetricsDto agg = computeMetrics(buItems, t, ac);
            BusinessUnit bm = buMasterMap.get(buCode);
            String desc = bm != null && bm.getName() != null && !bm.getName().isBlank()
                    ? bm.getName()
                    : ("API".equalsIgnoreCase(buCode) ? "Active Pharmaceutical Ingredients" : buCode);
            int unitsCount = !buPlantsInItems.isEmpty() ? buPlantsInItems.size() : buActiveUnits.size();

            buSummaryRows.add(new BuSummaryRowDto(buCode, desc, desc, unitsCount, agg, ac));
        }

        // 2. Build Unit-wise summary rows grouped by BU (with BU subtotals and Grand Total)
        List<UnitSummaryRowDto> unitSummaryRows = new ArrayList<>();
        for (String buCode : activeBus) {
            List<String> bUnits = activeUnits.stream()
                    .filter(u -> {
                        Plant p = plantMasterMap.get(u);
                        if (p != null) return buCode.equalsIgnoreCase(p.getBuCode());
                        return filteredItems.stream().anyMatch(x -> u.equalsIgnoreCase(x.plant()) && buCode.equalsIgnoreCase(x.bu()));
                    })
                    .toList();

            for (String unitName : bUnits) {
                List<CalcItem> uItems = filteredItems.stream().filter(x -> unitName.equalsIgnoreCase(x.plant())).toList();
                Plant pm = plantMasterMap.get(unitName);
                String sapCode = pm != null && pm.getSapCode() != null ? pm.getSapCode() : "";
                String city = pm != null && pm.getCity() != null ? pm.getCity() : "";
                ReportActionCountsDto uAc = computeActionCounts(visibleActions, a -> unitName.equalsIgnoreCase(a.getPlant()));
                ReportMetricsDto uAgg = computeMetrics(uItems, t, uAc);
                unitSummaryRows.add(new UnitSummaryRowDto("unit", buCode, unitName, sapCode, sapCode, city, uAgg, uAc));
            }

            if (bUnits.size() > 1 || (buFilterSet.isEmpty() && !bUnits.isEmpty())) {
                List<CalcItem> buItems = filteredItems.stream()
                        .filter(x -> buCode.equalsIgnoreCase(x.bu()) && bUnits.stream().anyMatch(u -> u.equalsIgnoreCase(x.plant())))
                        .toList();
                Set<String> bUnitSet = new HashSet<>(bUnits);
                ReportActionCountsDto subAc = computeActionCounts(visibleActions, a ->
                        buCode.equalsIgnoreCase(a.getBuCode()) && a.getPlant() != null && bUnitSet.contains(a.getPlant()));
                ReportMetricsDto subAgg = computeMetrics(buItems, t, subAc);
                unitSummaryRows.add(new UnitSummaryRowDto("sub", buCode, buCode + " subtotal", "", "", "", subAgg, subAc));
            }
        }

        // 3. Compute overall Grand Total summary
        Set<String> allUnitNamesInScope = new HashSet<>(activeUnits);
        filteredItems.stream().map(CalcItem::plant).filter(Objects::nonNull).forEach(allUnitNamesInScope::add);
        ReportActionCountsDto totalAc = computeActionCounts(visibleActions, a ->
                (buFilterSet.isEmpty() || (a.getBuCode() != null && buFilterSet.contains(a.getBuCode())))
                        && (a.getPlant() != null && allUnitNamesInScope.contains(a.getPlant()))
        );
        ReportMetricsDto totalAgg = computeMetrics(filteredItems, t, totalAc);
        int totalUnitsCount = (int) filteredItems.stream().map(CalcItem::plant).filter(Objects::nonNull).distinct().count();
        if (totalUnitsCount == 0) totalUnitsCount = activeUnits.size();
        ReportTotalSummaryDto totalSummary = new ReportTotalSummaryDto(activeBus.size(), totalUnitsCount, totalAgg, totalAc);

        if (buFilterSet.isEmpty() && !unitSummaryRows.isEmpty()) {
            unitSummaryRows.add(new UnitSummaryRowDto("total", "", "Grand total", "", "", "", totalAgg, totalAc));
        }

        // 4. Optional sections for instant preview / PDF
        List<Map<String, Object>> byCategory = Boolean.TRUE.equals(enabledSections.get("status"))
                ? buildCategoryBreakdown(filteredItems)
                : List.of();

        List<Map<String, Object>> agingBuckets = Boolean.TRUE.equals(enabledSections.get("aging"))
                ? buildAgingBreakdown(filteredItems)
                : List.of();

        List<CalcItem> expiryBatches = Boolean.TRUE.equals(enabledSections.get("expiry"))
                ? filteredItems.stream()
                        .filter(x -> x.rsl() != null && x.rsl() <= t.expiryDays())
                        .sorted(Comparator.comparingInt(CalcItem::rsl).thenComparing(Comparator.comparingDouble(CalcItem::value).reversed()))
                        .limit(topN)
                        .toList()
                : List.of();

        List<CalcItem> slowMovingBatches = Boolean.TRUE.equals(enabledSections.get("slow"))
                ? filteredItems.stream()
                        .filter(x -> (x.age() != null && x.age() > t.agingDays()) || !"Normal".equals(x.movement()))
                        .sorted(Comparator.comparingDouble(CalcItem::value).reversed())
                        .limit(topN)
                        .toList()
                : List.of();

        List<Map<String, Object>> consumablesByDept = Boolean.TRUE.equals(enabledSections.get("cons"))
                ? buildConsumablesByDept(filteredItems, t)
                : List.of();

        Map<String, Object> yearwise = Boolean.TRUE.equals(enabledSections.get("years"))
                ? snapshotService.yearwise("close", selectedBu, requestedUnits.size() == 1 ? requestedUnits.get(0) : "All")
                : Map.of();

        List<Map<String, Object>> openActions = Boolean.TRUE.equals(enabledSections.get("actions"))
                ? visibleActions.stream()
                        .filter(a -> !"Closed".equals(a.getStage()))
                        .filter(a -> buFilterSet.isEmpty() || (a.getBuCode() != null && buFilterSet.contains(a.getBuCode())))
                        .filter(a -> allUnitNamesInScope.isEmpty() || (a.getPlant() != null && allUnitNamesInScope.contains(a.getPlant())))
                        .limit(topN)
                        .map(actionService::view)
                        .toList()
                : List.of();

        List<Map<String, Object>> unitDetails = Boolean.TRUE.equals(enabledSections.get("detail"))
                ? buildUnitDetails(activeUnits, plantMasterMap, filteredItems, visibleActions, t, topN)
                : List.of();

        String preparer = safeReq.preparedBy() != null && !safeReq.preparedBy().isBlank()
                ? safeReq.preparedBy().trim()
                : currentUser.username().flatMap(u -> Optional.ofNullable(currentUser.require().getName())).orElse("System");

        return new ReportSummaryResponseDto(
                repType,
                selectedBu,
                activeBus,
                requestedUnits,
                dataScope,
                preparer,
                OffsetDateTime.now().toString(),
                allSettings.getOrDefault("companyName", "Unichem Laboratories Ltd."),
                allSettings.getOrDefault("companyShort", "UNICHEM"),
                allSettings.getOrDefault("sync.source", "SAP Stock"),
                t,
                activeBus,
                activeUnits,
                enabledSections,
                topN,
                totalSummary,
                buSummaryRows,
                unitSummaryRows,
                byCategory,
                agingBuckets,
                expiryBatches,
                slowMovingBatches,
                consumablesByDept,
                yearwise,
                openActions,
                unitDetails
        );
    }

    public ReportMetricsDto computeMetrics(List<CalcItem> list, Thresholds t, ReportActionCountsDto ac) {
        int n = list.size();
        int mats = (int) list.stream().map(CalcItem::code).filter(Objects::nonNull).distinct().count();
        double v = sum(list, CalcItem::value);
        double u = sum(list, CalcItem::uVal);
        double q = sum(list, CalcItem::qVal);
        double b = sum(list, CalcItem::bVal);
        int uN = (int) list.stream().filter(x -> x.unrestrictedQty() > 0).count();
        int qN = (int) list.stream().filter(x -> x.qualityQty() > 0).count();
        int bN = (int) list.stream().filter(x -> x.blockedQty() > 0).count();

        List<CalcItem> agedList = list.stream().filter(x -> x.age() != null && x.age() > t.agingDays()).toList();
        List<CalcItem> expList = list.stream().filter(x -> x.rsl() != null && x.rsl() >= 0 && x.rsl() <= t.expiryDays()).toList();
        List<CalcItem> expdList = list.stream().filter(x -> x.rsl() != null && x.rsl() < 0).toList();
        List<CalcItem> consList = list.stream().filter(x -> "Consumable".equals(x.matType())).toList();
        List<CalcItem> idleList = consList.stream().filter(x -> "Non-Moving".equals(x.consStatus()) || "Long Lying".equals(x.consStatus())).toList();
        List<CalcItem> excList = list.stream().filter(x -> !x.realFlags().isEmpty()).toList();

        double agedVal = sum(agedList, CalcItem::value);
        double expVal = sum(expList, CalcItem::value);
        double expdVal = sum(expdList, CalcItem::value);
        double consVal = sum(consList, CalcItem::value);
        double idleVal = sum(idleList, CalcItem::value);
        double excVal = sum(excList, CalcItem::value);

        int p1 = (int) excList.stream().filter(x -> "P1".equals(x.prio())).count();
        int p2 = (int) excList.stream().filter(x -> "P2".equals(x.prio())).count();
        int p3 = (int) excList.stream().filter(x -> "P3".equals(x.prio())).count();

        int openAct = ac != null ? ac.open() : 0;
        int odAct = ac != null ? ac.overdue() : 0;
        int totAct = ac != null ? ac.total() : 0;

        return new ReportMetricsDto(
                n, n, n,
                mats, mats,
                v, v,
                u, u, uN, uN,
                q, q, qN, qN,
                b, b, bN, bN,
                agedVal, agedVal, agedList.size(), agedList.size(),
                expVal, expVal, expList.size(), expList.size(),
                expdVal, expdVal, expdList.size(), expdList.size(),
                consVal, consVal, consList.size(), consList.size(),
                idleVal, idleVal, idleList.size(), idleList.size(),
                excList.size(), excList.size(),
                excVal, excVal,
                p1, p2, p3,
                openAct, odAct, totAct
        );
    }

    private ReportActionCountsDto computeActionCounts(List<ActionItem> actions, Predicate<ActionItem> filter) {
        List<ActionItem> matched = actions.stream().filter(filter).toList();
        int open = (int) matched.stream().filter(a -> !"Closed".equals(a.getStage())).count();
        int closed = (int) matched.stream().filter(a -> "Closed".equals(a.getStage())).count();
        int od = (int) matched.stream().filter(ActionService::overdue).count();
        return new ReportActionCountsDto(open, od, od, closed, matched.size());
    }

    private static double sum(Collection<CalcItem> items, ToDoubleFunction<CalcItem> fn) {
        return items.stream().mapToDouble(fn).sum();
    }

    private List<Map<String, Object>> buildCategoryBreakdown(List<CalcItem> items) {
        return MAT_TYPES.stream().map(c -> {
            List<CalcItem> r = items.stream().filter(x -> c.equals(x.matType())).toList();
            long uCount = r.stream().filter(x -> x.unrestrictedQty() > 0).count();
            long qCount = r.stream().filter(x -> x.qualityQty() > 0).count();
            long bCount = r.stream().filter(x -> x.blockedQty() > 0).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", c);
            m.put("short", SHORT.getOrDefault(c, c));
            m.put("n", r.size());
            m.put("batchCount", r.size());
            m.put("u", sum(r, CalcItem::uVal));
            m.put("uN", uCount);
            m.put("unrestrictedBatchCount", uCount);
            m.put("q", sum(r, CalcItem::qVal));
            m.put("qN", qCount);
            m.put("qualityBatchCount", qCount);
            m.put("b", sum(r, CalcItem::bVal));
            m.put("bN", bCount);
            m.put("blockedBatchCount", bCount);
            m.put("v", sum(r, CalcItem::value));
            return m;
        }).toList();
    }

    private List<Map<String, Object>> buildAgingBreakdown(List<CalcItem> items) {
        return Arrays.stream(CalcService.BUCKETS).map(b -> {
            List<CalcItem> r = items.stream().filter(x -> b.equals(x.bucket())).toList();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bucket", b);
            m.put("n", r.size());
            m.put("v", sum(r, CalcItem::value));
            return m;
        }).toList();
    }

    private List<Map<String, Object>> buildConsumablesByDept(List<CalcItem> items, Thresholds t) {
        List<CalcItem> cons = items.stream().filter(x -> "Consumable".equals(x.matType())).toList();
        return DEPTS.stream().map(dp -> {
            List<CalcItem> r = cons.stream().filter(x -> dp.equals(x.dept())).toList();
            long idle = r.stream().filter(x -> (x.age() != null && x.age() > t.longLyingDays()) || "Non-Moving".equals(x.consStatus())).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dept", dp);
            m.put("n", r.size());
            m.put("v", sum(r, CalcItem::value));
            m.put("idle", idle);
            return m;
        }).toList();
    }

    private List<Map<String, Object>> buildUnitDetails(
            List<String> activeUnits,
            Map<String, Plant> plantMasterMap,
            List<CalcItem> filteredItems,
            List<ActionItem> visibleActions,
            Thresholds t,
            int topN) {
        List<Map<String, Object>> details = new ArrayList<>();
        for (String unit : activeUnits) {
            List<CalcItem> uItems = filteredItems.stream().filter(x -> unit.equalsIgnoreCase(x.plant())).toList();
            Plant pm = plantMasterMap.get(unit);
            ReportActionCountsDto uAc = computeActionCounts(visibleActions, a -> unit.equalsIgnoreCase(a.getPlant()));
            ReportMetricsDto uAgg = computeMetrics(uItems, t, uAc);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("unit", unit);
            d.put("bu", pm != null ? pm.getBuCode() : uItems.stream().map(CalcItem::bu).findFirst().orElse(""));
            d.put("sapCode", pm != null && pm.getSapCode() != null ? pm.getSapCode() : "");
            d.put("city", pm != null && pm.getCity() != null ? pm.getCity() : "");
            d.put("agg", uAgg);
            d.put("ac", uAc);
            d.put("byCategory", buildCategoryBreakdown(uItems));
            d.put("agingBuckets", buildAgingBreakdown(uItems));
            d.put("topExceptions", uItems.stream()
                    .filter(x -> !x.realFlags().isEmpty())
                    .sorted(Comparator.comparingInt(CalcItem::score).reversed())
                    .limit(topN)
                    .toList());
            details.add(d);
        }
        return details;
    }

    private static String normalizeReportType(String raw) {
        if (raw == null || raw.isBlank()) return "both";
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if ("bu".equals(lower) || "unit".equals(lower) || "both".equals(lower)) return lower;
        return "both";
    }

    private static boolean hasExplicitAttributeFilters(ReportFilterRequest req) {
        return notAll(req.cat()) || notAll(req.status()) || notAll(req.dept()) || notAll(req.loc())
                || (req.q() != null && !req.q().isBlank()) || req.minValue() != null;
    }

    private static boolean notAll(String s) {
        return s != null && !s.isBlank() && !"All".equalsIgnoreCase(s.trim());
    }

    private static List<String> resolveRequestedBus(ReportFilterRequest req) {
        List<String> out = new ArrayList<>();
        if (req.bus() != null) req.bus().stream().filter(ReportService::notAll).map(String::trim).forEach(out::add);
        if (req.selectedBus() != null) req.selectedBus().stream().filter(ReportService::notAll).map(String::trim).forEach(out::add);
        if (notAll(req.selectedBu())) out.add(req.selectedBu().trim());
        if (notAll(req.bu())) out.add(req.bu().trim());
        return out.stream().distinct().toList();
    }

    private static List<String> resolveRequestedUnits(ReportFilterRequest req) {
        List<String> out = new ArrayList<>();
        if (req.units() != null) req.units().stream().filter(ReportService::notAll).map(String::trim).forEach(out::add);
        if (req.selectedUnits() != null) req.selectedUnits().stream().filter(ReportService::notAll).map(String::trim).forEach(out::add);
        if (req.plants() != null) req.plants().stream().filter(ReportService::notAll).map(String::trim).forEach(out::add);
        if (notAll(req.plant())) out.add(req.plant().trim());
        return out.stream().distinct().toList();
    }

    private static Map<String, Boolean> resolveSections(Map<String, Boolean> mapInput, List<String> listInput) {
        Map<String, Boolean> resolved = new LinkedHashMap<>();
        for (String s : ALL_SECTIONS) {
            resolved.put(s, true);
        }
        if (listInput != null && !listInput.isEmpty()) {
            Set<String> enabled = listInput.stream()
                    .filter(Objects::nonNull)
                    .flatMap(s -> Arrays.stream(s.split(",")))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            for (String s : ALL_SECTIONS) {
                resolved.put(s, enabled.contains(s));
            }
        }
        if (mapInput != null && !mapInput.isEmpty()) {
            mapInput.forEach((k, v) -> {
                if (k != null && v != null) {
                    resolved.put(k.trim().toLowerCase(Locale.ROOT), v);
                }
            });
        }
        return resolved;
    }

    private static List<String> determineActiveBus(
            Set<String> buFilterSet,
            List<BusinessUnit> buMasters,
            Set<String> allowedBuCodes,
            List<Plant> plantMasters,
            List<CalcItem> filteredItems) {
        if (!buFilterSet.isEmpty()) {
            return new ArrayList<>(buFilterSet);
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (BusinessUnit b : buMasters) {
            if (b.isActive() && (allowedBuCodes == null || allowedBuCodes.stream().anyMatch(a -> a.equalsIgnoreCase(b.getCode())))) {
                ordered.add(b.getCode());
            }
        }
        for (Plant p : plantMasters) {
            if (p.getBuCode() != null && !p.getBuCode().isBlank()) {
                ordered.add(p.getBuCode());
            }
        }
        for (CalcItem item : filteredItems) {
            if (item.bu() != null && !item.bu().isBlank()) {
                ordered.add(item.bu());
            }
        }
        return new ArrayList<>(ordered);
    }

    private static List<String> determineActiveUnits(
            Set<String> unitFilterSet,
            List<String> activeBus,
            List<Plant> plantMasters,
            List<CalcItem> filteredItems) {
        if (!unitFilterSet.isEmpty()) {
            return new ArrayList<>(unitFilterSet);
        }
        Set<String> activeBuSet = activeBus.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Set<String> units = new LinkedHashSet<>();
        for (Plant p : plantMasters) {
            if (p.getBuCode() != null && activeBuSet.contains(p.getBuCode().toLowerCase(Locale.ROOT))) {
                units.add(p.getName());
            }
        }
        for (CalcItem x : filteredItems) {
            if (x.plant() != null && !x.plant().isBlank()) {
                units.add(x.plant());
            }
        }
        return new ArrayList<>(units);
    }

    // =====================================================================
    // Server-Side A4 Landscape PDF Generator (Zero external dependency PDF 1.4)
    // =====================================================================

    @Transactional(readOnly = true)
    public byte[] generatePdf(ReportFilterRequest req) {
        ReportSummaryResponseDto summary = summarize(req);
        byte[] pdfBytes = renderLandscapeA4Pdf(summary);
        auditService.log("Exported PDF report",
                summary.reportType().toUpperCase(Locale.ROOT) + " · BU=" + summary.selectedBu() + " · " + summary.totalSummary().agg().n() + " batches");
        return pdfBytes;
    }

    public static String formatInrCompact(double val) {
        double abs = Math.abs(val);
        String sign = val < 0 ? "-" : "";
        if (abs >= 1e7) {
            return String.format(Locale.US, "%sRs %.2f Cr", sign, abs / 1e7);
        }
        if (abs >= 1e5) {
            return String.format(Locale.US, "%sRs %.2f L", sign, abs / 1e5);
        }
        return String.format(Locale.US, "%sRs %,.0f", sign, abs);
    }

    /**
     * Renders a multi-page A4 Landscape (841.89 x 595.28 pt / 297 x 210 mm) PDF 1.4 document
     * matching the Unichem Inventory Management report specification.
     */
    private byte[] renderLandscapeA4Pdf(ReportSummaryResponseDto data) {
        PdfLandscapeBuilder pdf = new PdfLandscapeBuilder(data);
        Thresholds t = data.thresholds();
        ReportMetricsDto tot = data.totalSummary().agg();
        ReportActionCountsDto totAc = data.totalSummary().ac();

        // Title & scope banner
        String typeLabel = switch (data.reportType()) {
            case "bu" -> "Business Unit-wise Report";
            case "unit" -> "Unit-wise Report";
            default -> "Business Unit & Unit-wise Report";
        };
        pdf.drawTitleBlock(typeLabel, data);

        // 1. Executive Summary KPI Tiles
        if (Boolean.TRUE.equals(data.sections().getOrDefault("summary", true))) {
            pdf.drawSectionHeading("Executive summary", "Totals for the selected business units and units");
            double usablePct = tot.v() > 0 ? Math.round(tot.u() * 100.0 / tot.v()) : 0;
            List<PdfKpiTile> tiles = List.of(
                    new PdfKpiTile("TOTAL VALUE", formatInrCompact(tot.v()), tot.n() + " batches", PdfColor.NAVY),
                    new PdfKpiTile("UNRESTRICTED", formatInrCompact(tot.u()), (int) usablePct + "% usable", PdfColor.GREEN),
                    new PdfKpiTile("QUALITY", formatInrCompact(tot.q()), "under inspection", PdfColor.AMBER),
                    new PdfKpiTile("BLOCKED", formatInrCompact(tot.b()), "restricted", PdfColor.RED),
                    new PdfKpiTile("AGED >" + t.agingDays() + "D", formatInrCompact(tot.aged()), tot.agedN() + " batches", PdfColor.AMBER),
                    new PdfKpiTile("EXPIRY <=" + t.expiryDays() + "D", formatInrCompact(tot.exp()), tot.expN() + " batches", PdfColor.RED),
                    new PdfKpiTile("CONSUMABLES", formatInrCompact(tot.cons()), tot.idleN() + " idle items", PdfColor.ORANGE),
                    new PdfKpiTile("EXCEPTIONS / OPEN", tot.exc() + " / " + totAc.open(), totAc.overdue() + " overdue", PdfColor.RED)
            );
            pdf.drawKpiTiles(tiles);
        }

        // 2. Business Unit-wise Summary Table
        if (!"unit".equals(data.reportType())) {
            pdf.drawSectionHeading("Business Unit-wise summary", "Values in INR. Expiry counts exclude already expired batches.");
            String[] headers = {
                    "BU", "Description", "Units", "Batches", "Total value", "Unrestricted",
                    "Quality", "Blocked", "Aged >" + t.agingDays() + "d", "Expiry <=" + t.expiryDays() + "d",
                    "Expired", "Consumables", "Exceptions", "Open / overdue"
            };
            float[] widths = {45f, 115f, 32f, 40f, 62f, 62f, 56f, 56f, 58f, 60f, 54f, 58f, 44f, 51f};
            List<PdfTableRow> rows = new ArrayList<>();
            for (BuSummaryRowDto r : data.buSummaryRows()) {
                rows.add(new PdfTableRow("data", List.of(
                        r.bu(),
                        r.desc(),
                        String.valueOf(r.unitsCount()),
                        String.valueOf(r.agg().n()),
                        formatInrCompact(r.agg().v()),
                        formatInrCompact(r.agg().u()),
                        formatInrCompact(r.agg().q()),
                        formatInrCompact(r.agg().b()),
                        formatInrCompact(r.agg().aged()),
                        formatInrCompact(r.agg().exp()),
                        formatInrCompact(r.agg().expd()),
                        formatInrCompact(r.agg().cons()),
                        String.valueOf(r.agg().exc()),
                        r.ac().open() + " / " + r.ac().overdue()
                )));
            }
            ReportTotalSummaryDto totRow = data.totalSummary();
            rows.add(new PdfTableRow("total", List.of(
                    "Total",
                    "",
                    String.valueOf(totRow.unitsCount()),
                    String.valueOf(totRow.agg().n()),
                    formatInrCompact(totRow.agg().v()),
                    formatInrCompact(totRow.agg().u()),
                    formatInrCompact(totRow.agg().q()),
                    formatInrCompact(totRow.agg().b()),
                    formatInrCompact(totRow.agg().aged()),
                    formatInrCompact(totRow.agg().exp()),
                    formatInrCompact(totRow.agg().expd()),
                    formatInrCompact(totRow.agg().cons()),
                    String.valueOf(totRow.agg().exc()),
                    totRow.ac().open() + " / " + totRow.ac().overdue()
            )));
            pdf.drawTable(headers, widths, rows, 2);
        }

        // 3. Unit-wise Summary Table
        if (!"bu".equals(data.reportType())) {
            if ("both".equals(data.reportType())) {
                pdf.newPage();
            }
            pdf.drawSectionHeading("Unit-wise summary", "Grouped by business unit with subtotals");
            String[] headers = {
                    "BU", "Unit", "SAP code", "Batches", "Total value", "Unrestricted",
                    "Quality", "Blocked", "Aged >" + t.agingDays() + "d", "Expiry <=" + t.expiryDays() + "d",
                    "Consumables", "Exceptions", "Open / overdue"
            };
            float[] widths = {50f, 115f, 46f, 44f, 66f, 66f, 60f, 60f, 62f, 64f, 62f, 46f, 52f};
            List<PdfTableRow> rows = new ArrayList<>();
            for (UnitSummaryRowDto r : data.unitSummaryRows()) {
                rows.add(new PdfTableRow(r.kind(), List.of(
                        r.bu() != null ? r.bu() : "",
                        r.unit() != null ? r.unit() : "",
                        r.sap() != null ? r.sap() : "",
                        String.valueOf(r.agg().n()),
                        formatInrCompact(r.agg().v()),
                        formatInrCompact(r.agg().u()),
                        formatInrCompact(r.agg().q()),
                        formatInrCompact(r.agg().b()),
                        formatInrCompact(r.agg().aged()),
                        formatInrCompact(r.agg().exp()),
                        formatInrCompact(r.agg().cons()),
                        String.valueOf(r.agg().exc()),
                        r.ac().open() + " / " + r.ac().overdue()
                )));
            }
            pdf.drawTable(headers, widths, rows, 3);
        }

        return pdf.build();
    }

    private record PdfColor(float r, float g, float b) {
        static final PdfColor ORANGE = new PdfColor(0.91f, 0.45f, 0.11f);
        static final PdfColor NAVY = new PdfColor(0.11f, 0.17f, 0.31f);
        static final PdfColor MUTE = new PdfColor(0.42f, 0.45f, 0.50f);
        static final PdfColor GREEN = new PdfColor(0.12f, 0.54f, 0.34f);
        static final PdfColor AMBER = new PdfColor(0.88f, 0.54f, 0.07f);
        static final PdfColor RED = new PdfColor(0.75f, 0.22f, 0.17f);
        static final PdfColor LIGHT = new PdfColor(0.97f, 0.98f, 0.99f);
        static final PdfColor BORDER = new PdfColor(0.89f, 0.90f, 0.93f);
        static final PdfColor TEXT = new PdfColor(0.12f, 0.16f, 0.22f);
        static final PdfColor PEACH = new PdfColor(0.99f, 0.94f, 0.89f);
        static final PdfColor SUB = new PdfColor(0.92f, 0.94f, 0.98f);
        static final PdfColor WHITE = new PdfColor(1f, 1f, 1f);
    }

    private record PdfKpiTile(String label, String value, String sub, PdfColor color) {}
    private record PdfTableRow(String kind, List<String> cells) {}

    /**
     * Lightweight, standards-compliant PDF 1.4 writer for A4 Landscape (841.89 x 595.28 pt).
     */
    private static final class PdfLandscapeBuilder {
        static final float W = 841.89f;
        static final float H = 595.28f;
        static final float M = 34f;
        static final float TOP_Y = H - 76f;
        static final float BOTTOM_Y = 45f;

        private final ReportSummaryResponseDto meta;
        private final List<StringBuilder> pages = new ArrayList<>();
        private StringBuilder cur;
        private float y;

        PdfLandscapeBuilder(ReportSummaryResponseDto meta) {
            this.meta = meta;
            newPage();
        }

        void newPage() {
            cur = new StringBuilder(4096);
            pages.add(cur);
            y = TOP_Y;
        }

        void ensureSpace(float neededHeight) {
            if (y - neededHeight < BOTTOM_Y) {
                newPage();
            }
        }

        void drawTitleBlock(String title, ReportSummaryResponseDto data) {
            ensureSpace(52f);
            text(cur, true, 16f, PdfColor.NAVY, M, y - 14f, title);
            Thresholds t = data.thresholds();
            String unitsLabel = data.selectedUnits() != null && !data.selectedUnits().isEmpty()
                    ? String.join(", ", data.selectedUnits())
                    : "All units (" + data.activeUnits().size() + ")";
            String scopeLine = String.format(
                    "Business unit: %s   |   Units: %s   |   Data: %s   |   Source: %s   |   Thresholds: aged >%dd, expiry <=%dd",
                    data.selectedBu(),
                    unitsLabel,
                    "filtered".equals(data.dataScope()) ? "Dashboard filters" : "All inventory",
                    data.syncSource(),
                    t.agingDays(),
                    t.expiryDays()
            );
            text(cur, false, 8.5f, PdfColor.MUTE, M, y - 30f, truncate(scopeLine, 145));
            y -= 44f;
        }

        void drawSectionHeading(String title, String subtitle) {
            float blockH = subtitle != null ? 32f : 24f;
            ensureSpace(blockH + 36f);
            fillRect(cur, M, y - (subtitle != null ? 26f : 18f), 5f, subtitle != null ? 24f : 16f, PdfColor.ORANGE);
            text(cur, true, 12f, PdfColor.NAVY, M + 12f, y - 11f, title);
            if (subtitle != null) {
                text(cur, false, 8f, PdfColor.MUTE, M + 12f, y - 23f, subtitle);
            }
            y -= blockH;
        }

        void drawKpiTiles(List<PdfKpiTile> tiles) {
            ensureSpace(64f);
            int count = tiles.size();
            float gap = 8f;
            float tileW = (W - 2 * M - gap * (count - 1)) / count;
            float tileH = 50f;
            float top = y;
            for (int i = 0; i < count; i++) {
                PdfKpiTile tile = tiles.get(i);
                float x = M + i * (tileW + gap);
                fillAndStrokeRect(cur, x, top - tileH, tileW, tileH, PdfColor.LIGHT, PdfColor.BORDER, 0.6f);
                fillRect(cur, x + 4f, top - tileH + 7f, 2.5f, tileH - 14f, tile.color());
                text(cur, true, 6.2f, PdfColor.MUTE, x + 11f, top - 14f, truncate(tile.label(), 20));
                text(cur, true, 10.5f, tile.color(), x + 11f, top - 30f, truncate(tile.value(), 18));
                text(cur, false, 6.8f, PdfColor.MUTE, x + 11f, top - 42f, truncate(tile.sub(), 22));
            }
            y -= (tileH + 16f);
        }

        void drawTable(String[] headers, float[] widths, List<PdfTableRow> rows, int firstNumericCol) {
            float headerH = 20f;
            float rowH = 16.5f;
            ensureSpace(headerH + rowH * Math.min(2, Math.max(1, rows.size())));

            drawTableHeader(headers, widths, headerH);
            int rowIdx = 0;
            for (PdfTableRow row : rows) {
                if (y - rowH < BOTTOM_Y) {
                    newPage();
                    drawTableHeader(headers, widths, headerH);
                }
                PdfColor bg = switch (row.kind()) {
                    case "total" -> PdfColor.PEACH;
                    case "sub" -> PdfColor.SUB;
                    default -> (rowIdx % 2 == 1) ? PdfColor.LIGHT : PdfColor.WHITE;
                };
                boolean bold = !"data".equals(row.kind()) && !"unit".equals(row.kind());
                float cx = M;
                for (int c = 0; c < headers.length; c++) {
                    float cw = widths[c];
                    fillAndStrokeRect(cur, cx, y - rowH, cw, rowH, bg, PdfColor.BORDER, 0.4f);
                    String val = c < row.cells().size() && row.cells().get(c) != null ? row.cells().get(c) : "";
                    int maxChars = Math.max(4, (int) (cw / 4.3f));
                    String clipped = truncate(val, maxChars);
                    if (c >= firstNumericCol) {
                        float textW = clipped.length() * 3.8f;
                        float tx = Math.max(cx + 3f, cx + cw - textW - 5f);
                        text(cur, bold, 7.2f, PdfColor.TEXT, tx, y - 11.5f, clipped);
                    } else {
                        text(cur, bold, 7.2f, PdfColor.TEXT, cx + 4f, y - 11.5f, clipped);
                    }
                    cx += cw;
                }
                y -= rowH;
                rowIdx++;
            }
            y -= 14f;
        }

        private void drawTableHeader(String[] headers, float[] widths, float headerH) {
            float cx = M;
            for (int c = 0; c < headers.length; c++) {
                float cw = widths[c];
                fillAndStrokeRect(cur, cx, y - headerH, cw, headerH, PdfColor.NAVY, PdfColor.BORDER, 0.4f);
                int maxChars = Math.max(4, (int) (cw / 4.3f));
                text(cur, true, 7.0f, PdfColor.WHITE, cx + 4f, y - 13f, truncate(headers[c], maxChars));
                cx += cw;
            }
            y -= headerH;
        }

        private void drawHeaderAndFooterOnPage(StringBuilder pageStream, int pageNum, int totalPages) {
            String typeLabel = switch (meta.reportType()) {
                case "bu" -> "BUSINESS UNIT-WISE REPORT";
                case "unit" -> "UNIT-WISE REPORT";
                default -> "BUSINESS UNIT & UNIT-WISE REPORT";
            };
            // Top orange bar & white brand box
            fillRect(pageStream, 0f, H - 48f, W, 48f, PdfColor.ORANGE);
            fillRect(pageStream, 0f, H - 48f, 175f, 48f, PdfColor.WHITE);
            text(pageStream, true, 15f, PdfColor.ORANGE, M - 10f, H - 26f, truncate(meta.companyShort(), 16));
            text(pageStream, true, 6.5f, PdfColor.NAVY, M - 10f, H - 39f, truncate(meta.companyName().toUpperCase(Locale.ROOT), 30));

            text(pageStream, true, 11.5f, PdfColor.WHITE, 190f, H - 23f, "INVENTORY MANAGEMENT - " + typeLabel);
            String stamp = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH));
            String metaLine = "Generated " + stamp
                    + (meta.preparedBy() != null && !meta.preparedBy().isBlank() ? " | Prepared by " + meta.preparedBy() : "")
                    + " | " + meta.companyName();
            text(pageStream, false, 7.8f, PdfColor.WHITE, 190f, H - 37f, truncate(metaLine, 110));

            // Footer line & page number
            strokeLine(pageStream, M, 30f, W - M, 30f, PdfColor.BORDER, 0.7f);
            String footerLeft = meta.companyName() + " | Inventory Management | Confidential - for internal use | Values in INR (L = lakh, Cr = crore)";
            text(pageStream, false, 7.0f, PdfColor.MUTE, M, 18f, footerLeft);
            String pageStr = "Page " + pageNum + " of " + totalPages;
            text(pageStream, false, 7.0f, PdfColor.MUTE, W - M - 52f, 18f, pageStr);
        }

        byte[] build() {
            int totalPages = pages.size();
            for (int i = 0; i < totalPages; i++) {
                drawHeaderAndFooterOnPage(pages.get(i), i + 1, totalPages);
            }

            // Assemble PDF 1.4 objects:
            // 1: Catalog, 2: Pages, 3: Font F1 (Helvetica), 4: Font F2 (Helvetica-Bold)
            // Followed by 2 objects per page (Page object + Contents stream object)
            ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
            List<Integer> offsets = new ArrayList<>();
            writeAscii(out, "%PDF-1.4\n");

            int firstPageObjId = 5;
            // Obj 1: Catalog
            offsets.add(out.size());
            writeAscii(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

            // Obj 2: Pages
            StringBuilder kids = new StringBuilder("[");
            for (int i = 0; i < totalPages; i++) {
                if (i > 0) kids.append(' ');
                kids.append(firstPageObjId + i * 2).append(" 0 R");
            }
            kids.append(']');
            offsets.add(out.size());
            writeAscii(out, String.format(Locale.US,
                    "2 0 obj\n<< /Type /Pages /Kids %s /Count %d >>\nendobj\n", kids, totalPages));

            // Obj 3: Helvetica
            offsets.add(out.size());
            writeAscii(out, "3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>\nendobj\n");

            // Obj 4: Helvetica-Bold
            offsets.add(out.size());
            writeAscii(out, "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>\nendobj\n");

            for (int i = 0; i < totalPages; i++) {
                int pageObjId = firstPageObjId + i * 2;
                int contentObjId = pageObjId + 1;
                byte[] streamBytes = pages.get(i).toString().getBytes(StandardCharsets.ISO_8859_1);

                offsets.add(out.size());
                writeAscii(out, String.format(Locale.US,
                        "%d 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.2f %.2f] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents %d 0 R >>\nendobj\n",
                        pageObjId, W, H, contentObjId));

                offsets.add(out.size());
                writeAscii(out, String.format(Locale.US,
                        "%d 0 obj\n<< /Length %d >>\nstream\n", contentObjId, streamBytes.length));
                out.writeBytes(streamBytes);
                writeAscii(out, "\nendstream\nendobj\n");
            }

            int xrefOffset = out.size();
            int totalObjects = offsets.size() + 1;
            writeAscii(out, String.format(Locale.US, "xref\n0 %d\n0000000000 65535 f \n", totalObjects));
            for (int off : offsets) {
                writeAscii(out, String.format(Locale.US, "%010d 00000 n \n", off));
            }
            writeAscii(out, String.format(Locale.US,
                    "trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n",
                    totalObjects, xrefOffset));
            return out.toByteArray();
        }

        private static void writeAscii(ByteArrayOutputStream out, String s) {
            out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
        }

        private static void fillRect(StringBuilder sb, float x, float y, float w, float h, PdfColor c) {
            sb.append(String.format(Locale.US, "%.2f %.2f %.2f rg %.2f %.2f %.2f %.2f re f\n",
                    c.r(), c.g(), c.b(), x, y, w, h));
        }

        private static void fillAndStrokeRect(StringBuilder sb, float x, float y, float w, float h, PdfColor fill, PdfColor stroke, float lineW) {
            sb.append(String.format(Locale.US, "%.2f w %.2f %.2f %.2f rg %.2f %.2f %.2f RG %.2f %.2f %.2f %.2f re B\n",
                    lineW, fill.r(), fill.g(), fill.b(), stroke.r(), stroke.g(), stroke.b(), x, y, w, h));
        }

        private static void strokeLine(StringBuilder sb, float x1, float y1, float x2, float y2, PdfColor stroke, float lineW) {
            sb.append(String.format(Locale.US, "%.2f w %.2f %.2f %.2f RG %.2f %.2f m %.2f %.2f l S\n",
                    lineW, stroke.r(), stroke.g(), stroke.b(), x1, y1, x2, y2));
        }

        private static void text(StringBuilder sb, boolean bold, float size, PdfColor color, float x, float y, String rawText) {
            String safe = sanitizePdfText(rawText);
            sb.append(String.format(Locale.US, "BT /%s %.2f Tf %.2f %.2f %.2f rg %.2f %.2f Td (%s) Tj ET\n",
                    bold ? "F2" : "F1", size, color.r(), color.g(), color.b(), x, y, safe));
        }

        private static String sanitizePdfText(String s) {
            if (s == null || s.isEmpty()) return "";
            String cleaned = s
                    .replace("₹", "Rs ")
                    .replace("≤", "<=")
                    .replace("≥", ">=")
                    .replace("–", "-")
                    .replace("—", "-")
                    .replace("·", "|");
            StringBuilder out = new StringBuilder(cleaned.length() + 8);
            for (int i = 0; i < cleaned.length(); i++) {
                char c = cleaned.charAt(i);
                if (c == '(' || c == ')' || c == '\\') {
                    out.append('\\').append(c);
                } else if (c >= 32 && c <= 126) {
                    out.append(c);
                } else {
                    out.append(' ');
                }
            }
            return out.toString();
        }

        private static String truncate(String s, int maxLen) {
            if (s == null) return "";
            if (s.length() <= maxLen) return s;
            return maxLen > 3 ? s.substring(0, maxLen - 2) + ".." : s.substring(0, maxLen);
        }
    }
}
