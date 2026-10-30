package com.truckgo.common;

import com.truckgo.common.exception.GlobalExceptionHandler;
import com.truckgo.common.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test void notFoundUses404AndSafeEnvelope() {
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/missing");
        var response = handler.notFound(new ResourceNotFoundException("Không tìm thấy tài nguyên"), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().path()).isEqualTo("/api/v1/missing");
    }
}
