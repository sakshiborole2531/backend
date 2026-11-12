package com.unichem.inventory.web;

import com.unichem.inventory.dto.Dtos.*;
import com.unichem.inventory.repo.AuditLogRepository;
import com.unichem.inventory.service.AuthService;
import com.unichem.inventory.service.CurrentUserService;
import com.unichem.inventory.service.SettingsService;
import com.unichem.inventory.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService auth;
    private final UserService users;
    private final SettingsService settings;
    private final CurrentUserService current;
    private final AuditLogRepository audit;

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("needsSetup", auth.needsSetup(), "company", settings.get("companyName", "Unichem Laboratories Ltd."),
                "short", settings.get("companyShort", "UNICHEM"), "passwordRules", auth.passwordProblems(""),
                "idleMinutes", settings.getInt("policy.idleMin", 30));
    }

    @PostMapping("/login") public TokenResponse login(@Valid @RequestBody LoginRequest r) { return auth.login(r); }
    @PostMapping("/setup") public TokenResponse setup(@Valid @RequestBody SetupRequest r) { return auth.setup(r); }
    @PostMapping("/access-request") public void request(@Valid @RequestBody AccessRequestDto r) { users.submitRequest(r); }

    @GetMapping("/me") public MeResponse me() { return auth.me(); }
    @GetMapping("/me/permissions")
    public Map<String, Object> mePermissions() {
        var u = current.require();
        Set<String> perms = current.permissions(u);
        Set<String> bus = current.allowedBusinessUnits(u);
        Set<String> plants = current.allowedPlants(u);
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> permMap = new LinkedHashMap<>();
        permMap.put("actions", perms);
        permMap.put("businessUnits", bus != null ? new ArrayList<>(bus) : List.of("ALL"));
        permMap.put("plants", plants != null ? new ArrayList<>(plants) : List.of("ALL"));
        permMap.put("scopeAll", bus == null);
        body.put("permissions", permMap);
        return body;
    }
    @PutMapping("/me") public MeResponse profile(@Valid @RequestBody ProfileUpdate p) { return auth.updateProfile(p); }
    @PostMapping("/change-password") public void change(@Valid @RequestBody ChangePasswordRequest r) { auth.changePassword(r); }
    @PostMapping("/verify") public void verify(@RequestBody Map<String, String> b) { auth.verifyPassword(b.get("password")); }
    @GetMapping("/me/activity") public List<?> activity() { return audit.findTop15ByUsernameOrderByAtDesc(current.require().getUsername()); }
}
