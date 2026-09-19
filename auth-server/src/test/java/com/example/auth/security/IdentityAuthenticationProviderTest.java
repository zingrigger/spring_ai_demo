package com.example.auth.security;

import com.example.auth.identity.IdentityRepository;
import com.example.auth.identity.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class IdentityAuthenticationProviderTest {

    private final IdentityRepository identityRepository = mock(IdentityRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final AuthenticationProvider provider =
            new IdentityAuthenticationProvider(this.identityRepository, this.passwordEncoder);

    @Test
    void authenticatesWithTheStoredBcryptPassword() {
        givenAccount("alice", new UserAccount(1L, "alice", "Alice",
                this.passwordEncoder.encode("alice-password")));

        Authentication result = this.provider.authenticate(
                new UsernamePasswordAuthenticationToken("alice", "alice-password"));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getPrincipal()).isInstanceOf(AuthenticatedUser.class);
        AuthenticatedUser user = (AuthenticatedUser) result.getPrincipal();
        assertThat(user.id()).isEqualTo(1L);
        assertThat(user.account()).isEqualTo("alice");
        assertThat(user.name()).isEqualTo("Alice");
        assertThat(result.getCredentials()).isNull();
        assertThat(result.getAuthorities())
                .singleElement()
                .isInstanceOf(FactorGrantedAuthority.class);
    }

    @Test
    void rejectsAWrongPasswordWithTheGenericFailure() {
        givenAccount("alice", new UserAccount(1L, "alice", "Alice",
                this.passwordEncoder.encode("alice-password")));

        assertThatThrownBy(() -> this.provider.authenticate(
                new UsernamePasswordAuthenticationToken("alice", "wrong-password")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid account or password");
    }

    @Test
    void rejectsAnUnknownAccountWithTheSameGenericFailure() {
        givenAccount("nobody", null);

        assertThatThrownBy(() -> this.provider.authenticate(
                new UsernamePasswordAuthenticationToken("nobody", "whatever")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid account or password");
    }

    @Test
    void takesTheUserIdFromTheStoredRowNotFromTheRequest() {
        givenAccount("alice", new UserAccount(1L, "alice", "Alice",
                this.passwordEncoder.encode("alice-password")));
        UsernamePasswordAuthenticationToken request =
                new UsernamePasswordAuthenticationToken("alice", "alice-password");
        request.setDetails(Map.of("userId", 999L));

        Authentication result = this.provider.authenticate(request);

        assertThat(((AuthenticatedUser) result.getPrincipal()).id()).isEqualTo(1L);
    }

    @Test
    void supportsUsernamePasswordAuthenticationOnly() {
        assertThat(this.provider.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
        assertThat(this.provider.supports(Object.class)).isFalse();
    }

    private void givenAccount(String account, UserAccount user) {
        given(this.identityRepository.findByAccount(account))
                .willReturn(user == null ? Optional.empty() : Optional.of(user));
    }
}
