package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity @Table(name = "access_request")
@Getter @Setter @NoArgsConstructor
public class AccessRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "requested_at") private OffsetDateTime requestedAt = OffsetDateTime.now();
    private String status = "pending";
    private String name;
    private String username;
    private String email;
    @Column(name = "emp_id") private String empId;
    private String designation;
    private String department;
    private String phone;
    @Column(name = "bu_code") private String buCode;
    private String unit;
    @Column(name = "role_code") private String roleCode;
    private String justification;
    @Column(name = "decided_by") private String decidedBy;
    @Column(name = "decided_at") private OffsetDateTime decidedAt;
    private String reason;
}
