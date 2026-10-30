package com.truckgo.shipment;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Human-friendly codes such as {@code TG261105-7KQ3MX}: business date plus 6 random characters (31^6 ≈ 887M per day) from an alphabet
 * without look-alikes (no 0/O, 1/I/L). Uniqueness is guaranteed by the database constraint; the service retries on
 * the rare collision.
 */
@Component
public class ShipmentCodeGenerator {
    private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final ZoneId zone;

    public ShipmentCodeGenerator(Clock clock, ShipmentProperties properties) {
        this.clock = clock;
        this.zone = properties.businessZone();
    }

    public String next() {
        StringBuilder code = new StringBuilder("TG").append(DATE.format(clock.instant().atZone(zone))).append('-');
        for (int i = 0; i < 6; i++) code.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        return code.toString();
    }
}
