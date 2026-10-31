package com.truckgo.trip;

import com.truckgo.security.SecurityProperties;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Six-digit codes from {@link SecureRandom}, stored only as HMAC-SHA256(secret, confirmationId + ":" + code). Binding
 * the id means a hash copied from one order is useless for another; the server-side key means the stored hashes
 * cannot be brute-forced offline from a database dump alone.
 */
@Component
public class OtpCodec {
    private static final int DIGITS = 6;
    private final SecureRandom random = new SecureRandom();
    private final byte[] key;

    public OtpCodec(TripProperties trip, SecurityProperties security) {
        String secret = trip.otpSecret() == null || trip.otpSecret().isBlank() ? security.jwt().secret() : trip.otpSecret();
        if (secret == null || secret.isBlank()) {
            // Same rule as the JWT key: only dev/test run without one (prod refuses to start without JWT_SECRET). Codes
            // issued before a restart then stop matching, which only means the customer requests a new one.
            byte[] random = new byte[32];
            this.random.nextBytes(random);
            this.key = random;
            LoggerFactory.getLogger(OtpCodec.class).warn("No app.trip.otp-secret or JWT secret configured; using a random per-process OTP key");
        } else {
            this.key = ("truckgo-otp:" + secret).getBytes(StandardCharsets.UTF_8);
        }
    }

    public String newCode() {
        return String.format("%0" + DIGITS + "d", random.nextInt((int) Math.pow(10, DIGITS)));
    }

    public String hash(UUID confirmationId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((confirmationId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Constant-time comparison; anything that is not exactly six digits never matches. */
    public boolean matches(UUID confirmationId, String code, String storedHash) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) return false;
        return MessageDigest.isEqual(hash(confirmationId, code).getBytes(StandardCharsets.US_ASCII), storedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
