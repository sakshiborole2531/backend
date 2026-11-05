package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "import_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ImportHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "configuration_id")
    private Long configurationId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_path", nullable = false, length = 760)
    private String filePath;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "file_modified_at")
    private OffsetDateTime fileModifiedAt;

    @Column(name = "import_type", nullable = false, length = 20)
    private String importType; // MANUAL | AUTOMATIC

    @Column(name = "import_started_at", nullable = false)
    private OffsetDateTime importStartedAt = OffsetDateTime.now();

    @Column(name = "import_completed_at")
    private OffsetDateTime importCompletedAt;

    @Column(name = "total_records", nullable = false)
    private int totalRecords;

    @Column(name = "successful_records", nullable = false)
    private int successfulRecords;

    @Column(name = "failed_records", nullable = false)
    private int failedRecords;

    @Column(name = "added_records", nullable = false)
    private int addedRecords;

    @Column(name = "updated_records", nullable = false)
    private int updatedRecords;

    @Column(nullable = false, length = 20)
    private String status = "PENDING"; // PENDING | RUNNING | SUCCESS | FAILED | CANCELLED

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "executed_by", length = 60)
    private String executedBy;

    @Column(name = "execution_duration", nullable = false)
    private long executionDuration;
}
