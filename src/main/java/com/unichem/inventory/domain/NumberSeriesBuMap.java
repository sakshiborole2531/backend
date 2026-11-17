package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "number_series_bu_map")
@Getter
@Setter
@NoArgsConstructor
public class NumberSeriesBuMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "series_pattern", nullable = false, length = 60)
    private String seriesPattern;

    @Column(name = "business_unit", nullable = false, length = 60)
    private String businessUnit;

    @Column(name = "category", nullable = false, length = 60)
    private String category;

    @Column(name = "department", length = 60)
    private String department;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    public NumberSeriesBuMap(String seriesPattern, String businessUnit, String category, String department, String description, Boolean active, Integer sortOrder) {
        this.seriesPattern = seriesPattern;
        this.businessUnit = businessUnit;
        this.category = category;
        this.department = department;
        this.description = description;
        this.active = active != null ? active : true;
        this.sortOrder = sortOrder != null ? sortOrder : 0;
    }
}
