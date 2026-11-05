package com.unichem.inventory.web;

import com.unichem.inventory.domain.AccessRequest;
import com.unichem.inventory.dto.Dtos.*;
import com.unichem.inventory.repo.AuditLogRepository;
import com.unichem.inventory.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Users & access, audit log, sample data. */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private final UserService users;
    private final AuditLogRepository audit;
    private final SampleDataService sample;
    private final AuthService auth;

    @GetMapping("/users") @PreAuthorize("hasAuthority('users.manage')") public List<Map<String, Object>> users() { return users.list(); }
    @PostMapping("/users") @PreAuthorize("hasAuthority('users.manage')") public UserCreated create(@Valid @RequestBody UserRequest r) { return users.create(r); }
    @PutMapping("/users/{id}") @PreAuthorize("hasAuthority('users.manage')") public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody UserRequest r) { return users.update(id, r); }
    @PostMapping("/users/{id}/reset-password") @PreAuthorize("hasAuthority('users.manage')") public UserCreated reset(@PathVariable Long id) { return users.resetPassword(id); }
    @PostMapping("/users/{id}/unlock") @PreAuthorize("hasAuthority('users.manage')") public void unlock(@PathVariable Long id) { users.unlock(id); }
    @DeleteMapping("/users/{id}") @PreAuthorize("hasAuthority('users.manage')") public void delete(@PathVariable Long id) { users.delete(id); }

    @GetMapping("/roles") @PreAuthorize("hasAnyAuthority('users.manage','audit.view')")
    public Map<String, Object> roles() { return Map.of("roles", users.roles(), "permissions", users.allPermissions(), "privileged", users.privileged()); }
    @PostMapping("/roles") @PreAuthorize("hasAuthority('users.manage')") public Map<String, Object> role(@Valid @RequestBody RoleRequest r) { return users.createRole(r); }
    @DeleteMapping("/roles/{code}") @PreAuthorize("hasAuthority('users.manage')") public void delRole(@PathVariable String code) { users.deleteRole(code); }
    @PutMapping("/roles/{code}/permissions/{perm}") @PreAuthorize("hasAuthority('users.manage')")
    public void perm(@PathVariable String code, @PathVariable String perm, @RequestBody Map<String, Object> b) {
        users.setRolePermission(code, perm, Boolean.TRUE.equals(b.get("enabled")), (String) b.get("reason"));
    }

    @GetMapping("/access-requests") @PreAuthorize("hasAuthority('users.manage')") public List<AccessRequest> requests() { return users.requests(); }
    @PostMapping("/access-requests/{id}/approve") @PreAuthorize("hasAuthority('users.manage')") public UserCreated approve(@PathVariable Long id, @Valid @RequestBody UserRequest r) { return users.approve(id, r); }
    @PostMapping("/access-requests/{id}/reject") @PreAuthorize("hasAuthority('users.manage')") public void reject(@PathVariable Long id, @RequestBody Map<String, String> b) { users.reject(id, b.get("reason")); }

    @GetMapping("/audit") @PreAuthorize("hasAuthority('audit.view')")
    public Page<?> audit(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return audit.search(q, PageRequest.of(page, Math.min(size, 500)));
    }

    /** Step-up: the caller's password is re-verified before data is replaced. */
    @PostMapping("/sample-data") @PreAuthorize("hasAuthority('data.clear')")
    public Map<String, Object> sample(@RequestBody Map<String, String> b) { auth.verifyPassword(b.get("password")); return sample.load(); }
}
