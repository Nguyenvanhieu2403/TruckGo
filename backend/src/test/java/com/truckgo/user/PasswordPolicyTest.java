package com.truckgo.user;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {
    @Test void requiresLengthLettersAndDigits() {
        assertThat(PasswordPolicy.isValid("Example-Password-123")).isTrue();
        assertThat(PasswordPolicy.isValid("short1")).isFalse();
        assertThat(PasswordPolicy.isValid("onlyletters")).isFalse();
        assertThat(PasswordPolicy.isValid("1234567890")).isFalse();
        assertThat(PasswordPolicy.isValid("a1" + "x".repeat(71))).isFalse();
    }

    @Test void encoderNeverStoresPlaintext() {
        var encoder = new BCryptPasswordEncoder(4);
        String hash = encoder.encode("Example-Password-123");
        assertThat(hash).doesNotContain("Example-Password-123").startsWith("$2");
        assertThat(encoder.matches("Example-Password-123", hash)).isTrue();
        assertThat(encoder.encode("Example-Password-123")).isNotEqualTo(hash);
    }
}
