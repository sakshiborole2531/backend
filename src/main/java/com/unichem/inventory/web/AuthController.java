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

import java.util.List;
import java.util.Map;

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
    @PutMapping("/me") public MeResponse profile(@Valid @RequestBody ProfileUpdate p) { return auth.updateProfile(p); }
    @PostMapping("/change-password") public void change(@Valid @RequestBody ChangePasswordRequest r) { auth.changePassword(r); }
    @PostMapping("/verify") public void verify(@RequestBody Map<String, String> b) { auth.verifyPassword(b.get("password")); }
    @GetMapping("/me/activity") public List<?> activity() { return audit.findTop15ByUsernameOrderByAtDesc(current.require().getUsername()); }
}
