package com.unichem.inventory.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "responsible_function")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class ResponsibleFunction {
    @Id
    @JsonAlias({"v", "functionName"})
    private String name;

    @Column(nullable = false)
    @JsonAlias({"is_active", "isActive"})
    private Boolean active = true;

    @Column(name = "sort_order")
    private int sortOrder;

    public ResponsibleFunction(String name, int sortOrder) {
        this.name = name;
        this.active = true;
        this.sortOrder = sortOrder;
    }

    @JsonProperty("v")
    public String getV() {
        return this.name;
    }

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
