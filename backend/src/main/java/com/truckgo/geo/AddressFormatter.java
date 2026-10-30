package com.truckgo.geo;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Builds the display address "detail, ward, district, province" from the structured parts. */
public final class AddressFormatter {
    private AddressFormatter() { }

    public static String format(String detail, String ward, String district, String province) {
        return Stream.of(detail, ward, district, province).filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.joining(", "));
    }
}
