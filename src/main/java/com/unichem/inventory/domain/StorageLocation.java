package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "storage_location")
@Getter @Setter @NoArgsConstructor
public class StorageLocation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "plant_name") private String plantName;
    @Column(nullable = false) private String code;
    @Column(nullable = false) private String name;
    @Column(name = "storage_type", nullable = false) private String storageType = "General";
    private boolean active = true;
    @Column(name = "sort_order") private int sortOrder;
}
