package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;
import java.util.HashSet;
import java.util.Set;

@Entity @Table(name = "app_role")
@Getter @Setter @NoArgsConstructor
public class AppRole {
    @Id private String code;
    @Column(nullable = false) private String label;
    private boolean builtin;
    private String description;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permission", joinColumns = @JoinColumn(name = "role_code"))
    @Column(name = "permission")
    private Set<String> permissions = new HashSet<>();
}
