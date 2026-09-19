package com.example.auth.identity;

import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the business identity tables ({@code sys_*}). Auth-server
 * never writes to these tables; roles are always filtered by the selected
 * organization so a token can never carry cross-organization roles.
 */
public interface IdentityRepository {

    Optional<UserAccount> findByAccount(String account);

    List<Organization> findOrganizations(long userId);

    List<Role> findRoles(long userId, long orgId);
}
