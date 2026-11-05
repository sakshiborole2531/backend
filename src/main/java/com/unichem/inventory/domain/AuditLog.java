package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity @Table(name = "audit_log")
@Getter @Setter @NoArgsConstructor
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private OffsetDateTime at = OffsetDateTime.now();
    @Column(nullable = false) private String username;
    private String name;
    @Column(nullable = false) private String action;
    private String detail;
    private String entity;
    @Column(name = "entity_id") private String entityId;
}
