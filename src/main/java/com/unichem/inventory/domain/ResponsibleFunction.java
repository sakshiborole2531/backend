package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "responsible_function")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class ResponsibleFunction {
    @Id private String name;
    @Column(name = "sort_order") private int sortOrder;
}
