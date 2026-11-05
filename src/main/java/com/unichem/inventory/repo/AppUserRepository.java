package com.unichem.inventory.repo;

import com.unichem.inventory.domain.AppUser;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    @Query("select u from AppUser u where lower(u.username) = lower(:u) or lower(u.email) = lower(:u)")
    Optional<AppUser> findLogin(@Param("u") String usernameOrEmail);
    Optional<AppUser> findByUsernameIgnoreCase(String username);
    boolean existsByUsernameIgnoreCase(String username);
    long countByRoleCode(String roleCode);
    @Query("select count(u) from AppUser u where u.roleCode = 'admin' and u.active = true and u.id <> :id")
    long countOtherActiveAdmins(@Param("id") Long id);
}
