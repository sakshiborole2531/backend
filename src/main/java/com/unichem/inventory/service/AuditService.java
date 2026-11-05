package com.unichem.inventory.service;

import com.unichem.inventory.domain.AuditLog;
import com.unichem.inventory.repo.AppUserRepository;
import com.unichem.inventory.repo.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository repo;
    private final AppUserRepository users;
    private final CurrentUserService current;

    /** Written in its own transaction so failed-login attempts are recorded even if the caller rolls back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String action, String detail) { log(action, detail, null, null, null); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String action, String detail, String entity, String entityId, String usernameOverride) {
        AuditLog a = new AuditLog();
        String un = usernameOverride != null ? usernameOverride : current.username().orElse("—");
        a.setUsername(un);
        users.findByUsernameIgnoreCase(un).ifPresent(u -> a.setName(u.getName()));
        a.setAction(action);
        a.setDetail(detail == null ? "" : detail.length() > 2000 ? detail.substring(0, 2000) : detail);
        a.setEntity(entity);
        a.setEntityId(entityId);
        repo.save(a);
    }
}
