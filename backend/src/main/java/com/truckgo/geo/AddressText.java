package com.truckgo.geo;

import java.text.Normalizer;
import java.util.Locale;

/** Accent- and case-insensitive comparison of Vietnamese address parts ("Phố Trần Thái Tông" ~ "tran thai tong"). */
final class AddressText {
    private AddressText() { }

    /** Lower case, no diacritics (đ → d), punctuation as spaces, single spaces. */
    static String normalize(String text) {
        String s = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
        return s.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Street name without its kind ("đường", "phố", "ngõ" are often written differently or left out). */
    static String streetName(String street) {
        return normalize(street).replaceFirst("^(duong|pho|dai lo|quoc lo|tinh lo) ", "");
    }
}
