package com.unichem.inventory.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity @Table(name = "business_unit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class BusinessUnit {
    @Id
    private String code;

    @Column(name = "id", unique = true)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId = 1L;

    @Column(nullable = false)
    private String name;

    private String alias;

    private boolean active = true;

    @Column(name = "sort_order")
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public BusinessUnit(String code, String name, String alias) {
        this.code = code;
        this.name = name;
        this.alias = alias;
        this.active = true;
        this.sortOrder = 0;
        this.organizationId = 1L;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public BusinessUnit(String code, String name, String alias, boolean active, int sortOrder) {
        this.code = code;
        this.name = name;
        this.alias = alias;
        this.active = active;
        this.sortOrder = sortOrder;
        this.organizationId = 1L;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public BusinessUnit(Long id, String code, String name, String alias, boolean active, int sortOrder) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.alias = alias;
        this.active = active;
        this.sortOrder = sortOrder;
        this.organizationId = 1L;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (this.createdAt == null) this.createdAt = now;
        this.updatedAt = now;
        if (this.organizationId == null) this.organizationId = 1L;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
        if (this.organizationId == null) this.organizationId = 1L;
    }

    @JsonProperty("description")
    public String getDescription() {
        return this.name;
    }

    @JsonProperty("alsoKnownAs")
    public String getAlsoKnownAs() {
        return this.alias;
    }
}
