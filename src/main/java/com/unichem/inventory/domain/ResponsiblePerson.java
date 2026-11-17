package com.unichem.inventory.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "responsible_person")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class ResponsiblePerson {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    @Column(name = "function_name") private String functionName;
    private String email;
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
