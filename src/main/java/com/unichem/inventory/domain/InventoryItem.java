package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity @Table(name = "inventory_item")
@Getter @Setter @NoArgsConstructor
public class InventoryItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "bu_code", nullable = false) private String buCode;
    @Column(nullable = false) private String plant;
    @Column(name = "mat_type", nullable = false) private String matType;
    private String department;
    @Column(name = "sub_category") private String subCategory;
    @Column(nullable = false) private String code;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private String batch = "";
    private String uom;
    @Column(name = "storage_loc") private String storageLoc;
    @Column(name = "unrestricted_qty") private BigDecimal unrestrictedQty = BigDecimal.ZERO;
    @Column(name = "quality_qty") private BigDecimal qualityQty = BigDecimal.ZERO;
    @Column(name = "blocked_qty") private BigDecimal blockedQty = BigDecimal.ZERO;
    @Column(name = "unit_cost") private BigDecimal unitCost = BigDecimal.ZERO;
    @Column(name = "receipt_date") private LocalDate receiptDate;
    @Column(name = "lying_since") private LocalDate lyingSince;
    @Column(name = "mfg_date") private LocalDate mfgDate;
    @Column(name = "expiry_date") private LocalDate expiryDate;
    @Column(name = "date_type") private String dateType = "Expiry";
    @Column(name = "last_issue_date") private LocalDate lastIssueDate;
    @Column(name = "block_reason") private String blockReason;
    private String remarks;
    @Column(name = "created_at") private OffsetDateTime createdAt = OffsetDateTime.now();
    @Column(name = "updated_at") private OffsetDateTime updatedAt = OffsetDateTime.now();
    @Version private long version;

    /** Natural key that links actions across SAP refreshes: plant|code|batch. */
    public String itemKey() { return plant + "|" + code + "|" + (batch == null ? "" : batch); }

    @PreUpdate void touch() { updatedAt = OffsetDateTime.now(); }
}
