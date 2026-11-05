package com.unichem.inventory.service;

import com.unichem.inventory.domain.*;
import com.unichem.inventory.dto.CalcItem;
import com.unichem.inventory.dto.Dtos.ImportResult;
import com.unichem.inventory.dto.Dtos.ImportRow;
import com.unichem.inventory.dto.Thresholds;
import com.unichem.inventory.repo.ActionItemRepository;
import com.unichem.inventory.repo.InventoryItemRepository;
import com.unichem.inventory.repo.SyncLogRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static com.unichem.inventory.domain.Constants.*;

/** Inventory import from the Unichem SAP stock report (22 columns) or the app template: preview → validate → commit. */
@Service
@RequiredArgsConstructor
public class ImportService {
    private final InventoryItemRepository repo;
    private final ActionItemRepository actions;
    private final SyncLogRepository syncLog;
    private final MasterLookup lookup;
    private final CurrentUserService current;
    private final CalcService calc;
    private final SettingsService settings;
    private final SnapshotService snapshots;
    private final AuditService audit;

    /** field → accepted header names. */
    static final Map<String, List<String>> SAPSTOCK = new LinkedHashMap<>();
    static final Map<String, List<String>> SAPSTOCK_SUM = new LinkedHashMap<>();
    static final Map<String, List<String>> APP = new LinkedHashMap<>();
    public static final List<String> SAP_COLUMNS = List.of("Material", "Material Description", "Plant", "Storage Location", "Material Type", "Material Group",
            "Base Unit of Measure", "Batch", "Unrestricted", "Unrestricted Stock Value", "In Quality Insp.", "Quality Inspection Value", "Restricted-Use Stock",
            "Restricted Stock Value", "Stock in transfer", "Stock In Transfer Value", "Blocked", "Blocked Stock Value", "Date of Manufacture", "SLED/BBD",
            "GR Posting Date", "Last Posting Date");
    public static final List<String> APP_COLUMNS = List.of("Business Unit", "Plant", "Material Category", "Department", "Sub-category", "Material Code",
            "Material Description", "Batch / Lot", "UOM", "Storage Location", "Unrestricted Qty", "Quality Inspection Qty", "Blocked Qty", "Unit Cost (INR)",
            "GR Posting Date", "Lying Since", "Date of Manufacture", "SLED/BBD", "Date Type", "Last Posting Date", "Block Reason", "Remarks");
    static {
        SAPSTOCK.put("code", List.of("Material")); SAPSTOCK.put("name", List.of("Material Description")); SAPSTOCK.put("plant", List.of("Plant", "Name 1"));
        SAPSTOCK.put("storageLoc", List.of("Storage Location")); SAPSTOCK.put("matType", List.of("Material Type")); SAPSTOCK.put("matGroup", List.of("Material Group"));
        SAPSTOCK.put("uom", List.of("Base Unit of Measure")); SAPSTOCK.put("batch", List.of("Batch")); SAPSTOCK.put("unrestrictedQty", List.of("Unrestricted"));
        SAPSTOCK.put("qualityQty", List.of("In Quality Insp.")); SAPSTOCK.put("mfgDate", List.of("Date of Manufacture", "Manufacture Date"));
        SAPSTOCK.put("expiryDate", List.of("SLED/BBD", "SLED / BBD", "Shelf Life Expiry Date")); SAPSTOCK.put("receiptDate", List.of("GR Posting Date", "GR Date", "Creation Date"));
        SAPSTOCK.put("lastIssueDate", List.of("Last Posting Date", "Last Goods Issue Date"));
        SAPSTOCK_SUM.put("blockedQty", List.of("Blocked", "Restricted-Use Stock", "Returns", "Valuated Goods Receipt Blocked Stock"));
        SAPSTOCK_SUM.put("totalValue", List.of("Unrestricted Stock Value", "Quality Inspection Value", "Blocked Stock Value", "Restricted Stock Value",
                "Value Unrestricted", "Value in QualInsp.", "Value BlockedStock", "Value Restricted"));
        SAPSTOCK_SUM.put("transitQty", List.of("Stock in transfer", "Stock in Transit", "Transit and Transfer"));
        SAPSTOCK_SUM.put("transitValue", List.of("Stock In Transfer Value", "Value in Transit", "Val. in Trans./Tfr"));
        String[][] app = {{"bu", "Business Unit"}, {"plant", "Plant"}, {"matType", "Material Category"}, {"dept", "Department"}, {"subCat", "Sub-category"},
                {"code", "Material Code"}, {"name", "Material Description"}, {"batch", "Batch / Lot"}, {"uom", "UOM"}, {"storageLoc", "Storage Location"},
                {"unrestrictedQty", "Unrestricted Qty"}, {"qualityQty", "Quality Inspection Qty"}, {"blockedQty", "Blocked Qty"}, {"unitCost", "Unit Cost (INR)"},
                {"receiptDate", "GR Posting Date"}, {"lyingSince", "Lying Since"}, {"mfgDate", "Date of Manufacture"}, {"expiryDate", "SLED/BBD"},
                {"dateType", "Date Type"}, {"lastIssueDate", "Last Posting Date"}, {"blockReason", "Block Reason"}, {"remarks", "Remarks"}};
        for (String[] a : app) APP.put(a[0], List.of(a[1], a[0]));
        APP.put("receiptDate", List.of("GR Posting Date", "GRN / Receipt Date", "receiptDate"));
        APP.put("mfgDate", List.of("Date of Manufacture", "Manufacturing Date", "mfgDate"));
        APP.put("expiryDate", List.of("SLED/BBD", "Expiry / Retest Date", "expiryDate"));
        APP.put("lastIssueDate", List.of("Last Posting Date", "Last Issue Date", "lastIssueDate"));
    }

    // ------------------------------------------------------------------ reading
    public List<Map<String, String>> read(MultipartFile file) throws Exception {
        String n = Objects.requireNonNullElse(file.getOriginalFilename(), "").toLowerCase();
        List<List<String>> aoa = n.endsWith(".xlsx") || n.endsWith(".xls") || n.endsWith(".xlsm") ? readExcel(file.getInputStream()) : readCsv(file.getInputStream());
        int h = 0;
        for (int i = 0; i < Math.min(10, aoa.size()); i++) if (aoa.get(i).stream().filter(v -> !v.isBlank()).count() >= 2) { h = i; break; }
        if (aoa.isEmpty()) return List.of();
        List<String> header = aoa.get(h).stream().map(String::trim).toList();
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = h + 1; i < aoa.size(); i++) {
            List<String> r = aoa.get(i);
            if (r.stream().allMatch(String::isBlank)) continue;
            Map<String, String> o = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) if (!header.get(c).isEmpty()) o.put(header.get(c), c < r.size() ? r.get(c) : "");
            rows.add(o);
        }
        return rows;
    }

    private List<List<String>> readExcel(InputStream in) throws Exception {
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = null;
            for (int i = 0; i < wb.getNumberOfSheets(); i++)
                if (wb.getSheetName(i).matches("(?i)sap stock|inventory|template|data|sheet1|summary")) { sh = wb.getSheetAt(i); break; }
            if (sh == null) sh = wb.getSheetAt(0);
            List<List<String>> out = new ArrayList<>();
            DataFormatter fmt = new DataFormatter();
            for (Row row : sh) {
                List<String> r = new ArrayList<>();
                for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                    Cell cell = row.getCell(c);
                    if (cell == null) { r.add(""); continue; }
                    CellType t = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
                    if (t == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell))
                        r.add(cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString());
                    else if (t == CellType.NUMERIC) r.add(BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString());
                    else r.add(fmt.formatCellValue(cell));
                }
                out.add(r);
            }
            return out;
        }
    }

    private List<List<String>> readCsv(InputStream in) throws Exception {
        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("﻿", "");
        String first = text.lines().findFirst().orElse("");
        long tabs = first.chars().filter(ch -> ch == '\t').count(), semis = first.chars().filter(ch -> ch == ';').count(), commas = first.chars().filter(ch -> ch == ',').count();
        char delim = tabs > commas && tabs >= semis ? '\t' : semis > commas ? ';' : ',';
        List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>(); StringBuilder cur = new StringBuilder(); boolean q = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (q) { if (c == '"') { if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = false; } else cur.append(c); }
            else if (c == '"') q = true;
            else if (c == delim) { row.add(cur.toString()); cur.setLength(0); }
            else if (c == '\n' || c == '\r') { if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++; row.add(cur.toString()); rows.add(row); row = new ArrayList<>(); cur.setLength(0); }
            else cur.append(c);
        }
        if (cur.length() > 0 || !row.isEmpty()) { row.add(cur.toString()); rows.add(row); }
        return rows;
    }

    // ------------------------------------------------------------------ mapping
    record Built(InventoryItem rec, List<String> errs, List<String> warns, int src, boolean excluded) {}

    private List<Built> map(List<Map<String, String>> rows, boolean sap, List<String> missing) {
        Map<String, List<String>> M = sap ? SAPSTOCK : APP;
        Set<String> keys = new LinkedHashSet<>(); rows.forEach(r -> keys.addAll(r.keySet()));
        Map<String, String> hidx = new HashMap<>(); keys.forEach(k -> hidx.put(Parsing.norm(k), k));
        Map<String, String> col = new HashMap<>();
        M.forEach((f, names) -> names.stream().map(n -> hidx.get(Parsing.norm(n))).filter(Objects::nonNull).findFirst().ifPresentOrElse(k -> col.put(f, k), () -> missing.add(f)));
        Map<String, List<String>> sum = new HashMap<>();
        if (sap) SAPSTOCK_SUM.forEach((f, names) -> { List<String> ks = names.stream().map(n -> hidx.get(Parsing.norm(n))).filter(Objects::nonNull).toList(); if (!ks.isEmpty()) sum.put(f, ks); });
        MasterLookup.Ctx ctx = lookup.ctx();
        List<Built> out = new ArrayList<>();
        int i = 1;
        for (Map<String, String> r : rows) {
            i++;
            Map<String, String> raw = new HashMap<>();
            col.forEach((f, k) -> raw.put(f, r.getOrDefault(k, "")));
            Map<String, Double> sums = new HashMap<>();
            sum.forEach((f, ks) -> sums.put(f, ks.stream().mapToDouble(k -> Parsing.num(r.get(k))).sum()));
            out.add(build(raw, sums, ctx, i));
        }
        return out;
    }

    /** Port of buildRec: validation, master mapping, exclusion of non-inventory SAP types. */
    private Built build(Map<String, String> raw, Map<String, Double> sums, MasterLookup.Ctx ctx, int src) {
        List<String> errs = new ArrayList<>(), warns = new ArrayList<>();
        java.util.function.Function<String, String> s = k -> Objects.requireNonNullElse(raw.get(k), "").trim();
        String pv = s.apply("plant");
        Optional<Plant> pm = ctx.plant(pv);
        if (pv.isEmpty()) errs.add("Plant missing"); else if (pm.isEmpty()) warns.add("Plant \"" + pv + "\" not in plant master");
        String plant = pm.map(Plant::getName).orElse(pv);
        String bu = ctx.bu(s.apply("bu"));
        if (bu.isEmpty()) bu = pm.map(Plant::getBuCode).orElse("");
        boolean excluded = false;
        String mtv = s.apply("matType");
        Optional<MaterialTypeMap> mt = ctx.mtype(mtv);
        String cat = mt.map(MaterialTypeMap::getCategory).filter(MAT_TYPES::contains).orElse(mt.isPresent() ? "" : MasterLookup.catFromText(mtv));
        Optional<MaterialGroupMap> mg = ctx.mgroup(s.apply("matGroup"));
        if (mt.isPresent() && "Exclude".equals(mt.get().getCategory())) { errs.add("Excluded: material type " + mtv + " is not tracked as inventory"); excluded = true; }
        else if (mt.isEmpty() && cat.isEmpty() && mg.isPresent() && "Exclude".equals(mg.get().getCategory())) { errs.add("Excluded: material group " + mg.get().getSapCode() + " is not tracked as inventory"); excluded = true; }
        if (cat.isEmpty() && mg.isPresent() && mg.get().getCategory() != null && MAT_TYPES.contains(mg.get().getCategory())) cat = mg.get().getCategory();
        if (cat.isEmpty() && mg.isPresent() && mg.get().getDepartment() != null) cat = "Consumable";
        if (cat.isEmpty()) { if (!excluded) warns.add(mtv.isEmpty() ? "Category missing — set to Raw Material" : "Category \"" + mtv + "\" not mapped — set to Raw Material"); cat = "Raw Material"; }
        String dept = null, sub = s.apply("subCat");
        if ("Consumable".equals(cat)) {
            dept = MasterLookup.dept(s.apply("dept"));
            if (dept.isEmpty() && mg.isPresent() && mg.get().getDepartment() != null) dept = mg.get().getDepartment();
            if (dept.isEmpty() && mt.isPresent() && mt.get().getDepartment() != null) dept = mt.get().getDepartment();
            if (dept.isEmpty()) dept = "Other";
            if (sub.isEmpty() && mg.isPresent()) sub = Objects.requireNonNullElse(mg.get().getSubCategory(), Objects.requireNonNullElse(mg.get().getDescription(), ""));
        }
        String lv = s.apply("storageLoc");
        Optional<StorageLocation> lm = ctx.sloc(lv, plant);
        if (!lv.isEmpty() && lm.isEmpty()) warns.add("Storage location \"" + lv + "\" not in master");
        double u = Parsing.num(raw.get("unrestrictedQty")), q = Parsing.num(raw.get("qualityQty"));
        double b = sums.containsKey("blockedQty") ? sums.get("blockedQty") : Parsing.num(raw.get("blockedQty"));
        if (u < 0 || q < 0 || b < 0) errs.add("Negative quantity");
        if (u + q + b == 0) warns.add("Zero stock quantity");
        double cost = Parsing.num(raw.get("unitCost"));
        if (cost == 0 && sums.containsKey("totalValue") && u + q + b > 0) cost = sums.get("totalValue") / (u + q + b);
        if (cost == 0) warns.add("No unit cost / value — value will show as ₹0");
        Map<String, LocalDate> dates = new HashMap<>();
        for (String f : List.of("receiptDate", "lyingSince", "mfgDate", "expiryDate", "lastIssueDate")) {
            try { dates.put(f, Parsing.date(raw.get(f))); } catch (IllegalArgumentException e) { warns.add(e.getMessage()); dates.put(f, null); }
        }
        if (dates.get("receiptDate") == null && dates.get("lyingSince") == null) warns.add("No GR Posting Date — inventory age cannot be calculated");
        if (dates.get("mfgDate") != null && dates.get("expiryDate") != null && dates.get("expiryDate").isBefore(dates.get("mfgDate"))) warns.add("SLED/BBD is before Date of Manufacture");
        if (dates.get("lastIssueDate") != null && dates.get("receiptDate") != null && dates.get("lastIssueDate").isBefore(dates.get("receiptDate"))) dates.put("lastIssueDate", null);
        if (SHELF_TYPES.contains(cat) && dates.get("expiryDate") == null && !excluded) warns.add("No SLED/BBD — expiry cannot be monitored");
        String code = s.apply("code"), name = s.apply("name");
        if (code.isEmpty()) errs.add("Material code missing");
        if (name.isEmpty()) { name = code; if (!code.isEmpty()) warns.add("Description missing — code used"); }
        if (!plant.isEmpty() && pm.isPresent() && !current.inScope(plant)) errs.add("Plant outside your data access");

        InventoryItem it = new InventoryItem();
        it.setBuCode(bu.isEmpty() ? "API" : bu); it.setPlant(plant); it.setMatType(cat); it.setDepartment(dept);
        it.setSubCategory(sub.isEmpty() ? null : sub); it.setCode(code); it.setName(name.length() > 200 ? name.substring(0, 200) : name);
        it.setBatch(s.apply("batch")); it.setUom(s.apply("uom").isEmpty() ? null : s.apply("uom"));
        it.setStorageLoc(lm.map(StorageLocation::getName).orElse(lv.isEmpty() ? null : lv));
        it.setUnrestrictedQty(bd(Math.max(0, u), 3)); it.setQualityQty(bd(Math.max(0, q), 3)); it.setBlockedQty(bd(Math.max(0, b), 3)); it.setUnitCost(bd(Math.max(0, cost), 4));
        it.setReceiptDate(dates.get("receiptDate")); it.setLyingSince(dates.get("lyingSince")); it.setMfgDate(dates.get("mfgDate"));
        it.setExpiryDate(dates.get("expiryDate")); it.setLastIssueDate(dates.get("lastIssueDate"));
        String dt = s.apply("dateType");
        it.setDateType(dt.toLowerCase().contains("retest") || (dt.isEmpty() && (cat.equals("Raw Material") || cat.equals("Intermediate"))) ? "Retest" : "Expiry");
        it.setBlockReason(s.apply("blockReason").isEmpty() ? null : s.apply("blockReason"));
        List<String> rem = new ArrayList<>();
        if (!s.apply("remarks").isEmpty()) rem.add(s.apply("remarks"));
        if (sums.getOrDefault("transitQty", 0.0) != 0 || sums.getOrDefault("transitValue", 0.0) != 0)
            rem.add("In transit: " + sums.getOrDefault("transitQty", 0.0) + " · ₹" + Math.round(sums.getOrDefault("transitValue", 0.0)));
        it.setRemarks(rem.isEmpty() ? null : String.join(" · ", rem));
        return new Built(it, errs, warns, src, excluded);
    }

    private static BigDecimal bd(double v, int scale) { return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP); }

    // ------------------------------------------------------------------ preview / commit
    @Transactional
    public ImportResult run(MultipartFile file, String format, String mode, LocalDate asOf, boolean commit) throws Exception {
        List<Map<String, String>> rows = read(file);
        if (rows.isEmpty()) throw new IllegalArgumentException("No data rows found");
        boolean sap = switch (format == null ? "AUTO" : format) {
            case "SAPSTOCK" -> true; case "APP" -> false;
            default -> rows.get(0).keySet().stream().anyMatch(k -> Parsing.norm(k).equals("unrestricted"));
        };
        List<String> missing = new ArrayList<>();
        List<Built> built = map(rows, sap, missing);
        if ("history".equals(mode) && asOf == null) throw new IllegalArgumentException("Enter the 'As of' date for the historical snapshot");
        List<Built> ok = built.stream().filter(b -> b.errs().isEmpty()).toList();
        // duplicates within the file: keep the last occurrence
        Map<String, Built> uniq = new LinkedHashMap<>();
        ok.forEach(b -> uniq.put(b.rec().itemKey(), b));
        Set<String> existing = new HashSet<>();
        repo.findAll().forEach(i -> existing.add(i.itemKey()));
        int updates = "upsert".equals(mode) ? (int) uniq.keySet().stream().filter(existing::contains).count() : 0;
        Thresholds t = settings.thresholds();
        double value = uniq.values().stream().mapToDouble(b -> calc.calc(b.rec(), t).value()).sum();
        int added = 0, updated = 0;
        if (commit) {
            switch (mode == null ? "upsert" : mode) {
                case "history" -> {
                    List<CalcItem> list = uniq.values().stream().map(b -> calc.calc(b.rec(), t, asOf)).toList();
                    snapshots.take("import", asOf, list);
                    added = list.size();
                }
                case "replace" -> {
                    Set<String> allowed = current.allowedPlants();
                    if (allowed == null) repo.deleteAllInBatch(); else repo.deleteByPlantIn(allowed);
                    repo.flush();
                    repo.saveAll(uniq.values().stream().map(Built::rec).toList());
                    added = uniq.size();
                }
                case "add" -> {
                    List<InventoryItem> fresh = uniq.values().stream().map(Built::rec).filter(r -> !existing.contains(r.itemKey())).toList();
                    repo.saveAll(fresh); added = fresh.size();
                }
                default -> {
                    Map<String, InventoryItem> idx = new HashMap<>();
                    repo.findAll().forEach(i -> idx.put(i.itemKey(), i));
                    List<InventoryItem> save = new ArrayList<>();
                    for (Built b : uniq.values()) {
                        InventoryItem cur = idx.get(b.rec().itemKey());
                        if (cur != null) { copy(b.rec(), cur); save.add(cur); updated++; } else { save.add(b.rec()); added++; }
                    }
                    repo.saveAll(save);
                }
            }
            SyncLog l = new SyncLog();
            l.setSource(("history".equals(mode) ? "Historical snapshot (" : "File import (") + file.getOriginalFilename() + ")");
            l.setRecords(uniq.size()); l.setStatus("OK");
            l.setMessage(added + " added · " + updated + " updated · " + (built.size() - ok.size()) + " skipped");
            syncLog.save(l);
            settings.update(Map.of("sync.source", l.getSource(), "sync.lastRefresh", java.time.OffsetDateTime.now().toString()), current.require().getUsername());
            if (!"history".equals(mode)) snapshots.take("auto", null, null);
            audit.log("Imported inventory", file.getOriginalFilename() + " · " + mode + " · " + added + " added, " + updated + " updated", "inventory", null, null);
        }
        List<ImportRow> preview = built.stream().limit(300).map(b -> {
            InventoryItem r = b.rec();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("plant", r.getPlant()); m.put("matType", r.getMatType()); m.put("dept", r.getDepartment()); m.put("code", r.getCode());
            m.put("name", r.getName()); m.put("batch", r.getBatch()); m.put("u", r.getUnrestrictedQty()); m.put("q", r.getQualityQty()); m.put("b", r.getBlockedQty());
            m.put("unitCost", r.getUnitCost()); m.put("receiptDate", r.getReceiptDate()); m.put("expiryDate", r.getExpiryDate());
            String st = !b.errs().isEmpty() ? (b.excluded() ? "Excluded" : "Error") : !b.warns().isEmpty() ? "Warning" : "OK";
            return new ImportRow(b.src(), st, m, b.errs(), b.warns());
        }).toList();
        return new ImportResult(file.getOriginalFilename(), sap ? "SAPSTOCK" : "APP", mode, rows.size(), uniq.size(),
                (int) ok.stream().filter(b -> !b.warns().isEmpty()).count(), built.size() - ok.size(),
                commit ? added : uniq.size() - updates, commit ? updated : updates, value, missing, preview, commit);
    }

    private static void copy(InventoryItem s, InventoryItem d) {
        d.setBuCode(s.getBuCode()); d.setMatType(s.getMatType()); d.setDepartment(s.getDepartment()); d.setSubCategory(s.getSubCategory());
        d.setName(s.getName()); d.setUom(s.getUom()); d.setStorageLoc(s.getStorageLoc()); d.setUnrestrictedQty(s.getUnrestrictedQty());
        d.setQualityQty(s.getQualityQty()); d.setBlockedQty(s.getBlockedQty()); d.setUnitCost(s.getUnitCost()); d.setReceiptDate(s.getReceiptDate());
        d.setLyingSince(s.getLyingSince()); d.setMfgDate(s.getMfgDate()); d.setExpiryDate(s.getExpiryDate()); d.setDateType(s.getDateType());
        d.setLastIssueDate(s.getLastIssueDate()); d.setBlockReason(s.getBlockReason()); d.setRemarks(s.getRemarks());
    }
}
