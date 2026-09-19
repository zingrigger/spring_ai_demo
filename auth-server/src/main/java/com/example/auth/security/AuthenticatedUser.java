package com.example.auth.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Authenticated end user read from the read-only {@code sys_user} table.
 *
 * <p>The BCrypt hash stays in the database: this principal only carries the
 * numeric id, the account and the display name, so the credential is never
 * persisted with the OAuth authorization it takes part in.
 */
public class AuthenticatedUser implements UserDetails {

    private static final long serialVersionUID = 1L;

    private final long id;
    private final String account;
    private final String name;

    public AuthenticatedUser(long id, String account, String name) {
        this.id = id;
        this.account = account;
        this.name = name;
    }

    public long id() {
        return this.id;
    }

    public String account() {
        return this.account;
    }

    public String name() {
        return this.name;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return this.account;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String toString() {
        return "AuthenticatedUser[id=" + this.id + ", account=" + this.account + "]";
    }
}
