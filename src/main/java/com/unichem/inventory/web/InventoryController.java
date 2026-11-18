package com.unichem.inventory.web;

import com.unichem.inventory.dto.CalcItem;
import com.unichem.inventory.dto.Dtos.ImportResult;
import com.unichem.inventory.dto.Dtos.InventoryRequest;
import com.unichem.inventory.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.EmptyFileException;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.openxml4j.exceptions.OLE2NotOfficeXmlFileException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
@Slf4j
public class InventoryController {
    private final InventoryService inventory;
    private final ImportService importer;
    private final ExportService export;

    private static final ExecutorService IMPORT_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "inventory-import-worker");
                t.setDaemon(true);
                return t;
            }
    );

    static InventoryService.Filter filter(String bu, String plant, String cat, String status, String dept, String loc, String q, Double minValue, LocalDate from, LocalDate to) {
        return new InventoryService.Filter(bu, plant, cat, status, dept, loc, q, minValue, from, to);
    }

    static InventoryService.Filter filter(String bu, String plant, String cat, String status, String dept, String loc, String q, Double minValue) {
        return new InventoryService.Filter(bu, plant, cat, status, dept, loc, q, minValue, null, null);
    }

    @GetMapping @PreAuthorize("hasAuthority('view.inventory')")
    public List<CalcItem> list(@RequestParam(required = false) String bu, @RequestParam(required = false) String plant,
                               @RequestParam(required = false) String cat, @RequestParam(required = false) String status,
                               @RequestParam(required = false) String dept, @RequestParam(required = false) String loc,
                               @RequestParam(required = false) String q, @RequestParam(required = false) Double minValue, @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        return inventory.calculated(filter(bu, plant, cat, status, dept, loc, q, minValue, from, to));
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
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('import.data')")
    public ResponseEntity<?> importFile(@RequestParam(value = "file", required = false) MultipartFile file,
                                        @RequestParam(defaultValue = "AUTO") String format,
                                        @RequestParam(defaultValue = "upsert") String mode,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                        @RequestParam(defaultValue = "true") boolean preview) {
        CompletableFuture<ImportResult> future = null;
        try {
            if (file == null || file.isEmpty()) {
                return buildErrorResponse(HttpStatus.BAD_REQUEST, "Please select a non-empty Excel or CSV file to import.");
            }
            byte[] fileBytes = file.getBytes();
            String originalFilename = file.getOriginalFilename();
            SecurityContext securityContext = SecurityContextHolder.getContext();

            future = CompletableFuture.supplyAsync(() -> {
                SecurityContext previous = SecurityContextHolder.getContext();
                try {
                    SecurityContextHolder.setContext(securityContext);
                    return importer.runBytes(fileBytes, originalFilename, format, mode, asOf, !preview);
                } catch (Exception e) {
                    throw new CompletionException(e);
                } finally {
                    SecurityContextHolder.setContext(previous);
                }
            }, IMPORT_EXECUTOR);

            ImportResult result = future.get(ImportService.IMPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return ResponseEntity.ok(result);
        } catch (TimeoutException te) {
            if (future != null) {
                future.cancel(true);
            }
            String msg = "Inventory import timed out after " + ImportService.IMPORT_TIMEOUT_SECONDS + " seconds. The file may be too large or the database is busy.";
            log.error("Timeout during /api/inventory/import for file {}: {}", file != null ? file.getOriginalFilename() : "unknown", msg, te);
            return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, msg);
        } catch (Throwable ex) {
            Throwable cause = unwrap(ex);
            log.error("Error during /api/inventory/import for file {}: {}",
                    file != null ? file.getOriginalFilename() : "unknown", cause.getMessage(), cause);

            if (cause instanceof AccessDeniedException) {
                return buildErrorResponse(HttpStatus.FORBIDDEN,
                        cause.getMessage() != null ? cause.getMessage() : "Access denied for inventory import.");
            }
            if (cause instanceof IllegalArgumentException
                    || cause instanceof InvalidFormatException
                    || cause instanceof NotOfficeXmlFileException
                    || cause instanceof OLE2NotOfficeXmlFileException
                    || cause instanceof EmptyFileException
                    || cause instanceof EncryptedDocumentException
                    || cause instanceof MultipartException) {
                String msg = cause.getMessage() != null && !cause.getMessage().isBlank()
                        ? cause.getMessage()
                        : "Invalid inventory import file or format.";
                return buildErrorResponse(HttpStatus.BAD_REQUEST, msg);
            }
            if (cause instanceof DataIntegrityViolationException dive) {
                String specific = dive.getMostSpecificCause() != null ? dive.getMostSpecificCause().getMessage() : dive.getMessage();
                String msg = "Database constraint violation during import: " + (specific != null ? specific.split("\n")[0] : "invalid data");
                return buildErrorResponse(HttpStatus.BAD_REQUEST, msg);
            }
            if (cause instanceof TransactionTimedOutException) {
                return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Database transaction timed out after " + ImportService.IMPORT_TIMEOUT_SECONDS + " seconds.");
            }
            String msg = cause.getMessage() != null && !cause.getMessage().isBlank()
                    ? cause.getMessage()
                    : "Unexpected error during inventory import (" + cause.getClass().getSimpleName() + ")";
            return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, msg);
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while ((cur instanceof ExecutionException || cur instanceof CompletionException) && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }

    private static ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("title", status.getReasonPhrase());
        body.put("message", message);
        body.put("detail", message);
        return ResponseEntity.status(status).body(body);
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
