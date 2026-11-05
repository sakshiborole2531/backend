package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "automatic_import_configuration")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AutomaticImportConfiguration {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false, unique = true)
    private Long organizationId = 1L;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "configured_folder_path", nullable = false, length = 500)
    private String configuredFolderPath = "C:\\Inventory_Sap";

    @Column(name = "configured_file_name", nullable = false, length = 255)
    private String configuredFileName = "SAP_Inventory.xlsx";

    @Column(name = "configured_file_path", nullable = false, length = 760)
    private String configuredFilePath = "C:\\Inventory_Sap\\SAP_Inventory.xlsx";

    @Column(name = "import_frequency_hours", nullable = false)
    private int importFrequencyHours = 24;

    @Column(name = "import_mode", nullable = false, length = 40)
    private String importMode = "REPLACE_IN_SCOPE";

    @Column(name = "skip_unchanged_file", nullable = false)
    private boolean skipUnchangedFile = true;

    @Column(name = "scope_bu_code", length = 40)
    private String scopeBuCode;

    @Column(name = "scope_plant_name", length = 80)
    private String scopePlantName;

    @Column(name = "first_execution_at")
    private OffsetDateTime firstExecutionAt;

    @Column(name = "last_execution_at")
    private OffsetDateTime lastExecutionAt;

    @Column(name = "last_successful_import_at")
    private OffsetDateTime lastSuccessfulImportAt;

    @Column(name = "last_imported_file_modified_at")
    private OffsetDateTime lastImportedFileModifiedAt;

    @Column(name = "last_imported_file_size")
    private Long lastImportedFileSize;

    @Column(name = "next_execution_at")
    private OffsetDateTime nextExecutionAt;

    @Column(name = "last_import_status", nullable = false, length = 40)
    private String lastImportStatus = "DISABLED";

    @Column(name = "last_import_message", length = 2000)
    private String lastImportMessage;

    @Column(name = "lock_acquired_at")
    private OffsetDateTime lockAcquiredAt;

    @Column(name = "locked_by", length = 120)
    private String lockedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "created_by", length = 60)
    private String createdBy;

    @Column(name = "updated_by", length = 60)
    private String updatedBy;

    @Version
    private long version;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (this.createdAt == null) this.createdAt = now;
        this.updatedAt = now;
        if (this.organizationId == null) this.organizationId = 1L;
        if (this.importFrequencyHours <= 0) this.importFrequencyHours = 24;
        if (this.importMode == null || this.importMode.isBlank()) this.importMode = "REPLACE_IN_SCOPE";
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
