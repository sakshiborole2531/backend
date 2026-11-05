package com.unichem.inventory.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity @Table(name = "app_setting")
@Getter @Setter @NoArgsConstructor
public class AppSetting {
    @Id private String key;
    @Column(nullable = false) private String value;
    @Column(name = "updated_at") private OffsetDateTime updatedAt = OffsetDateTime.now();
    @Column(name = "updated_by") private String updatedBy;
}
