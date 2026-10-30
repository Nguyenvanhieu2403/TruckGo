package com.truckgo.vehicle;

import com.truckgo.common.exception.FieldErrorException;

import java.util.Locale;
import java.util.regex.Pattern;

/** Vietnamese plates such as "51C-123.45", "29LD 123.45" are stored as "51C12345", "29LD12345". */
public final class PlateNumberNormalizer {
    private static final Pattern PLATE = Pattern.compile("^[0-9]{2}[A-Z]{1,2}[0-9]?[0-9]{4,5}$");

    private PlateNumberNormalizer() { }

    public static String normalize(String raw) {
        String value = raw == null ? "" : raw.replaceAll("[\\s.\\-]", "").toUpperCase(Locale.ROOT);
        if (!PLATE.matcher(value).matches()) throw FieldErrorException.invalid("plateNumber", "Biển số xe không đúng định dạng (ví dụ 51C-123.45)");
        return value;
    }
}
