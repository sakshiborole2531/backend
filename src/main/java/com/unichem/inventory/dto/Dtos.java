package com.unichem.inventory.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Request / response payloads. */
public final class Dtos {
    private Dtos() {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record SetupRequest(@NotBlank String name, @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{3,}") String username,
                               @Email String email, String designation, @NotBlank String password) {}
    public record ChangePasswordRequest(String currentPassword, @NotBlank String newPassword) {}
    public record TokenResponse(String token, MeResponse user) {}
    public record MeResponse(Long id, String username, String name, String email, String phone, String empId,
                             String designation, String department, String location, String functionName,
                             String role, String roleLabel, boolean scopeAll, Set<String> scopeBus, Set<String> scopeUnits,
                             Set<String> permissions, boolean mustChange, String theme, String lastLogin, String validUntil) {}
    public record ProfileUpdate(@NotBlank String name, @Email String email, String phone, String empId, String designation,
                                String department, String location, String theme) {}

    public record InventoryRequest(
            @NotBlank String plant, @NotBlank String matType, String dept, String subCat,
            @NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 200) String name, @Size(max = 40) String batch,
            String uom, String storageLoc,
            @PositiveOrZero BigDecimal unrestrictedQty, @PositiveOrZero BigDecimal qualityQty, @PositiveOrZero BigDecimal blockedQty,
            @NotNull @PositiveOrZero BigDecimal unitCost,
            LocalDate receiptDate, LocalDate lyingSince, LocalDate mfgDate, LocalDate expiryDate, String dateType,
            LocalDate lastIssueDate, String blockReason, String remarks) {}

    public record ActionRequest(Long inventoryId, String name, String exception, @NotBlank String actionType,
                                @Pattern(regexp = "P[123]") String priority, @NotBlank String functionName, String person,
                                @NotBlank @Size(max = 1000) String plan, LocalDate targetDate, String stage, String remarks) {}
    public record CloseRequest(@NotNull LocalDate closedDate, @NotBlank String outcome, @NotBlank String closure) {}

    public record ImportRow(int row, String status, Map<String, Object> record, List<String> errors, List<String> warnings) {}
    public record ImportResult(String file, String format, String mode, int rowsRead, int ready, int warnings, int skipped,
                               int added, int updated, double value, List<String> missingColumns, List<ImportRow> rows, boolean committed) {}

    public record UserRequest(@NotBlank String name, @Pattern(regexp = "[A-Za-z0-9._-]{3,}") String username,
                              @Email String email, String phone, String empId, String designation, String department,
                              String location, @NotBlank String role, String functionName, boolean scopeAll,
                              Set<String> scopeBus, Set<String> scopeUnits, Boolean active, LocalDate validUntil,
                              List<GrantDto> grants, String reason, String password) {}
    public record GrantDto(String permission, String mode, LocalDate until) {}
    public record UserCreated(Long id, String username, String temporaryPassword) {}
    public record RoleRequest(@NotBlank String label, String description, Set<String> permissions) {}
    public record AccessRequestDto(@NotBlank String name, @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{3,}") String username,
                                   @NotBlank @Email String email, String empId, String designation, String department,
                                   String phone, String bu, String unit, String role, @NotBlank String justification) {}

    public record PlantSidebarDto(Long id, String code, String name, boolean active, int order, String buCode, String city) {}
    public record BusinessUnitSidebarDto(String id, String code, String name, boolean active, int order, String alias, int plantCount) {}
    public record StorageLocationSidebarDto(Long id, String code, String name, String plantCode, String plantName, String storageType, boolean active, int order) {}
    public record MaterialTypeSidebarDto(String id, String code, String name, boolean active, int order, String category, String department) {}
    public record SidebarCountsDto(int plants, int businessUnits, int storageLocations, int materialTypes) {}
    public record SidebarResponseDto(
            List<PlantSidebarDto> plants,
            List<BusinessUnitSidebarDto> businessUnits,
            List<StorageLocationSidebarDto> storageLocations,
            List<MaterialTypeSidebarDto> materialTypes,
            SidebarCountsDto counts) {}

    public record ApiResponse<T>(boolean success, T data) {
        public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(true, data); }
    }

    public record BusinessUnitRequest(
            Long id,
            Long organizationId,
            String name,
            String code,
            String description,
            String alsoKnownAs,
            String alias,
            Boolean active,
            Integer sortOrder) {}

    public record BusinessUnitDto(
            Long id,
            Long organizationId,
            String name,
            String code,
            String description,
            String alsoKnownAs,
            String alias,
            boolean active,
            int sortOrder,
            java.time.OffsetDateTime createdAt,
            java.time.OffsetDateTime updatedAt) {}

    public record PlantDto(
            Long id,
            String sapCode,
            String name,
            Long businessUnitId,
            String buCode,
            String city,
            boolean active,
            int sortOrder) {}

    public record HierarchyPlantDto(Long id, String name, String sapCode, Long businessUnitId, boolean active) {}

    public record HierarchyBusinessUnitDto(
            Long id,
            String name,
            String description,
            String alsoKnownAs,
            boolean active,
            List<HierarchyPlantDto> plants) {}

    public record OrganizationHierarchyDto(
            Long organizationId,
            String organizationName,
            List<HierarchyBusinessUnitDto> businessUnits) {}
}
