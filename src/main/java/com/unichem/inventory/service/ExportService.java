package com.unichem.inventory.service;

import com.unichem.inventory.domain.ActionItem;
import com.unichem.inventory.dto.CalcItem;
import com.unichem.inventory.dto.Thresholds;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static com.unichem.inventory.domain.Constants.*;

/** Excel exports: inventory register with calculated fields, management report, action register, templates. */
@Service
@RequiredArgsConstructor
public class ExportService {
    private final InventoryService inventory;
    private final ActionService actions;
    private final AnalyticsService analytics;
    private final SettingsService settings;
    private final AuditService audit;

    private static final String[] INV_HEAD = {"BU", "Plant", "Category", "Department", "Sub-category", "Code", "Description", "Batch", "UOM", "Storage Loc",
            "Unrestricted Qty", "Quality Qty", "Blocked Qty", "Total Qty", "Unit Cost", "Total Value", "Unrestricted Value", "Quality Value", "Blocked Value",
            "GR Posting Date", "Lying Since", "Inventory Age (d)", "Aging Bucket", "Date of Manufacture", "SLED/BBD", "Date Type", "Remaining Shelf Life (d)",
            "Expiry Band", "Last Posting Date", "Days Since Issue", "Movement", "Stock Status", "Consumable Status", "Exceptions", "Priority", "Score"};

    private List<Object> invRow(CalcItem x, Thresholds t) {
        return Arrays.asList(x.bu(), x.plant(), x.matType(), x.dept(), x.subCat(), x.code(), x.name(), x.batch(), x.uom(), x.storageLoc(),
                x.unrestrictedQty(), x.qualityQty(), x.blockedQty(), x.qty(), x.unitCost(), Math.round(x.value()), Math.round(x.uVal()), Math.round(x.qVal()),
                Math.round(x.bVal()), x.receiptDate(), x.lying(), x.age(), x.bucket(), x.mfgDate(), x.expiryDate(), x.dateType(), x.rsl(), x.band(),
                x.lastIssueDate(), x.dsi(), x.movement(), String.join("/", x.statuses()), x.consStatus(),
                x.flags().stream().map(f -> CalcService.flagLabel(f, t)).collect(Collectors.joining("; ")), x.prio(), x.score());
    }

    private static final String[] ACT_HEAD = {"ID", "Priority", "Stage", "BU", "Plant", "Category", "Code", "Description", "Batch", "Exception", "Action Type",
            "Action Plan", "Responsible Function", "Responsible Person", "Created", "Target Date", "Days Left", "Escalation", "Closed Date", "Closure", "Remarks"};

    private List<Object> actRow(ActionItem a) {
        return Arrays.asList(a.getId(), a.getPriority(), a.getStage(), a.getBuCode(), a.getPlant(), a.getMatType(), a.getCode(), a.getName(), a.getBatch(),
                a.getException(), a.getActionType(), a.getPlan(), a.getFunctionName(), a.getPerson(), a.getCreatedDate(), a.getTargetDate(),
                ActionService.daysLeft(a), ActionService.escalation(a), a.getClosedDate(), a.getClosure(), a.getRemarks());
    }

    public byte[] inventory(InventoryService.Filter f) {
        Thresholds t = settings.thresholds();
        List<CalcItem> d = inventory.calculated(f);
        audit.log("Exported inventory", d.size() + " records");
        return book(Map.of("Inventory", sheet(INV_HEAD, d.stream().map(x -> invRow(x, t)).toList())));
    }

    public byte[] actions() {
        List<ActionItem> l = actions.visible();
        audit.log("Exported", "Action register · " + l.size());
        return book(Map.of("Actions", sheet(ACT_HEAD, l.stream().map(this::actRow).toList())));
    }

    @SuppressWarnings("unchecked")
    public byte[] report(InventoryService.Filter f) {
        Thresholds t = settings.thresholds();
        List<CalcItem> d = inventory.calculated(f);
        Map<String, Object> a = analytics.agg(d, t);
        List<List<Object>> sum = new ArrayList<>();
        sum.add(List.of("Unichem Inventory Management — Management Report"));
        sum.add(List.of("Generated", LocalDate.now().toString()));
        sum.add(List.of()); sum.add(List.of("KPI", "Value (₹)", "Count"));
        sum.add(List.of("Total inventory value", Math.round((double) a.get("v")), a.get("n")));
        sum.add(List.of("Unrestricted", Math.round((double) a.get("u")), ""));
        sum.add(List.of("Quality", Math.round((double) a.get("q")), ""));
        sum.add(List.of("Blocked", Math.round((double) a.get("b")), ""));
        sum.add(List.of("Aged >" + t.agingDays() + " days", Math.round((double) a.get("aged")), a.get("agedN")));
        sum.add(List.of("Expiry ≤" + t.expiryDays() + " days", Math.round((double) a.get("exp")), a.get("expN")));
        sum.add(List.of("Expired", Math.round((double) a.get("expd")), a.get("expdN")));
        sum.add(List.of("Consumables", Math.round((double) a.get("cons")), a.get("consN")));
        sum.add(List.of()); sum.add(List.of("Category", "Unrestricted", "Quality", "Blocked", "Total"));
        for (String c : MAT_TYPES) {
            List<CalcItem> r = d.stream().filter(x -> c.equals(x.matType())).toList();
            sum.add(List.of(c, Math.round(AnalyticsService.sum(r, CalcItem::uVal)), Math.round(AnalyticsService.sum(r, CalcItem::qVal)),
                    Math.round(AnalyticsService.sum(r, CalcItem::bVal)), Math.round(AnalyticsService.sum(r, CalcItem::value))));
        }
        Map<String, List<List<Object>>> sheets = new LinkedHashMap<>();
        sheets.put("Summary", sum);
        sheets.put("Inventory", sheet(INV_HEAD, d.stream().map(x -> invRow(x, t)).toList()));
        sheets.put("Expiry le " + t.expiryDays() + "d", sheet(INV_HEAD, d.stream().filter(x -> x.rsl() != null && x.rsl() <= t.expiryDays()).sorted(Comparator.comparing(CalcItem::rsl)).map(x -> invRow(x, t)).toList()));
        sheets.put("Aged gt " + t.agingDays() + "d", sheet(INV_HEAD, d.stream().filter(x -> x.age() != null && x.age() > t.agingDays()).sorted(Comparator.comparing(CalcItem::age).reversed()).map(x -> invRow(x, t)).toList()));
        sheets.put("Consumables", sheet(INV_HEAD, d.stream().filter(x -> "Consumable".equals(x.matType())).map(x -> invRow(x, t)).toList()));
        sheets.put("Exceptions", sheet(INV_HEAD, d.stream().filter(x -> !x.realFlags().isEmpty()).sorted(Comparator.comparingInt(CalcItem::score).reversed()).map(x -> invRow(x, t)).toList()));
        sheets.put("Actions", sheet(ACT_HEAD, actions.visible().stream().map(this::actRow).toList()));
        audit.log("Exported", "Management report (Excel)");
        return book(sheets);
    }

    public byte[] template(String kind) {
        List<String> head = "sap".equals(kind) ? ImportService.SAP_COLUMNS : ImportService.APP_COLUMNS;
        List<List<Object>> rows = new ArrayList<>();
        rows.add(new ArrayList<>(head));
        if ("sap".equals(kind)) rows.add(Arrays.asList("110034501", "Lactose monohydrate IP", "1200", "RM01", "ZRAW", "R007", "KG", "RM25A0141", 1200, 252000,
                0, 0, 0, 0, 0, 0, 0, 0, "12.01.2025", "11.01.2027", "05.02.2025", "14.10.2026"));
        else rows.add(Arrays.asList("Formulation", "GOA-1", "ZRAW", "", "", "RM-1102-0045", "Lactose monohydrate IP", "B2201", "KG", "RM01", 1200, 0, 0, 210,
                "01.08.2026", "", "10.07.2026", "09.07.2028", "Retest", "", "", ""));
        return book(Map.of("sap".equals(kind) ? "SAP Stock" : "Inventory", rows));
    }

    private static List<List<Object>> sheet(String[] head, List<List<Object>> rows) {
        List<List<Object>> out = new ArrayList<>();
        out.add(Arrays.asList((Object[]) head));
        out.addAll(rows);
        return out;
    }

    private static byte[] book(Map<String, List<List<Object>>> sheets) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            CellStyle hs = wb.createCellStyle(); Font hf = wb.createFont(); hf.setBold(true); hs.setFont(hf);
            hs.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex()); hs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle ds = wb.createCellStyle(); ds.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd.mm.yyyy"));
            for (var e : sheets.entrySet()) {
                Sheet sh = wb.createSheet(e.getKey().length() > 31 ? e.getKey().substring(0, 31) : e.getKey());
                int r = 0;
                for (List<Object> row : e.getValue()) {
                    Row xr = sh.createRow(r);
                    for (int c = 0; c < row.size(); c++) {
                        Object v = row.get(c); Cell cell = xr.createCell(c);
                        if (v instanceof Number n) cell.setCellValue(n.doubleValue());
                        else if (v instanceof LocalDate d) { cell.setCellValue(d); cell.setCellStyle(ds); }
                        else if (v != null) cell.setCellValue(v.toString());
                        if (r == 0 && !"Summary".equals(e.getKey())) cell.setCellStyle(hs);
                    }
                    r++;
                }
                if (!"Summary".equals(e.getKey())) sh.createFreezePane(0, 1);
                int cols = e.getValue().isEmpty() ? 0 : e.getValue().get(0).size();
                for (int c = 0; c < cols; c++) sh.setColumnWidth(c, 18 * 256);
            }
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception ex) { throw new IllegalStateException("Could not build Excel file: " + ex.getMessage(), ex); }
    }
}
