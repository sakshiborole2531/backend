package com.unichem.inventory.service;

import com.unichem.inventory.domain.*;
import com.unichem.inventory.dto.Dtos.*;
import com.unichem.inventory.repo.*;
import com.unichem.inventory.web.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.unichem.inventory.domain.Constants.*;

/** Users, roles, individual privileges and access requests (privileged changes need a reason and are audited). */
@Service
@RequiredArgsConstructor
public class UserService {
    private final AppUserRepository users;
    private final AppRoleRepository roles;
    private final AccessRequestRepository requests;
    private final PasswordEncoder encoder;
    private final AuthService auth;
    private final CurrentUserService current;
    private final AuditService audit;

    private static final SecureRandom RND = new SecureRandom();
    public static String tempPassword() {
        String c = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) sb.append(c.charAt(RND.nextInt(c.length())));
        return sb.append(RND.nextInt(10)).append(RND.nextInt(10)).toString();
    }

    public List<Map<String, Object>> list() {
        return users.findAll().stream().sorted(Comparator.comparing(AppUser::getName)).map(this::view).toList();
    }

    public Map<String, Object> view(AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId()); m.put("username", u.getUsername()); m.put("name", u.getName()); m.put("email", u.getEmail());
        m.put("phone", u.getPhone()); m.put("empId", u.getEmpId()); m.put("designation", u.getDesignation()); m.put("department", u.getDepartment());
        m.put("location", u.getLocation()); m.put("functionName", u.getFunctionName()); m.put("role", u.getRoleCode());
        m.put("roleLabel", roles.findById(u.getRoleCode()).map(AppRole::getLabel).orElse(u.getRoleCode()));
        m.put("scopeAll", u.isScopeAll()); m.put("scopeBus", u.getScopeBus()); m.put("scopeUnits", u.getScopeUnits());
        m.put("active", u.isActive()); m.put("validUntil", u.getValidUntil()); m.put("lastLogin", u.getLastLogin());
        m.put("status", !u.isActive() ? "Inactive" : u.isExpired() ? "Expired" : u.isLocked() ? "Locked" : u.isMustChange() ? "Temp. password" : "Active");
        m.put("grants", u.getGrants().stream().map(g -> Map.of("permission", g.getPermission(), "mode", g.getMode(), "until", g.getUntilDate() == null ? "" : g.getUntilDate().toString())).toList());
        m.put("privileged", current.permissions(u).stream().filter(PRIVILEGED::contains).toList());
        return m;
    }

    private Set<String> privOf(String role, List<UserGrant> grants) {
        Set<String> p = new HashSet<>(roles.findById(role).map(AppRole::getPermissions).orElse(Set.of()));
        grants.stream().filter(UserGrant::live).forEach(g -> { if ("grant".equals(g.getMode())) p.add(g.getPermission()); else p.remove(g.getPermission()); });
        p.retainAll(PRIVILEGED);
        return p;
    }

    @Transactional
    public UserCreated create(UserRequest r) {
        if (r.username() == null || users.existsByUsernameIgnoreCase(r.username())) throw new IllegalArgumentException("Username already exists.");
        if (!roles.existsById(r.role())) throw new IllegalArgumentException("Unknown role");
        AppUser u = new AppUser();
        u.setUsername(r.username().trim());
        String pw = r.password() == null || r.password().isBlank() ? tempPassword() : r.password();
        auth.checkPassword(pw);
        u.setPasswordHash(encoder.encode(pw)); u.setMustChange(true); u.setCreatedBy(current.require().getName());
        applyFields(u, r, List.of());
        users.save(u);
        audit.log("User created", u.getUsername() + " · " + u.getRoleCode() + (r.reason() != null ? " · reason: " + r.reason() : ""), "user", u.getUsername(), null);
        return new UserCreated(u.getId(), u.getUsername(), pw);
    }

    @Transactional
    public Map<String, Object> update(Long id, UserRequest r) {
        AppUser u = users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        AppUser me = current.require();
        if (u.getId().equals(me.getId())) {   // own account: contact details only (segregation of duties)
            u.setName(r.name()); u.setEmail(r.email()); u.setPhone(r.phone()); u.setEmpId(r.empId());
            u.setDesignation(r.designation()); u.setDepartment(r.department()); u.setLocation(r.location());
            audit.log("User updated", u.getUsername() + ": contact details", "user", u.getUsername(), null);
            return view(u);
        }
        boolean active = r.active() == null || r.active();
        if ("admin".equals(u.getRoleCode()) && (!"admin".equals(r.role()) || !active) && users.countOtherActiveAdmins(u.getId()) == 0)
            throw new IllegalStateException("At least one active administrator is required.");
        applyFields(u, r, u.getGrants());
        audit.log("User updated", u.getUsername() + " · " + u.getRoleCode() + (r.reason() != null ? " · reason: " + r.reason() : ""), "user", u.getUsername(), null);
        return view(u);
    }

    private void applyFields(AppUser u, UserRequest r, List<UserGrant> oldGrants) {
        if (!roles.existsById(r.role())) throw new IllegalArgumentException("Unknown role");
        boolean scopeAll = r.scopeAll();
        Set<String> bus = r.scopeBus() == null ? Set.of() : r.scopeBus(), units = r.scopeUnits() == null ? Set.of() : r.scopeUnits();
        if (!scopeAll && bus.isEmpty() && units.isEmpty()) throw new IllegalArgumentException("Select at least one business unit or unit, or choose Entire organization.");
        List<UserGrant> grants = new ArrayList<>();
        if (r.grants() != null) for (GrantDto g : r.grants()) {
            if (g.mode() == null || g.mode().isBlank()) continue;
            if (!PERMISSIONS.contains(g.permission())) throw new IllegalArgumentException("Unknown permission " + g.permission());
            grants.add(new UserGrant(g.permission(), g.mode(), g.until(), current.require().getName(), OffsetDateTime.now(), r.reason()));
        }
        Set<String> before = privOf(u.getRoleCode() == null ? "viewer" : u.getRoleCode(), oldGrants);
        Set<String> after = privOf(r.role(), grants);
        after.removeAll(before);
        if (!after.isEmpty() && (r.reason() == null || r.reason().isBlank()))
            throw new IllegalArgumentException("Enter the reason / ticket reference — this adds privileged access: " + String.join(", ", after));
        u.setName(r.name().trim()); u.setEmail(r.email()); u.setPhone(r.phone()); u.setEmpId(r.empId()); u.setDesignation(r.designation());
        u.setDepartment(r.department()); u.setLocation(r.location()); u.setFunctionName(r.functionName()); u.setRoleCode(r.role());
        u.setScopeAll(scopeAll); u.getScopeBus().clear(); u.getScopeUnits().clear();
        if (!scopeAll) { u.getScopeBus().addAll(bus); u.getScopeUnits().addAll(units); }
        u.getGrants().clear(); u.getGrants().addAll(grants);
        u.setActive(r.active() == null || r.active()); u.setValidUntil(r.validUntil());
        if (!after.isEmpty()) audit.log("Privileged access granted", u.getUsername() + ": " + String.join(", ", after) + " · reason: " + r.reason(), "user", u.getUsername(), null);
    }

    @Transactional
    public UserCreated resetPassword(Long id) {
        AppUser u = users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        String pw = tempPassword();
        u.setPasswordHash(encoder.encode(pw)); u.setMustChange(true); u.setFailedLogins(0); u.setLockedUntil(null);
        audit.log("Password reset", u.getUsername(), "user", u.getUsername(), null);
        return new UserCreated(u.getId(), u.getUsername(), pw);
    }

    @Transactional
    public void unlock(Long id) {
        AppUser u = users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        u.setLockedUntil(null); u.setFailedLogins(0);
        audit.log("Account unlocked", u.getUsername(), "user", u.getUsername(), null);
    }

    @Transactional
    public void delete(Long id) {
        AppUser u = users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        if (u.getId().equals(current.require().getId())) throw new IllegalStateException("You cannot delete your own account");
        if ("admin".equals(u.getRoleCode()) && users.countOtherActiveAdmins(u.getId()) == 0) throw new IllegalStateException("At least one active administrator is required.");
        users.delete(u);
        audit.log("User deleted", u.getUsername(), "user", u.getUsername(), null);
    }

    // ---------- roles ----------
    public List<Map<String, Object>> roles() {
        return roles.findAll().stream().sorted(Comparator.comparing(AppRole::isBuiltin).reversed().thenComparing(AppRole::getCode)).map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.getCode()); m.put("label", r.getLabel()); m.put("builtin", r.isBuiltin()); m.put("description", r.getDescription());
            m.put("permissions", new TreeSet<>(r.getPermissions())); m.put("users", users.countByRoleCode(r.getCode()));
            return m;
        }).toList();
    }

    @Transactional
    public void setRolePermission(String code, String perm, boolean on, String reason) {
        if ("admin".equals(code)) throw new IllegalStateException("Administrator always has every permission");
        if (!PERMISSIONS.contains(perm)) throw new IllegalArgumentException("Unknown permission");
        AppRole r = roles.findById(code).orElseThrow(() -> new NotFoundException("Role not found"));
        if (on && PRIVILEGED.contains(perm) && (reason == null || reason.isBlank()))
            throw new IllegalArgumentException("\"" + perm + "\" is a privileged permission — a reason / ticket reference is required");
        if (on) r.getPermissions().add(perm); else r.getPermissions().remove(perm);
        audit.log(on && PRIVILEGED.contains(perm) ? "Privileged permission added to role" : "Role permission changed",
                r.getLabel() + ": " + perm + " " + (on ? "granted" : "removed") + (reason != null ? " · reason: " + reason : ""), "role", code, null);
    }

    @Transactional
    public Map<String, Object> createRole(RoleRequest req) {
        String code = "r_" + req.label().trim().toLowerCase().replaceAll("[^a-z0-9]+", "_");
        if (roles.existsById(code)) throw new IllegalArgumentException("A role with this name exists.");
        AppRole r = new AppRole(); r.setCode(code); r.setLabel(req.label().trim()); r.setDescription(req.description() == null ? "Custom role" : req.description());
        r.getPermissions().addAll(List.of("view.inventory", "view.years", "view.actions"));
        roles.save(r);
        audit.log("Role created", r.getLabel(), "role", code, null);
        return Map.of("code", code);
    }

    @Transactional
    public void deleteRole(String code) {
        AppRole r = roles.findById(code).orElseThrow(() -> new NotFoundException("Role not found"));
        if (r.isBuiltin()) throw new IllegalStateException("Built-in roles cannot be deleted");
        if (users.countByRoleCode(code) > 0) throw new IllegalStateException("Reassign the users of this role first.");
        roles.delete(r);
        audit.log("Role deleted", r.getLabel(), "role", code, null);
    }

    // ---------- access requests ----------
    @Transactional
    public void submitRequest(AccessRequestDto d) {
        if (users.existsByUsernameIgnoreCase(d.username()) || requests.existsByUsernameIgnoreCaseAndStatus(d.username(), "pending"))
            throw new IllegalArgumentException("This username is already taken or requested — choose another.");
        AccessRequest a = new AccessRequest();
        a.setName(d.name()); a.setUsername(d.username()); a.setEmail(d.email()); a.setEmpId(d.empId()); a.setDesignation(d.designation());
        a.setDepartment(d.department()); a.setPhone(d.phone()); a.setBuCode(d.bu()); a.setUnit(d.unit());
        a.setRoleCode(d.role() != null && roles.existsById(d.role()) && !"admin".equals(d.role()) ? d.role() : "viewer");
        a.setJustification(d.justification());
        requests.save(a);
        audit.log("Access requested", d.name() + " · " + a.getRoleCode(), "request", null, d.username());
    }

    public List<AccessRequest> requests() { return requests.findAllByOrderByRequestedAtDesc(); }

    @Transactional
    public UserCreated approve(Long id, UserRequest r) {
        AccessRequest a = requests.findById(id).orElseThrow(() -> new NotFoundException("Request not found"));
        if (!"pending".equals(a.getStatus())) throw new IllegalStateException("Request already decided");
        UserCreated c = create(r);
        a.setStatus("approved"); a.setDecidedBy(current.require().getName()); a.setDecidedAt(OffsetDateTime.now());
        audit.log("Access request approved", a.getUsername(), "request", String.valueOf(id), null);
        return c;
    }

    @Transactional
    public void reject(Long id, String reason) {
        AccessRequest a = requests.findById(id).orElseThrow(() -> new NotFoundException("Request not found"));
        a.setStatus("rejected"); a.setReason(reason); a.setDecidedBy(current.require().getName()); a.setDecidedAt(OffsetDateTime.now());
        audit.log("Access request rejected", a.getUsername() + " · " + reason, "request", String.valueOf(id), null);
    }

    public Set<String> allPermissions() { return new LinkedHashSet<>(PERMISSIONS); }
    public Set<String> privileged() { return PRIVILEGED.stream().collect(Collectors.toCollection(TreeSet::new)); }
}
