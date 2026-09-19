package com.example.auth.security;

import java.io.Serializable;
import java.util.List;

/**
 * Organization bound to the current request on the server side, together with
 * the roles that belong to that organization only.
 *
 * <p>Stored in the {@code Authentication} details so Spring Authorization
 * Server persists it with the OAuth authorization record; clients can never
 * supply roles or another organization.
 */
public record OrganizationAuthorization(long userId, long orgId, String orgName, List<String> roles)
        implements Serializable {
}
