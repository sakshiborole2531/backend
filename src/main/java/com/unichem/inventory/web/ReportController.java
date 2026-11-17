package com.unichem.inventory.web;

import com.unichem.inventory.dto.Dtos.ReportFilterRequest;
import com.unichem.inventory.dto.Dtos.ReportSummaryResponseDto;
import com.unichem.inventory.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Reporting APIs: dashboard, matrix, year-wise comparison, multi-level BU/Unit summary, PDF & Excel management reports, snapshots. */
@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final AnalyticsService analytics;
    private final SnapshotService snapshots;
    private final ExportService export;
    private final CurrentUserService current;
    private final AuditService audit;
    private final ReportService reportService;

    @Autowired
    public ReportController(
            AnalyticsService analytics,
            SnapshotService snapshots,
            ExportService export,
            CurrentUserService current,
            AuditService audit,
            @Autowired(required = false) ReportService reportService) {
        this.analytics = analytics;
        this.snapshots = snapshots;
        this.export = export;
        this.current = current;
        this.audit = audit;
        this.reportService = reportService;
    }

    public ReportController(
            AnalyticsService analytics,
            SnapshotService snapshots,
            ExportService export,
            CurrentUserService current,
            AuditService audit) {
        this(analytics, snapshots, export, current, audit, null);
    }

    @GetMapping("/dashboard") @PreAuthorize("hasAuthority('view.inventory')")
    public Map<String, Object> dashboard(@RequestParam(required = false) String bu, @RequestParam(required = false) String plant,
                                         @RequestParam(required = false) String cat, @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String dept, @RequestParam(required = false) String loc,
                                         @RequestParam(required = false) String q) {
        return analytics.dashboard(InventoryController.filter(bu, plant, cat, status, dept, loc, q, null));
    }

    @GetMapping("/matrix") @PreAuthorize("hasAuthority('view.inventory')")
    public Map<String, Object> matrix(@RequestParam(defaultValue = "value") String measure, @RequestParam(required = false) String bu,
                                      @RequestParam(required = false) String cat, @RequestParam(required = false) String dept,
                                      @RequestParam(required = false) String q) {
        return analytics.matrix(InventoryController.filter(bu, null, cat, null, dept, null, q, null), measure);
    }

    @GetMapping("/yearwise") @PreAuthorize("hasAuthority('view.years')")
    public Map<String, Object> yearwise(@RequestParam(defaultValue = "close") String basis, @RequestParam(required = false) String bu,
                                        @RequestParam(required = false) String plant) {
        return snapshots.yearwise(basis, bu, plant);
    }

    /**
     * Multi-level Business Unit-wise and Unit-wise summarized inventory report data (GET).
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyAuthority('view.inventory','report.pdf','export.data')")
    public ResponseEntity<ReportSummaryResponseDto> getSummary(
            @RequestParam(required = false) String reportType,
            @RequestParam(required = false) String repType,
            @RequestParam(required = false) String bu,
            @RequestParam(required = false) String selectedBu,
            @RequestParam(required = false) List<String> bus,
            @RequestParam(required = false) String plant,
            @RequestParam(required = false) List<String> units,
            @RequestParam(required = false) List<String> plants,
            @RequestParam(defaultValue = "all") String dataScope,
            @RequestParam(required = false) String cat,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String dept,
            @RequestParam(required = false) String loc,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Double minValue,
            @RequestParam(required = false) List<String> sections,
            @RequestParam(defaultValue = "10") Integer topN,
            @RequestParam(required = false) String preparedBy) {
        ReportFilterRequest req = new ReportFilterRequest(
                reportType, repType, bu, selectedBu, bus, null,
                plant, units, null, plants,
                dataScope, cat, status, dept, loc, q, minValue,
                null, sections, topN, preparedBy
        );
        return ResponseEntity.ok(reportService.summarize(req));
    }

    /**
     * Multi-level Business Unit-wise and Unit-wise summarized inventory report data (POST).
     */
    @PostMapping({"/generate", "/summary"})
    @PreAuthorize("hasAnyAuthority('view.inventory','report.pdf','export.data')")
    public ResponseEntity<ReportSummaryResponseDto> generateSummary(
            @RequestBody(required = false) ReportFilterRequest request) {
        return ResponseEntity.ok(reportService.summarize(request));
    }

    /**
     * Server-side A4 Landscape PDF stream generation (GET).
     */
    @GetMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyAuthority('report.pdf','export.data','view.inventory')")
    public ResponseEntity<byte[]> downloadPdfGet(
            @RequestParam(required = false) String reportType,
            @RequestParam(required = false) String repType,
            @RequestParam(required = false) String bu,
            @RequestParam(required = false) String selectedBu,
            @RequestParam(required = false) List<String> bus,
            @RequestParam(required = false) String plant,
            @RequestParam(required = false) List<String> units,
            @RequestParam(required = false) List<String> plants,
            @RequestParam(defaultValue = "all") String dataScope,
            @RequestParam(required = false) String cat,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String dept,
            @RequestParam(required = false) String loc,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Double minValue,
            @RequestParam(required = false) List<String> sections,
            @RequestParam(defaultValue = "10") Integer topN,
            @RequestParam(required = false) String preparedBy) {
        ReportFilterRequest req = new ReportFilterRequest(
                reportType, repType, bu, selectedBu, bus, null,
                plant, units, null, plants,
                dataScope, cat, status, dept, loc, q, minValue,
                null, sections, topN, preparedBy
        );
        return pdfResponse(reportService.generatePdf(req), reportType != null ? reportType : repType);
    }

    /**
     * Server-side A4 Landscape PDF stream generation (POST).
     */
    @PostMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyAuthority('report.pdf','export.data','view.inventory')")
    public ResponseEntity<byte[]> downloadPdfPost(
            @RequestBody(required = false) ReportFilterRequest request) {
        String rt = request != null ? (request.reportType() != null ? request.reportType() : request.repType()) : "both";
        return pdfResponse(reportService.generatePdf(request), rt);
    }

    private static ResponseEntity<byte[]> pdfResponse(byte[] pdfBytes, String repType) {
        String safeType = (repType == null || repType.isBlank()) ? "both" : repType.trim().toLowerCase();
        String fileName = "Unichem_Inventory_" + safeType + "_Report_" + LocalDate.now() + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    @PostMapping("/snapshots") @PreAuthorize("hasAnyAuthority('import.data','masters.edit')")
    public Map<String, Object> capture() {
        boolean ok = snapshots.take("manual", null, null);
        audit.log("Snapshot captured", LocalDate.now().toString());
        return Map.of("captured", ok);
    }

    @DeleteMapping("/snapshots/{period}") @PreAuthorize("hasAuthority('masters.edit')")
    public void deleteSnapshot(@PathVariable String period) { snapshots.delete(period); audit.log("Snapshot deleted", period); }

    @GetMapping("/management") @PreAuthorize("hasAuthority('export.data')")
    public ResponseEntity<byte[]> management(@RequestParam(required = false) String bu, @RequestParam(required = false) String plant) {
        return InventoryController.xlsx(export.report(InventoryController.filter(bu, plant, null, null, null, null, null, null)),
                "Unichem_Inventory_Report_" + LocalDate.now() + ".xlsx");
    }
}
