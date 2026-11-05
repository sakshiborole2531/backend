package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Entity @Table(name = "app_user")
@Getter @Setter @NoArgsConstructor
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String username;
    @Column(nullable = false) private String name;
    private String email;
    private String phone;
    @Column(name = "emp_id") private String empId;
    private String designation;
    private String department;
    private String location;
    @Column(name = "function_name") private String functionName;
    @Column(name = "role_code", nullable = false) private String roleCode;
    @Column(name = "scope_all") private boolean scopeAll;
    private boolean active = true;
    @Column(name = "valid_until") private LocalDate validUntil;
    @Column(name = "password_hash", nullable = false) private String passwordHash;
    @Column(name = "must_change") private boolean mustChange = true;
    @Column(name = "failed_logins") private int failedLogins;
    @Column(name = "locked_until") private OffsetDateTime lockedUntil;
    @Column(name = "last_login") private OffsetDateTime lastLogin;
    @Column(name = "pw_changed") private OffsetDateTime pwChanged;
    @Column(name = "created_at") private OffsetDateTime createdAt = OffsetDateTime.now();
    @Column(name = "created_by") private String createdBy;
    private String theme;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_scope_bu", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "bu_code")
    private Set<String> scopeBus = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_scope_unit", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "plant_name")
    private Set<String> scopeUnits = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_grant", joinColumns = @JoinColumn(name = "user_id"))
    private List<UserGrant> grants = new ArrayList<>();

    public boolean isLocked() { return lockedUntil != null && lockedUntil.isAfter(OffsetDateTime.now()); }
    public boolean isExpired() { return validUntil != null && validUntil.isBefore(LocalDate.now()); }
}
