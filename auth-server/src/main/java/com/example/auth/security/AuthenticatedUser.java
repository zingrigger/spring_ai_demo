package com.example.auth.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 *
 * <p>Its Jackson shape is the record itself, because the principal is stored
 * with the OAuth2 authorization attributes; the derived {@link UserDetails}
 * accessors are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthenticatedUser(long id, String account, String name) implements UserDetails {

    private static final long serialVersionUID = 1L;

    @Override
    @JsonIgnore
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    @JsonIgnore
    public String getPassword() {
        return "";
    }

    @Override
    @JsonIgnore
    public String getUsername() {
        // The design uses the numeric user id as the token subject, while the
        // login account is carried as preferred_username.
        return String.valueOf(this.id);
    }

    @Override
    @JsonIgnore
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    @JsonIgnore
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    @JsonIgnore
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    @JsonIgnore
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String toString() {
        return "AuthenticatedUser[id=" + this.id + ", account=" + this.account + "]";
    }
}
