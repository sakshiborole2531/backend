package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity @Table(name = "action_item")
@Getter @Setter @NoArgsConstructor
public class ActionItem {
    @Id private String id;
    @Column(name = "item_key") private String itemKey;
    @Column(name = "bu_code") private String buCode;
    private String plant;
    private String code;
    private String name;
    private String batch;
    @Column(name = "mat_type") private String matType;
    @Column(name = "value_at_raise") private BigDecimal valueAtRaise;
    private String exception;
    @Column(name = "action_type", nullable = false) private String actionType;
    @Column(nullable = false) private String plan;
    @Column(nullable = false) private String priority = "P3";
    @Column(name = "function_name", nullable = false) private String functionName;
    private String person;
    @Column(name = "created_date") private LocalDate createdDate = LocalDate.now();
    @Column(name = "target_date") private LocalDate targetDate;
    @Column(nullable = false) private String stage = "Prioritized";
    private String remarks;
    @Column(name = "closed_date") private LocalDate closedDate;
    private String closure;
    @Column(name = "closed_by") private String closedBy;
    @Column(name = "created_by") private String createdBy;
    @Column(name = "updated_at") private OffsetDateTime updatedAt = OffsetDateTime.now();
    @Version private long version;

    @PreUpdate void touch() { updatedAt = OffsetDateTime.now(); }
}
