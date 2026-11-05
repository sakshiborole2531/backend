package com.unichem.inventory.web;

import com.unichem.inventory.dto.Dtos.*;
import com.unichem.inventory.service.AutomaticImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for Automatic Daily SAP Inventory Import configuration, execution, status, and history.
 */
@RestController
@RequestMapping("/api/automatic-import")
@RequiredArgsConstructor
public class AutomaticImportController {
    private final AutomaticImportService automaticImportService;

    /**
     * Get configuration
     * GET /api/automatic-import/config
     */
    @GetMapping("/config")
    @PreAuthorize("hasAnyAuthority('sap.config','masters.edit','import.data')")
    public ResponseEntity<AutomaticImportConfigDto> getConfig(
            @RequestParam(required = false) Long organizationId) {
        return ResponseEntity.ok(automaticImportService.getConfig(organizationId));
    }

    /**
     * Save configuration
     * PUT /api/automatic-import/config
     */
    @PutMapping("/config")
    @PreAuthorize("hasAnyAuthority('sap.config','masters.edit')")
    public ResponseEntity<AutomaticImportConfigDto> saveConfig(
            @Valid @RequestBody AutomaticImportConfigRequest request) {
        return ResponseEntity.ok(automaticImportService.saveConfig(request));
    }

    /**
     * Enable automation
     * POST /api/automatic-import/enable
     */
    @PostMapping("/enable")
    @PreAuthorize("hasAnyAuthority('sap.config','masters.edit')")
    public ResponseEntity<AutomaticImportStatusDto> enable(
            @RequestParam(required = false) Long organizationId) {
        return ResponseEntity.ok(automaticImportService.enable(organizationId));
    }

    /**
     * Disable automation
     * POST /api/automatic-import/disable
     */
    @PostMapping("/disable")
    @PreAuthorize("hasAnyAuthority('sap.config','masters.edit')")
    public ResponseEntity<AutomaticImportStatusDto> disable(
            @RequestParam(required = false) Long organizationId) {
        return ResponseEntity.ok(automaticImportService.disable(organizationId));
    }

    /**
     * Run import immediately
     * POST /api/automatic-import/run-now
     */
    @PostMapping("/run-now")
    @PreAuthorize("hasAnyAuthority('import.data','sap.config','masters.edit')")
    public ResponseEntity<ImportHistoryDto> runNow(
            @RequestParam(required = false) Long organizationId,
            @RequestParam(defaultValue = "true") boolean force) {
        return ResponseEntity.ok(automaticImportService.runNow(organizationId, force));
    }

    /**
     * Get automation status
     * GET /api/automatic-import/status
     */
    @GetMapping("/status")
    @PreAuthorize("hasAnyAuthority('sap.config','masters.edit','import.data','view.inventory')")
    public ResponseEntity<AutomaticImportStatusDto> getStatus(
            @RequestParam(required = false) Long organizationId) {
        return ResponseEntity.ok(automaticImportService.getStatus(organizationId));
    }

    /**
     * Get import history (paginated & sorted by importStartedAt DESC)
     * GET /api/automatic-import/history
     */
    @GetMapping("/history")
    @PreAuthorize("hasAnyAuthority('sap.config','masters.edit','import.data','audit.view')")
    public ResponseEntity<Page<ImportHistoryDto>> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(automaticImportService.getHistory(page, size));
    }

    /**
     * Get import history details by ID
     * GET /api/automatic-import/history/{id}
     */
    @GetMapping("/history/{id}")
    @PreAuthorize("hasAnyAuthority('sap.config','masters.edit','import.data','audit.view')")
    public ResponseEntity<ImportHistoryDto> getHistoryById(@PathVariable Long id) {
        return ResponseEntity.ok(automaticImportService.getHistoryById(id));
    }
}
