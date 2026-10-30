package com.truckgo.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContactNormalizerTest {
    @Test void emailIsTrimmedAndLowerCased() {
        assertThat(ContactNormalizer.email("  Customer@Example.COM ")).isEqualTo("customer@example.com");
        assertThat(ContactNormalizer.email("  ")).isNull();
        assertThatThrownBy(() -> ContactNormalizer.email("not-an-email")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void vietnamesePhoneFormatsShareOneCanonicalForm() {
        assertThat(ContactNormalizer.phone("0912345678")).isEqualTo("+84912345678");
        assertThat(ContactNormalizer.phone("+84 912 345 678")).isEqualTo("+84912345678");
        assertThat(ContactNormalizer.phone("84912345678")).isEqualTo("+84912345678");
        assertThat(ContactNormalizer.phone("0912.345.678")).isEqualTo("+84912345678");
        assertThatThrownBy(() -> ContactNormalizer.phone("0123")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContactNormalizer.phone("0112345678")).isInstanceOf(IllegalArgumentException.class);
    }
}
