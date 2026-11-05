package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Individual privilege: grant or restrict one permission for one user, optionally time-bound. */
@Embeddable
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class UserGrant {
    private String permission;
    private String mode;            // grant | deny
    @Column(name = "until_date") private LocalDate untilDate;
    @Column(name = "granted_by") private String grantedBy;
    @Column(name = "granted_at") private OffsetDateTime grantedAt;
    private String reason;

    public boolean live() { return untilDate == null || !untilDate.isBefore(LocalDate.now()); }
}
