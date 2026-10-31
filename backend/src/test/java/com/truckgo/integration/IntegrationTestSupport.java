package com.truckgo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truckgo.user.User;
import com.truckgo.user.UserRepository;
import com.truckgo.user.UserRole;
import com.truckgo.user.UserStatus;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared helpers. Each concrete test class declares its own containers so Spring contexts never point at a stopped one. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTestSupport {
    static final String PASSWORD = "Example-Password-123";
    private static final Pattern REFRESH_COOKIE = Pattern.compile("tg_refresh=([^;]*)");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;

    record Session(String accessToken, String refreshToken, JsonNode user) { }

    static String uniqueEmail() { return "u" + UUID.randomUUID().toString().substring(0, 12) + "@example.com"; }

    static String uniquePhone() { return "09" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)); }

    static String uniqueLicense() { return String.format("%012d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L)); }

    static String uniquePlate() { return "51C" + String.format("%05d", ThreadLocalRandom.current().nextInt(100_000)); }

    ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }

    JsonNode registerCustomer(String email, String phone) throws Exception {
        var body = new java.util.HashMap<String, Object>(Map.of("fullName", "Nguyen Van A", "password", PASSWORD, "confirmPassword", PASSWORD));
        if (email != null) body.put("email", email);
        if (phone != null) body.put("phone", phone);
        return body(postJson("/api/v1/auth/register/customer", body).andExpect(status().isCreated()).andReturn());
    }

    JsonNode registerDriver(String email) throws Exception {
        return body(postJson("/api/v1/auth/register/driver", Map.of("fullName", "Nguyen Van B", "email", email, "password", PASSWORD,
                "confirmPassword", PASSWORD, "licenseNumber", uniqueLicense(), "licenseClass", "C")).andExpect(status().isCreated()).andReturn());
    }

    Session login(String identifier, String password) throws Exception {
        MvcResult result = postJson("/api/v1/auth/login", Map.of("identifier", identifier, "password", password)).andExpect(status().isOk()).andReturn();
        return session(result);
    }

    Session session(MvcResult result) throws Exception {
        JsonNode data = body(result).get("data");
        return new Session(data.get("accessToken").asText(), refreshCookie(result), data.get("user"));
    }

    static String refreshCookie(MvcResult result) {
        String header = result.getResponse().getHeader("Set-Cookie");
        if (header == null) return null;
        Matcher m = REFRESH_COOKIE.matcher(header);
        return m.find() ? m.group(1) : null;
    }

    ResultActions refresh(String refreshToken) throws Exception {
        var request = post("/api/v1/auth/refresh").header("X-Requested-With", "XMLHttpRequest");
        if (refreshToken != null) request.cookie(new Cookie("tg_refresh", refreshToken));
        return mvc.perform(request);
    }

    Session newAdmin() throws Exception {
        String email = uniqueEmail();
        users.save(new User("Admin", email, null, passwordEncoder.encode(PASSWORD), UserRole.ADMIN, UserStatus.ACTIVE));
        return login(email, PASSWORD);
    }

    static String bearer(Session session) { return "Bearer " + session.accessToken(); }

    record DriverFixture(Session session, String profileId, String vehicleId) { }

    /** Registers a driver, gets the profile and one vehicle approved by the admin. {@code vehicle} overrides the defaults. */
    DriverFixture approvedDriver(Session admin, Map<String, Object> vehicle) throws Exception {
        String email = uniqueEmail();
        registerDriver(email);
        Session driver = login(email, PASSWORD);
        String profileId = body(mvc.perform(post("/api/v1/drivers/me/submit-verification").header("Authorization", bearer(driver)))
                .andExpect(status().isOk()).andReturn()).at("/data/id").asText();
        mvc.perform(post("/api/v1/admin/drivers/" + profileId + "/approve").header("Authorization", bearer(admin))).andExpect(status().isOk());
        String vehicleId = addApprovedVehicle(admin, driver, vehicle);
        return new DriverFixture(driver, profileId, vehicleId);
    }

    String addApprovedVehicle(Session admin, Session driver, Map<String, Object> overrides) throws Exception {
        var vehicle = new java.util.HashMap<String, Object>(Map.of("plateNumber", uniquePlate(), "vehicleType", "SMALL_TRUCK", "maxPayloadKg", 1500,
                "lengthCm", 420, "widthCm", 180, "heightCm", 190, "registrationExpiryDate", java.time.LocalDate.now().plusYears(1).toString()));
        vehicle.putAll(overrides);
        MvcResult created = null;
        // Random plates occasionally collide across a test run; retry with a fresh one.
        for (int attempt = 0; attempt < 5 && (created == null || created.getResponse().getStatus() == 409); attempt++) {
            if (attempt > 0) vehicle.put("plateNumber", uniquePlate());
            created = mvc.perform(post("/api/v1/vehicles").header("Authorization", bearer(driver)).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(vehicle))).andReturn();
        }
        org.assertj.core.api.Assertions.assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String vehicleId = body(created).at("/data/id").asText();
        mvc.perform(post("/api/v1/vehicles/" + vehicleId + "/submit-verification").header("Authorization", bearer(driver))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/vehicles/" + vehicleId + "/approve").header("Authorization", bearer(admin))).andExpect(status().isOk());
        return vehicleId;
    }
}
