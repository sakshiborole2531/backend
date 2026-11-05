package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity @Table(name = "inventory_snapshot")
@Getter @Setter @NoArgsConstructor
public class InventorySnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String period;
    @Column(name = "snap_date", nullable = false) private LocalDate snapDate;
    @Column(nullable = false) private String fy;
    @Column(nullable = false) private String source;
    @Column(name = "captured_at") private OffsetDateTime capturedAt = OffsetDateTime.now();
    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<SnapshotRow> rows = new ArrayList<>();
}
