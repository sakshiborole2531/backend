package com.unichem.inventory.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "material_group_map")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class MaterialGroupMap {
    @Id
    @Column(name = "sap_code")
    @JsonAlias({"code", "materialGroup"})
    private String sapCode;
    private String description;
    private String category;
    private String department;
    @Column(name = "sub_category") private String subCategory;
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
