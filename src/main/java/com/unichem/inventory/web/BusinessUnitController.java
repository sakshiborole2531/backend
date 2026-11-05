package com.unichem.inventory.web;

import com.unichem.inventory.dto.Dtos.*;
import com.unichem.inventory.service.MasterService;
import com.unichem.inventory.service.SettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST APIs for Business Units and Organization Sidebar Hierarchy.
 * Synchronizes Configuration -> Masters -> Business Units and Organization -> Business Units -> Plants.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BusinessUnitController {
    private final MasterService masters;
    private final SettingsService settings;

    /**
     * A. Get all Business Units
     * GET /api/business-units
     */
    @GetMapping("/business-units")
    public ApiResponse<List<BusinessUnitDto>> getAllBusinessUnits(
            @RequestParam(required = false) Long organizationId,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.ok(masters.getAllBusinessUnits(organizationId, includeInactive));
    }

    /**
     * B. Get Business Unit by ID
     * GET /api/business-units/{id}
     */
    @GetMapping("/business-units/{id}")
    public ResponseEntity<BusinessUnitDto> getBusinessUnitById(@PathVariable String id) {
        BusinessUnitDto dto = isNumeric(id)
                ? masters.getBusinessUnitById(Long.parseLong(id.trim()))
                : masters.getBusinessUnitByIdentifier(id);
        return ResponseEntity.ok(dto);
    }

    /**
     * C. Create Business Unit
     * POST /api/business-units
     */
    @PostMapping("/business-units")
    @PreAuthorize("hasAuthority('masters.edit')")
    public ResponseEntity<BusinessUnitDto> createBusinessUnit(@Valid @RequestBody BusinessUnitRequest request) {
        BusinessUnitDto created = masters.createBusinessUnit(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * D. Update Business Unit
     * PUT /api/business-units/{id}
     */
    @PutMapping("/business-units/{id}")
    @PreAuthorize("hasAuthority('masters.edit')")
    public ResponseEntity<BusinessUnitDto> updateBusinessUnit(
            @PathVariable String id,
            @Valid @RequestBody BusinessUnitRequest request) {
        BusinessUnitDto updated = isNumeric(id)
                ? masters.updateBusinessUnit(Long.parseLong(id.trim()), request)
                : masters.updateBusinessUnitByIdentifier(id, request);
        return ResponseEntity.ok(updated);
    }

    /**
     * E. Delete Business Unit
     * DELETE /api/business-units/{id}
     */
    @DeleteMapping("/business-units/{id}")
    @PreAuthorize("hasAuthority('masters.edit')")
    public ResponseEntity<Map<String, Object>> deleteBusinessUnit(@PathVariable String id) {
        if (isNumeric(id)) {
            masters.deleteBusinessUnit(Long.parseLong(id.trim()));
        } else {
            masters.deleteBusinessUnitByIdentifier(id);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Business Unit deleted successfully");
        return ResponseEntity.ok(body);
    }

    /**
     * F. Get Plants by Business Unit
     * GET /api/business-units/{id}/plants
     */
    @GetMapping("/business-units/{id}/plants")
    public ResponseEntity<List<PlantDto>> getPlantsByBusinessUnit(@PathVariable String id) {
        List<PlantDto> plants = isNumeric(id)
                ? masters.getPlantsByBusinessUnit(Long.parseLong(id.trim()))
                : masters.getPlantsByBusinessUnitIdentifier(id);
        return ResponseEntity.ok(plants);
    }

    /**
     * G. Get Organization Sidebar Hierarchy
     * GET /api/organizations/{organizationId}/hierarchy
     */
    @GetMapping("/organizations/{organizationId}/hierarchy")
    public ResponseEntity<OrganizationHierarchyDto> getOrganizationHierarchy(@PathVariable Long organizationId) {
        String companyShort = settings != null ? settings.get("companyShort", "Unichem") : "Unichem";
        return ResponseEntity.ok(masters.getOrganizationHierarchy(organizationId, companyShort));
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isBlank()) return false;
        String t = s.trim();
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) return false;
        }
        return true;
    }
}
