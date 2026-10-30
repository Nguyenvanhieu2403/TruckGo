package com.truckgo.common;

import com.truckgo.common.web.HealthController;
import com.truckgo.security.JsonSecurityErrorHandler;
import com.truckgo.security.JwtTokenService;
import com.truckgo.security.JwtUserConverter;
import com.truckgo.security.SecurityConfig;
import com.truckgo.security.SecurityProperties;
import com.truckgo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import(HealthControllerTest.Security.class)
class HealthControllerTest {
    @TestConfiguration
    @EnableConfigurationProperties(SecurityProperties.class)
    @Import({SecurityConfig.class, JwtTokenService.class, JwtUserConverter.class, JsonSecurityErrorHandler.class})
    static class Security { }

    @Autowired MockMvc mockMvc;
    @MockitoBean UserRepository users;

    @Test void healthReturnsStandardEnvelopeWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.application").value("TruckGo API"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/v1/health"));
    }
}
