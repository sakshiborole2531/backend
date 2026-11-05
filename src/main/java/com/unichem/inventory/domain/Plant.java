package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "plant")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class Plant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "sap_code") private String sapCode;
    @Column(nullable = false, unique = true) private String name;
    @Column(name = "bu_code", nullable = false) private String buCode;
    @Column(name = "business_unit_id", nullable = false) private Long businessUnitId;
    private String city;
    private boolean active = true;
    @Column(name = "sort_order") private int sortOrder;
}
