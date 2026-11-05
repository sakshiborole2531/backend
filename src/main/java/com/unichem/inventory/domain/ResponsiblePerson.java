package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "responsible_person")
@Getter @Setter @NoArgsConstructor
public class ResponsiblePerson {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    @Column(name = "function_name") private String functionName;
    private String email;
}
