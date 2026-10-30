package com.truckgo.vehicle;

import com.truckgo.common.exception.FieldErrorException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlateNumberNormalizerTest {
    @Test void normalisesCommonPlateFormats() {
        assertThat(PlateNumberNormalizer.normalize("51C-123.45")).isEqualTo("51C12345");
        assertThat(PlateNumberNormalizer.normalize("29ld 123.45")).isEqualTo("29LD12345");
        assertThat(PlateNumberNormalizer.normalize("51C-1234")).isEqualTo("51C1234");
        assertThatThrownBy(() -> PlateNumberNormalizer.normalize("ABC")).isInstanceOf(FieldErrorException.class);
    }
}
