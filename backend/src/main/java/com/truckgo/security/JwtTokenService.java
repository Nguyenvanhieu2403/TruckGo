package com.truckgo.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.truckgo.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Issues and verifies short-lived HS256 access tokens. */
@Service
public class JwtTokenService {
    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);
    private static final int MIN_KEY_BYTES = 32;

    private final SecurityProperties.Jwt config;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public JwtTokenService(SecurityProperties properties, Environment environment) {
        this.config = properties.jwt();
        SecretKey key = new SecretKeySpec(resolveKey(config.secret(), environment), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        List<OAuth2TokenValidator<Jwt>> validators = List.of(JwtValidators.createDefaultWithIssuer(config.issuer()));
        nimbusDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        this.decoder = nimbusDecoder;
    }

    public record AccessToken(String value, long expiresInSeconds) { }

    public AccessToken issue(User user, UUID sessionFamilyId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(config.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(config.accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim("roles", List.of(user.getRole().name()))
                .claim("sid", sessionFamilyId.toString())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new AccessToken(token, config.accessTokenTtl().toSeconds());
    }

    public JwtDecoder decoder() { return decoder; }

    private static byte[] resolveKey(String secret, Environment environment) {
        boolean lenient = environment.acceptsProfiles(Profiles.of("dev", "test"));
        if (secret == null || secret.isBlank()) {
            if (!lenient) throw new IllegalStateException("JWT_SECRET must be configured (Base64, >= 32 bytes)");
            log.warn("JWT_SECRET is not set; using an ephemeral random key. Tokens become invalid on restart. Never do this in production.");
            byte[] random = new byte[64];
            new SecureRandom().nextBytes(random);
            return random;
        }
        byte[] key;
        try { key = Base64.getDecoder().decode(secret.trim()); }
        catch (IllegalArgumentException ex) { throw new IllegalStateException("JWT_SECRET must be Base64 encoded"); }
        if (key.length < MIN_KEY_BYTES) throw new IllegalStateException("JWT_SECRET must decode to at least 32 bytes");
        return key;
    }
}
