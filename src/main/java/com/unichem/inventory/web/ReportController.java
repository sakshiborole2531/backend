package com.unichem.inventory.web;

import com.unichem.inventory.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Reporting APIs: dashboard, matrix, year-wise comparison, management report, snapshots. */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {
    private final AnalyticsService analytics;
    private final SnapshotService snapshots;
    private final ExportService export;
    private final CurrentUserService current;
    private final AuditService audit;

    @GetMapping("/dashboard") @PreAuthorize("hasAuthority('view.inventory')")
    public Map<String, Object> dashboard(@RequestParam(required = false) String bu, @RequestParam(required = false) String plant,
                                         @RequestParam(required = false) String cat, @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String dept, @RequestParam(required = false) String q) {
        return analytics.dashboard(InventoryController.filter(bu, plant, cat, status, dept, null, q, null));
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
