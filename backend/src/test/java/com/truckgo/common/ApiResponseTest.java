package com.truckgo.common;

import com.truckgo.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {
    @Test void successResponseHasStandardFields() {
        var response = ApiResponse.success("ok", "data", "/api/v1/health");
        assertThat(response.success()).isTrue();
        assertThat(response.timestamp()).isNotNull();
        assertThat(response.path()).isEqualTo("/api/v1/health");
    }
}
