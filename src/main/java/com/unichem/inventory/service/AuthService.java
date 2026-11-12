package com.unichem.inventory.service;

import com.unichem.inventory.domain.AppRole;
import com.unichem.inventory.domain.AppUser;
import com.unichem.inventory.dto.Dtos.*;
import com.unichem.inventory.repo.AppRoleRepository;
import com.unichem.inventory.repo.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Sign-in with lock-out policy, first-run administrator setup, password policy and the signed-in user's profile. */
@Service
@RequiredArgsConstructor
public class AuthService {
    private final AppUserRepository users;
    private final AppRoleRepository roles;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwtEncoder;
    private final CurrentUserService current;
    private final SettingsService settings;
    private final AuditService audit;
    @Value("${app.jwt.ttl-minutes:480}") private long ttl;

    public boolean needsSetup() { return users.count() == 0; }

    public List<String> passwordProblems(String pw) {
        List<String> out = new ArrayList<>();
        int min = settings.getInt("policy.minLen", 8);
        if (pw == null || pw.length() < min) out.add("at least " + min + " characters");
        if (pw == null || !pw.matches(".*\\d.*")) out.add("a number");
        if (pw == null || !pw.matches(".*[A-Za-z].*")) out.add("a letter");
        return out;
    }

    public void checkPassword(String pw) {
        List<String> p = passwordProblems(pw);
        if (!p.isEmpty()) throw new IllegalArgumentException("Password needs " + String.join(", ", p) + ".");
    }

    @Transactional(noRollbackFor = {BadCredentialsException.class, LockedException.class, DisabledException.class})
    public TokenResponse login(LoginRequest r) {
        AppUser u = users.findLogin(r.username().trim()).orElse(null);
        if (u == null) { audit.log("Sign-in failed", "Unknown username", null, null, r.username()); throw new BadCredentialsException("Incorrect username or password."); }
        if (u.isLocked()) throw new LockedException("Account locked after repeated failed attempts. Try again later or ask an administrator to unlock it.");
        if (!encoder.matches(r.password(), u.getPasswordHash())) {
            int maxFail = settings.getInt("policy.maxFail", 5);
            u.setFailedLogins(u.getFailedLogins() + 1);
            String msg = "Incorrect username or password.";
            if (u.getFailedLogins() >= maxFail) {
                u.setLockedUntil(OffsetDateTime.now().plusMinutes(settings.getInt("policy.lockMin", 15)));
                u.setFailedLogins(0);
                msg = "Too many failed attempts — account locked for " + settings.getInt("policy.lockMin", 15) + " minutes.";
                audit.log("Account locked", maxFail + " failed attempts", "user", u.getUsername(), u.getUsername());
            } else msg += " " + (maxFail - u.getFailedLogins()) + " attempt(s) left before lock-out.";
            users.save(u);
            audit.log("Sign-in failed", "Wrong password", "user", u.getUsername(), u.getUsername());
            throw new BadCredentialsException(msg);
        }
        if (!u.isActive()) throw new DisabledException("This account is deactivated. Contact an administrator.");
        if (u.isExpired()) throw new DisabledException("This account expired on " + u.getValidUntil() + ". Contact an administrator to extend it.");
        u.setFailedLogins(0); u.setLockedUntil(null); u.setLastLogin(OffsetDateTime.now());
        users.save(u);
        audit.log("Signed in", "", "user", u.getUsername(), u.getUsername());
        return new TokenResponse(token(u), me(u));
    }

    @Transactional
    public TokenResponse setup(SetupRequest r) {
        if (!needsSetup()) throw new IllegalStateException("Setup has already been completed");
        checkPassword(r.password());
        AppUser u = new AppUser();
        u.setUsername(r.username().trim()); u.setName(r.name().trim()); u.setEmail(r.email()); u.setDesignation(r.designation());
        u.setDepartment("Information Technology"); u.setRoleCode("admin"); u.setScopeAll(true); u.setMustChange(false);
        u.setPasswordHash(encoder.encode(r.password())); u.setPwChanged(OffsetDateTime.now()); u.setLastLogin(OffsetDateTime.now());
        users.save(u);
        audit.log("Administrator created", u.getUsername(), "user", u.getUsername(), u.getUsername());
        return new TokenResponse(token(u), me(u));
    }

    @Transactional
    public void changePassword(ChangePasswordRequest r) {
        AppUser u = current.require();
        if (!u.isMustChange() && (r.currentPassword() == null || !encoder.matches(r.currentPassword(), u.getPasswordHash())))
            throw new IllegalArgumentException("Current password is incorrect.");
        checkPassword(r.newPassword());
        if (encoder.matches(r.newPassword(), u.getPasswordHash())) throw new IllegalArgumentException("Choose a password different from the current one.");
        u.setPasswordHash(encoder.encode(r.newPassword())); u.setMustChange(false); u.setPwChanged(OffsetDateTime.now());
        audit.log("Password changed", "");
    }

    /** Step-up check for privileged operations. */
    public void verifyPassword(String pw) {
        AppUser u = current.require();
        if (pw == null || !encoder.matches(pw, u.getPasswordHash())) {
            audit.log("Step-up verification failed", "");
            throw new BadCredentialsException("Incorrect password.");
        }
    }

    @Transactional
    public MeResponse updateProfile(ProfileUpdate p) {
        AppUser u = current.require();
        u.setName(p.name().trim()); u.setEmail(p.email()); u.setPhone(p.phone()); u.setEmpId(p.empId());
        u.setDesignation(p.designation()); u.setDepartment(p.department()); u.setLocation(p.location());
        if (p.theme() != null) u.setTheme(p.theme());
        audit.log("Profile updated", "");
        return me(u);
    }

    public String token(AppUser u) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("unichem-inventory").issuedAt(now).expiresAt(now.plus(ttl, ChronoUnit.MINUTES))
                .subject(u.getUsername()).claim("uid", u.getId()).claim("role", u.getRoleCode()).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public MeResponse me() { return me(current.require()); }

    public MeResponse me(AppUser u) {
        AppRole r = roles.findById(u.getRoleCode()).orElse(null);
        Set<String> buSet = current.allowedBusinessUnits(u);
        List<String> allowedBus = buSet == null ? null : new ArrayList<>(buSet);
        return new MeResponse(u.getId(), u.getUsername(), u.getName(), u.getEmail(), u.getPhone(), u.getEmpId(), u.getDesignation(),
                u.getDepartment(), u.getLocation(), u.getFunctionName(), u.getRoleCode(), r == null ? u.getRoleCode() : r.getLabel(),
                u.isScopeAll(), u.getScopeBus(), u.getScopeUnits(), current.permissions(u), allowedBus, u.isMustChange(), u.getTheme(),
                u.getLastLogin() == null ? null : u.getLastLogin().toString(), u.getValidUntil() == null ? null : u.getValidUntil().toString());
    }
}
