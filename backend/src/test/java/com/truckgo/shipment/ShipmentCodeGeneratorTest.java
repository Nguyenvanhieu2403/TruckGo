package com.truckgo.shipment;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShipmentCodeGeneratorTest {
    private final ShipmentProperties properties = new ShipmentProperties(ZoneId.of("Asia/Ho_Chi_Minh"), null, 10, 50, 10, null, null);

    @Test void usesBusinessDateInVietnamTime() {
        // 2026-11-04T18:30Z is already 2026-11-05 01:30 in Vietnam (UTC+7).
        var generator = new ShipmentCodeGenerator(Clock.fixed(Instant.parse("2026-11-04T18:30:00Z"), ZoneOffset.UTC), properties);
        assertThat(generator.next()).matches("TG261105-[2-9A-HJKMNP-Z]{6}");
    }

    @Test void neverUsesAmbiguousCharacters() {
        var generator = new ShipmentCodeGenerator(Clock.systemUTC(), properties);
        Set<String> suffixChars = new HashSet<>();
        for (int i = 0; i < 2_000; i++) for (char c : generator.next().substring(9).toCharArray()) suffixChars.add(String.valueOf(c));
        assertThat(suffixChars).doesNotContain("0", "1", "O", "I", "L");
    }
}
