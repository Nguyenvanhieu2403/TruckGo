package com.truckgo.shipment;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ImageTypeDetectorTest {
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0};
    static final byte[] WEBP = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};

    @Test void detectsSupportedImagesByContent() {
        assertThat(ImageTypeDetector.detect(JPEG)).hasValueSatisfying(t -> assertThat(t.mimeType()).isEqualTo("image/jpeg"));
        assertThat(ImageTypeDetector.detect(PNG)).hasValueSatisfying(t -> assertThat(t.extension()).isEqualTo("png"));
        assertThat(ImageTypeDetector.detect(WEBP)).hasValueSatisfying(t -> assertThat(t.mimeType()).isEqualTo("image/webp"));
    }

    @Test void rejectsEverythingElse() {
        assertThat(ImageTypeDetector.detect("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ImageTypeDetector.detect("%PDF-1.7".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ImageTypeDetector.detect("GIF89a".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ImageTypeDetector.detect(new byte[]{(byte) 0xFF})).isEmpty();
        assertThat(ImageTypeDetector.detect(null)).isEmpty();
    }

    @Test void displayNameCannotCarryPaths() {
        assertThat(ShipmentAttachmentService.displayName("../../etc/passwd", "png")).isEqualTo("passwd");
        assertThat(ShipmentAttachmentService.displayName("C:\\Users\\a\\tủ lạnh.jpg", "jpg")).isEqualTo("tủ lạnh.jpg");
        assertThat(ShipmentAttachmentService.displayName("..", "png")).isEqualTo("anh-hang-hoa.png");
        assertThat(ShipmentAttachmentService.displayName(null, "webp")).isEqualTo("anh-hang-hoa.webp");
        assertThat(ShipmentAttachmentService.displayName("a\u0000b\nc.png", "png")).isEqualTo("abc.png");
    }
}
