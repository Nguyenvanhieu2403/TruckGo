package com.truckgo.security;

import com.truckgo.user.User;
import com.truckgo.user.UserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Maps a verified JWT to the current user. Role and status are re-read from the database on every request so that
 * suspending an account or changing a role takes effect immediately rather than when the access token expires.
 */
@Component
public class JwtUserConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final UserRepository users;

    public JwtUserConverter(UserRepository users) { this.users = users; }

    @Override public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId;
        UUID familyId;
        try {
            userId = UUID.fromString(jwt.getSubject());
            familyId = UUID.fromString(jwt.getClaimAsString("sid"));
        } catch (RuntimeException ex) {
            throw new DisabledException("Invalid token subject");
        }
        User user = users.findById(userId).orElseThrow(() -> new DisabledException("Unknown user"));
        if (!user.isActive()) throw new DisabledException("Account is not active");
        return new UserAuthenticationToken(new AuthenticatedUser(user.getId(), user.getRole(), familyId), jwt);
    }
}
