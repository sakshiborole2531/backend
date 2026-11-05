package com.unichem.inventory.service;

import com.unichem.inventory.domain.AppRole;
import com.unichem.inventory.domain.AppUser;
import com.unichem.inventory.domain.Plant;
import com.unichem.inventory.domain.UserGrant;
import com.unichem.inventory.repo.AppRoleRepository;
import com.unichem.inventory.repo.AppUserRepository;
import com.unichem.inventory.repo.PlantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.*;

/** Who is signed in, what they may do (role + individual grants) and which plants they may see. */
@Service
@RequiredArgsConstructor
public class CurrentUserService {
    private final AppUserRepository users;
    private final AppRoleRepository roles;
    private final PlantRepository plants;

    public Optional<String> username() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || !a.isAuthenticated() || "anonymousUser".equals(a.getName()) ? Optional.empty() : Optional.of(a.getName());
    }

    public AppUser require() {
        return username().flatMap(users::findByUsernameIgnoreCase)
                .orElseThrow(() -> new AccessDeniedException("Not signed in"));
    }

    public Set<String> permissions(AppUser u) {
        Set<String> p = new TreeSet<>(roles.findById(u.getRoleCode()).map(AppRole::getPermissions).orElse(Set.of()));
        for (UserGrant g : u.getGrants()) {
            if (!g.live()) continue;
            if ("grant".equals(g.getMode())) p.add(g.getPermission()); else p.remove(g.getPermission());
        }
        return p;
    }

    public boolean can(String perm) { return permissions(require()).contains(perm); }

    public void requirePerm(String... anyOf) {
        Set<String> p = permissions(require());
        if (Arrays.stream(anyOf).noneMatch(p::contains)) throw new AccessDeniedException("You don't have permission for this");
    }

    /** null = entire organization. */
    public Set<String> allowedPlants(AppUser u) {
        if (u.isScopeAll()) return null;
        Set<String> s = new HashSet<>(u.getScopeUnits());
        if (!u.getScopeBus().isEmpty()) {
            for (Plant p : plants.findAll()) if (u.getScopeBus().contains(p.getBuCode())) s.add(p.getName());
        }
        return s;
    }

    public Set<String> allowedPlants() { return allowedPlants(require()); }

    public boolean inScope(String plant) { Set<String> a = allowedPlants(); return a == null || a.contains(plant); }

    public void requireScope(String plant) {
        if (!inScope(plant)) throw new AccessDeniedException("Plant " + plant + " is outside your data access");
    }

    /** Active plants within the user's data access (inactive plants are hidden everywhere). */
    public Set<String> visiblePlants() {
        Set<String> a = allowedPlants();
        Set<String> out = new HashSet<>();
        for (Plant p : plants.findAll()) if (p.isActive() && (a == null || a.contains(p.getName()))) out.add(p.getName());
        return out;
    }

    public Set<String> inactivePlants() {
        Set<String> s = new HashSet<>();
        plants.findAll().forEach(p -> { if (!p.isActive()) s.add(p.getName()); });
        return s;
    }
}
