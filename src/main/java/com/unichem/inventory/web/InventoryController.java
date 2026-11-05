package com.unichem.inventory.web;

import com.unichem.inventory.dto.CalcItem;
import com.unichem.inventory.dto.Dtos.ImportResult;
import com.unichem.inventory.dto.Dtos.InventoryRequest;
import com.unichem.inventory.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {
    private final InventoryService inventory;
    private final ImportService importer;
    private final ExportService export;

    static InventoryService.Filter filter(String bu, String plant, String cat, String status, String dept, String loc, String q, Double minValue) {
        return new InventoryService.Filter(bu, plant, cat, status, dept, loc, q, minValue);
    }

    @GetMapping @PreAuthorize("hasAuthority('view.inventory')")
    public List<CalcItem> list(@RequestParam(required = false) String bu, @RequestParam(required = false) String plant,
                               @RequestParam(required = false) String cat, @RequestParam(required = false) String status,
                               @RequestParam(required = false) String dept, @RequestParam(required = false) String loc,
                               @RequestParam(required = false) String q, @RequestParam(required = false) Double minValue) {
        return inventory.calculated(filter(bu, plant, cat, status, dept, loc, q, minValue));
    }

    @GetMapping("/{id}") @PreAuthorize("hasAuthority('view.inventory')")
    public CalcItem one(@PathVariable Long id) { return inventory.one(id); }

    @PostMapping @PreAuthorize("hasAuthority('data.edit')")
    public CalcItem create(@Valid @RequestBody InventoryRequest r) { return inventory.create(r); }

    @PutMapping("/{id}") @PreAuthorize("hasAuthority('data.edit')")
    public CalcItem update(@PathVariable Long id, @Valid @RequestBody InventoryRequest r) { return inventory.update(id, r); }

    @DeleteMapping("/{id}") @PreAuthorize("hasAuthority('data.edit')")
    public void delete(@PathVariable Long id) { inventory.delete(id); }

    @GetMapping("/data-issues") @PreAuthorize("hasAnyAuthority('data.edit','data.approve')")
    public List<Map<String, Object>> issues() { return inventory.dataIssues(); }

    /** preview=true validates without saving; preview=false commits. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @PreAuthorize("hasAuthority('import.data')")
    public ImportResult importFile(@RequestParam MultipartFile file, @RequestParam(defaultValue = "AUTO") String format,
                                   @RequestParam(defaultValue = "upsert") String mode,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                   @RequestParam(defaultValue = "true") boolean preview) throws Exception {
        return importer.run(file, format, mode, asOf, !preview);
    }

    @GetMapping("/export") @PreAuthorize("hasAuthority('export.data')")
    public ResponseEntity<byte[]> exportXlsx(@RequestParam(required = false) String bu, @RequestParam(required = false) String plant,
                                             @RequestParam(required = false) String cat, @RequestParam(required = false) String q) {
        return xlsx(export.inventory(filter(bu, plant, cat, null, null, null, q, null)), "Unichem_Inventory_" + LocalDate.now() + ".xlsx");
    }

    @GetMapping("/template/{kind}") @PreAuthorize("hasAnyAuthority('import.data','export.data')")
    public ResponseEntity<byte[]> template(@PathVariable String kind) {
        return xlsx(export.template(kind), "sap".equals(kind) ? "Unichem_SAP_Stock_Template.xlsx" : "Unichem_Inventory_Template.xlsx");
    }

    static ResponseEntity<byte[]> xlsx(byte[] data, String name) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(data);
    }
}
