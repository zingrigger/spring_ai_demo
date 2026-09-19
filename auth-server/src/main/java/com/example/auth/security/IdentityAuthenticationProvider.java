package com.example.auth.security;

import com.example.auth.identity.IdentityRepository;
import com.example.auth.identity.UserAccount;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates against the read-only identity tables with BCrypt.
 *
 * <p>Unknown accounts and wrong passwords fail with the same message, and both
 * branches run one BCrypt comparison so the failure does not reveal whether the
 * account exists. The user id always comes from the stored row, never from the
 * request.
 */
@Component
public class IdentityAuthenticationProvider implements AuthenticationProvider {

    private static final String BAD_CREDENTIALS = "Invalid account or password";

    /**
     * BCrypt hash of a value that is never accepted; compared when the account
     * is unknown so both failure paths take the same time.
     */
    private static final String TIMING_EQUALIZER_HASH =
            "$2a$10$HhM6BRuECOpF8Gumpmg.YeoJB2MjC9UhFlSdpR.kZLcgUTH9hmawq";

    private final IdentityRepository identityRepository;
    private final PasswordEncoder passwordEncoder;

    public IdentityAuthenticationProvider(IdentityRepository identityRepository, PasswordEncoder passwordEncoder) {
        this.identityRepository = identityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String account = authentication.getName();
        Object credentials = authentication.getCredentials();
        if (!StringUtils.hasText(account) || !(credentials instanceof String password)) {
            throw new BadCredentialsException(BAD_CREDENTIALS);
        }

        Optional<UserAccount> found = this.identityRepository.findByAccount(account);
        if (found.isEmpty()) {
            this.passwordEncoder.matches(password, TIMING_EQUALIZER_HASH);
            throw new BadCredentialsException(BAD_CREDENTIALS);
        }

        UserAccount user = found.get();
        if (!this.passwordEncoder.matches(password, user.password())) {
            throw new BadCredentialsException(BAD_CREDENTIALS);
        }

        AuthenticatedUser principal = new AuthenticatedUser(user.id(), user.account(), user.name());
        // Spring Authorization Server reads the authentication time from the
        // password factor when it issues an ID token.
        FactorGrantedAuthority passwordFactor = FactorGrantedAuthority
                .withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                .issuedAt(Instant.now())
                .build();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of(passwordFactor));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
