package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "action_type")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class ActionType {
    @Id private String name;
    @Column(name = "sort_order") private int sortOrder;
}
