package com.truckgo.shipment;

import java.util.Optional;

/** Identifies JPEG, PNG and WebP from their magic bytes; the client's file name and Content-Type are ignored. */
public final class ImageTypeDetector {
    public record ImageType(String mimeType, String extension) { }

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private ImageTypeDetector() { }

    public static Optional<ImageType> detect(byte[] data) {
        if (data == null) return Optional.empty();
        if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) return Optional.of(new ImageType("image/jpeg", "jpg"));
        if (startsWith(data, PNG)) return Optional.of(new ImageType("image/png", "png"));
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') return Optional.of(new ImageType("image/webp", "webp"));
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }
}
