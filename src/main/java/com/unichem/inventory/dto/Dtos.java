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
                             Set<String> permissions, List<String> allowedBusinessUnits, boolean mustChange, String theme,
                             String lastLogin, String validUntil) {}
    public record UserPermissionsResponse(Set<String> actions, List<String> businessUnits, List<String> plants, boolean scopeAll) {}
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
    public record MaterialTypeSidebarDto(String id, String code, String name, boolean active, int order, String category, String businessUnit, String department) {}

    public record MaterialTypeRequest(
            String id,
            String materialType,
            String sapCode,
            String description,
            String numberSeries,
            String dashboardCategory,
            String category,
            String businessUnit,
            String defaultDepartment,
            String department,
            Boolean active,
            Integer sortOrder) {}

    public record MaterialTypeResponse(
            String id,
            String materialType,
            String sapCode,
            String description,
            String numberSeries,
            String dashboardCategory,
            String category,
            String businessUnit,
            String defaultDepartment,
            String department,
            boolean active,
            int sortOrder) {

        public static MaterialTypeResponse from(com.unichem.inventory.domain.MaterialTypeMap m) {
            if (m == null) return null;
            String idStr = m.getId() != null ? String.valueOf(m.getId()) : m.getSapCode();
            return new MaterialTypeResponse(
                    idStr,
                    m.getSapCode(),
                    m.getSapCode(),
                    m.getDescription(),
                    m.getNumberSeries(),
                    m.getCategory(),
                    m.getCategory(),
                    m.getBusinessUnit(),
                    m.getDepartment(),
                    m.getDepartment(),
                    m.isActive(),
                    m.getSortOrder()
            );
        }
    }
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

    public record StorageLocationRequest(
            Long id,
            String code,
            String name,
            String plantName,
            String storageType,
            Boolean active,
            Integer sortOrder) {}

    public record StorageLocationDto(
            Long id,
            String code,
            String name,
            String plantName,
            String storageType,
            boolean active,
            int sortOrder) {}

    public record MaterialGroupRequest(
            String sapCode,
            String description,
            String category,
            String department,
            String subCategory,
            Boolean active,
            Integer sortOrder) {}

    public record MaterialGroupDto(
            String sapCode,
            String description,
            String category,
            String department,
            String subCategory,
            boolean active,
            int sortOrder) {}

    public record ResponsibleFunctionDto(
            String name,
            String v,
            boolean active,
            int sortOrder) {}

    public record ActionTypeDto(
            String name,
            String v,
            boolean active,
            int sortOrder) {}

    public record ResponsiblePersonDto(
            Long id,
            String name,
            String functionName,
            String email,
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

    public record AutomaticImportConfigRequest(
            Long organizationId,
            String configuredFolderPath,
            String configuredFileName,
            String configuredFilePath,
            Integer importFrequencyHours,
            String importMode,
            Boolean enabled,
            Boolean skipUnchangedFile,
            String scopeBuCode,
            String scopePlantName) {}

    public record AutomaticImportConfigDto(
            Long id,
            Long organizationId,
            boolean enabled,
            String configuredFolderPath,
            String configuredFileName,
            String configuredFilePath,
            int importFrequencyHours,
            String importMode,
            boolean skipUnchangedFile,
            String scopeBuCode,
            String scopePlantName,
            java.time.OffsetDateTime firstExecutionAt,
            java.time.OffsetDateTime lastExecutionAt,
            java.time.OffsetDateTime lastSuccessfulImportAt,
            java.time.OffsetDateTime nextExecutionAt,
            String lastImportStatus,
            String lastImportMessage,
            java.time.OffsetDateTime createdAt,
            java.time.OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy) {}

    public record AutomaticImportStatusDto(
            boolean enabled,
            String status,
            String configuredFolderPath,
            String configuredFileName,
            String configuredFilePath,
            int importFrequencyHours,
            String importMode,
            java.time.OffsetDateTime firstExecutionAt,
            java.time.OffsetDateTime lastExecutionAt,
            java.time.OffsetDateTime lastSuccessfulImportAt,
            java.time.OffsetDateTime nextExecutionAt,
            String lastImportMessage) {}

    public record ImportHistoryDto(
            Long id,
            Long configurationId,
            String fileName,
            String filePath,
            long fileSize,
            java.time.OffsetDateTime fileModifiedAt,
            String importType,
            java.time.OffsetDateTime importStartedAt,
            java.time.OffsetDateTime importCompletedAt,
            int totalRecords,
            int successfulRecords,
            int failedRecords,
            int addedRecords,
            int updatedRecords,
            String status,
            String errorMessage,
            String executedBy,
            long executionDuration) {}

    // ---------- Multi-level PDF & Live Preview Report DTOs ----------
    public record ReportFilterRequest(
            String reportType,
            String repType,
            String bu,
            String selectedBu,
            List<String> bus,
            List<String> selectedBus,
            String plant,
            List<String> units,
            List<String> selectedUnits,
            List<String> plants,
            String dataScope,
            String cat,
            String status,
            String dept,
            String loc,
            String q,
            Double minValue,
            Map<String, Boolean> sections,
            List<String> sectionList,
            Integer topN,
            String preparedBy) {}

    public record ReportMetricsDto(
            int n,
            int batches,
            int mats,
            int materials,
            double v,
            double totalValue,
            double u,
            double unrestrictedValue,
            double q,
            double qualityValue,
            double b,
            double blockedValue,
            double aged,
            double agedValue,
            int agedN,
            int agedBatches,
            double exp,
            double expiryValue,
            int expN,
            int expiryBatches,
            double expd,
            double expiredValue,
            int expdN,
            int expiredBatches,
            double cons,
            double consumablesValue,
            int consN,
            int consumablesBatches,
            double idle,
            double idleValue,
            int idleN,
            int idleBatches,
            int exc,
            int exceptionCount,
            double excV,
            double exceptionValue,
            int p1,
            int p2,
            int p3,
            int openActions,
            int overdueActions,
            int totalActions) {}

    public record ReportActionCountsDto(
            int open,
            int od,
            int overdue,
            int closed,
            int total) {}

    public record BuSummaryRowDto(
            String bu,
            String desc,
            String description,
            int unitsCount,
            ReportMetricsDto agg,
            ReportActionCountsDto ac) {}

    public record UnitSummaryRowDto(
            String kind,
            String bu,
            String unit,
            String sap,
            String sapCode,
            String city,
            ReportMetricsDto agg,
            ReportActionCountsDto ac) {}

    public record ReportTotalSummaryDto(
            int buCount,
            int unitsCount,
            ReportMetricsDto agg,
            ReportActionCountsDto ac) {}

    public record ReportSummaryResponseDto(
            String reportType,
            String selectedBu,
            List<String> selectedBus,
            List<String> selectedUnits,
            String dataScope,
            String preparedBy,
            String generatedAt,
            String companyName,
            String companyShort,
            String syncSource,
            Thresholds thresholds,
            List<String> activeBus,
            List<String> activeUnits,
            Map<String, Boolean> sections,
            int topN,
            ReportTotalSummaryDto totalSummary,
            List<BuSummaryRowDto> buSummaryRows,
            List<UnitSummaryRowDto> unitSummaryRows,
            List<Map<String, Object>> byCategory,
            List<Map<String, Object>> agingBuckets,
            List<CalcItem> expiryBatches,
            List<CalcItem> slowMovingBatches,
            List<Map<String, Object>> consumablesByDept,
            Map<String, Object> yearwise,
            List<Map<String, Object>> openActions,
            List<Map<String, Object>> unitDetails) {}
}
