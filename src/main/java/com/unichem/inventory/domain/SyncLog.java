package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity @Table(name = "sync_log")
@Getter @Setter @NoArgsConstructor
public class SyncLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private OffsetDateTime at = OffsetDateTime.now();
    @Column(nullable = false) private String source;
    private int records;
    @Column(nullable = false) private String status;
    private String message;
}
