package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "material_group_map")
@Getter @Setter @NoArgsConstructor
public class MaterialGroupMap {
    @Id @Column(name = "sap_code") private String sapCode;
    private String description;
    private String category;
    private String department;
    @Column(name = "sub_category") private String subCategory;
}
