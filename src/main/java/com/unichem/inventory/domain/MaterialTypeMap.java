package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "material_type_map")
@Getter @Setter @NoArgsConstructor
public class MaterialTypeMap {
    @Id @Column(name = "sap_code") private String sapCode;
    private String description;
    @Column(name = "number_series") private String numberSeries;
    @Column(nullable = false) private String category;
    @Column(name = "business_unit") private String businessUnit;
    private String department;
    private boolean active = true;
    @Column(name = "sort_order") private int sortOrder;
}
