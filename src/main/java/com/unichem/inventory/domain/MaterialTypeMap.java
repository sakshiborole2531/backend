package com.unichem.inventory.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "material_type_map", indexes = {
        @Index(name = "uq_material_type_composite", columnList = "sap_code, category, business_unit")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class MaterialTypeMap {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sap_code", nullable = false)
    @JsonAlias({"materialType", "code"})
    private String sapCode;
    private String description;
    @Column(name = "number_series") private String numberSeries;
    @Column(nullable = false)
    @JsonAlias({"dashboardCategory"})
    private String category;
    @Column(name = "business_unit") private String businessUnit;
    @JsonAlias({"defaultDepartment"})
    private String department;
    @Column(nullable = false)
    @JsonAlias({"is_active", "isActive"})
    private Boolean active = true;
    @Column(name = "sort_order") private int sortOrder;

    public boolean isActive() {
        return !Boolean.FALSE.equals(this.active);
    }

    public Boolean getActive() {
        return this.active != null ? this.active : Boolean.TRUE;
    }

    public void setActive(Boolean active) {
        this.active = active != null ? active : Boolean.TRUE;
    }

    @JsonProperty("is_active")
    public Boolean getIsActive() {
        return getActive();
    }
}
