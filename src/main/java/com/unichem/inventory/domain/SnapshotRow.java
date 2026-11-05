package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity @Table(name = "snapshot_row")
@Getter @Setter @NoArgsConstructor
public class SnapshotRow {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "snapshot_id", nullable = false)
    private InventorySnapshot snapshot;
    @Column(name = "bu_code") private String buCode;
    private String plant;
    private String category;
    private String department;
    private int batches;
    @Column(name = "val_u") private BigDecimal valU = BigDecimal.ZERO;
    @Column(name = "val_q") private BigDecimal valQ = BigDecimal.ZERO;
    @Column(name = "val_b") private BigDecimal valB = BigDecimal.ZERO;
    @Column(name = "val_aged") private BigDecimal valAged = BigDecimal.ZERO;
    @Column(name = "val_exp") private BigDecimal valExp = BigDecimal.ZERO;
    @Column(name = "val_expd") private BigDecimal valExpd = BigDecimal.ZERO;
    @Column(name = "val_idle") private BigDecimal valIdle = BigDecimal.ZERO;
}
