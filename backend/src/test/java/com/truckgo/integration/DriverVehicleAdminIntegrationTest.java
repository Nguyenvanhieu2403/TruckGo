package com.truckgo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.truckgo.admin.AdminAuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
class DriverVehicleAdminIntegrationTest extends IntegrationTestSupport {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired AdminAuditLogRepository auditLogs;

    @Test void customerAndDriverCannotReachAdminEndpoints() throws Exception {
        String customerEmail = uniqueEmail();
        registerCustomer(customerEmail, null);
        Session customer = login(customerEmail, PASSWORD);
        String driverEmail = uniqueEmail();
        registerDriver(driverEmail);
        Session driver = login(driverEmail, PASSWORD);
        for (Session session : new Session[]{customer, driver}) {
            mvc.perform(get("/api/v1/admin/drivers/pending").header("Authorization", bearer(session))).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/drivers/" + UUID.randomUUID() + "/approve").header("Authorization", bearer(session))).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/vehicles/" + UUID.randomUUID() + "/approve").header("Authorization", bearer(session))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/drivers/me").header("Authorization", bearer(customer))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/vehicles/my").header("Authorization", bearer(customer))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/drivers/pending")).andExpect(status().isUnauthorized());
    }

    @Test void driverCannotSelfApproveProfile() throws Exception {
        String email = uniqueEmail();
        registerDriver(email);
        Session driver = login(email, PASSWORD);
        String license = uniqueLicense();
        patchJson("/api/v1/drivers/me", driver, Map.of("licenseNumber", license, "licenseClass", "CE", "verificationStatus", "APPROVED", "reviewedBy", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.licenseNumber").value(license))
                .andExpect(jsonPath("$.data.licenseClass").value("CE"))
                .andExpect(jsonPath("$.data.verificationStatus").value("NOT_SUBMITTED"));
        mvc.perform(post("/api/v1/drivers/me/submit-verification").header("Authorization", bearer(driver)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.verificationStatus").value("PENDING_REVIEW"));
        mvc.perform(post("/api/v1/drivers/me/submit-verification").header("Authorization", bearer(driver))).andExpect(status().isConflict());
        patchJson("/api/v1/drivers/me", driver, Map.of("licenseNumber", license, "licenseClass", "C")).andExpect(status().isConflict());
    }

    @Test void adminApprovesAndRejectsDriverProfiles() throws Exception {
        Session admin = newAdmin();
        String email = uniqueEmail();
        registerDriver(email);
        Session driver = login(email, PASSWORD);
        String profileId = body(mvc.perform(post("/api/v1/drivers/me/submit-verification").header("Authorization", bearer(driver))).andReturn()).at("/data/id").asText();

        JsonNode pending = body(mvc.perform(get("/api/v1/admin/drivers/pending?page=0&size=100").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn()).get("data");
        assertThat(pending.get("totalElements").asLong()).isPositive();
        assertThat(pending.get("items").findValuesAsText("id")).contains(profileId);

        postAuthorized("/api/v1/admin/drivers/" + profileId + "/reject", admin, Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.reason").exists());
        postAuthorized("/api/v1/admin/drivers/" + profileId + "/reject", admin, Map.of("reason", "Ảnh giấy phép không rõ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.verificationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.profile.rejectionReason").value("Ảnh giấy phép không rõ"));
        mvc.perform(post("/api/v1/admin/drivers/" + profileId + "/approve").header("Authorization", bearer(admin))).andExpect(status().isConflict());

        mvc.perform(post("/api/v1/drivers/me/submit-verification").header("Authorization", bearer(driver))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/drivers/" + profileId + "/approve").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.profile.verificationStatus").value("APPROVED"));
        mvc.perform(get("/api/v1/drivers/me").header("Authorization", bearer(driver)))
                .andExpect(jsonPath("$.data.verificationStatus").value("APPROVED")).andExpect(jsonPath("$.data.reviewedAt").isNotEmpty());
        mvc.perform(post("/api/v1/admin/drivers/" + UUID.randomUUID() + "/approve").header("Authorization", bearer(admin))).andExpect(status().isNotFound());

        UUID adminId = UUID.fromString(admin.user().get("id").asText());
        assertThat(auditLogs.findByTargetIdOrderByCreatedAtAsc(UUID.fromString(profileId)))
                .extracting(log -> log.getAction() + ":" + log.getActorId())
                .containsExactly("DRIVER_REJECTED:" + adminId, "DRIVER_APPROVED:" + adminId);
    }

    @Test void vehicleLifecycleWithOwnershipChecks() throws Exception {
        Session admin = newAdmin();
        String ownerEmail = uniqueEmail();
        registerDriver(ownerEmail);
        Session owner = login(ownerEmail, PASSWORD);
        String otherEmail = uniqueEmail();
        registerDriver(otherEmail);
        Session other = login(otherEmail, PASSWORD);

        Map<String, Object> vehicle = new HashMap<>(Map.of("plateNumber", uniquePlate().replaceFirst("(\\d{2}[A-Z])(\\d{3})(\\d{2})", "$1-$2.$3"),
                "vehicleType", "SMALL_TRUCK", "maxPayloadKg", 1500, "lengthCm", 420, "widthCm", 180, "heightCm", 190,
                "registrationExpiryDate", LocalDate.now().plusYears(1).toString()));
        vehicle.put("driverId", UUID.randomUUID().toString());
        vehicle.put("verificationStatus", "APPROVED");
        JsonNode created = body(postAuthorized("/api/v1/vehicles", owner, vehicle)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.verificationStatus").value("NOT_SUBMITTED"))
                .andReturn()).get("data");
        String vehicleId = created.get("id").asText();
        String ownerProfileId = body(mvc.perform(get("/api/v1/drivers/me").header("Authorization", bearer(owner))).andReturn()).at("/data/id").asText();
        assertThat(created.get("driverId").asText()).isEqualTo(ownerProfileId);

        postAuthorized("/api/v1/vehicles", owner, Map.of("plateNumber", "51C-999.99", "vehicleType", "SMALL_TRUCK", "maxPayloadKg", 0, "lengthCm", -1,
                "widthCm", 180, "heightCm", 190, "registrationExpiryDate", LocalDate.now().plusYears(1).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.maxPayloadKg").exists()).andExpect(jsonPath("$.data.lengthCm").exists());
        postAuthorized("/api/v1/vehicles", other, Map.of("plateNumber", created.get("plateNumber").asText(), "vehicleType", "VAN", "maxPayloadKg", 900, "lengthCm", 300,
                "widthCm", 160, "heightCm", 170, "registrationExpiryDate", LocalDate.now().plusYears(1).toString()))
                .andExpect(status().isConflict());

        // Another driver can neither read, edit nor submit it.
        mvc.perform(get("/api/v1/vehicles/" + vehicleId).header("Authorization", bearer(other))).andExpect(status().isNotFound());
        patchJson("/api/v1/vehicles/" + vehicleId, other, Map.of("maxPayloadKg", 9999)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/vehicles/" + vehicleId + "/submit-verification").header("Authorization", bearer(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/vehicles/my").header("Authorization", bearer(other))).andExpect(jsonPath("$.data.length()").value(0));

        patchJson("/api/v1/vehicles/" + vehicleId, owner, Map.of("maxPayloadKg", 1800, "verificationStatus", "APPROVED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.maxPayloadKg").value(1800)).andExpect(jsonPath("$.data.verificationStatus").value("NOT_SUBMITTED"));
        mvc.perform(post("/api/v1/vehicles/" + vehicleId + "/submit-verification").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.verificationStatus").value("PENDING_REVIEW"));

        JsonNode pending = body(mvc.perform(get("/api/v1/admin/vehicles/pending?size=100").header("Authorization", bearer(admin))).andExpect(status().isOk()).andReturn());
        assertThat(pending.at("/data/items").findValuesAsText("id")).contains(vehicleId);
        mvc.perform(post("/api/v1/admin/vehicles/" + vehicleId + "/approve").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.vehicle.verificationStatus").value("APPROVED"));

        // Editing an approved vehicle sends it back for review.
        patchJson("/api/v1/vehicles/" + vehicleId, owner, Map.of("heightCm", 200))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.verificationStatus").value("PENDING_REVIEW"));
        postAuthorized("/api/v1/admin/vehicles/" + vehicleId + "/reject", admin, Map.of("reason", "Đăng kiểm không khớp"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.vehicle.rejectionReason").value("Đăng kiểm không khớp"));
    }

    @Test void adminCanSuspendUserButNotThemselves() throws Exception {
        Session admin = newAdmin();
        String email = uniqueEmail();
        registerCustomer(email, null);
        Session customer = login(email, PASSWORD);
        String customerId = customer.user().get("id").asText();
        postAuthorized("/api/v1/admin/users/" + customerId + "/status", admin, Map.of("status", "SUSPENDED", "reason", "Spam"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUSPENDED"));
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(customer))).andExpect(status().isUnauthorized());
        refresh(customer.refreshToken()).andExpect(status().isUnauthorized());
        postAuthorized("/api/v1/admin/users/" + admin.user().get("id").asText() + "/status", admin, Map.of("status", "LOCKED"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/users?role=CUSTOMER&status=SUSPENDED").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").isNumber());
    }

    private ResultActions postAuthorized(String url, Session session, Object body) throws Exception {
        return mvc.perform(post(url).header("Authorization", bearer(session)).contentType("application/json").content(json.writeValueAsString(body)));
    }

    private ResultActions patchJson(String url, Session session, Object body) throws Exception {
        return mvc.perform(patch(url).header("Authorization", bearer(session)).contentType("application/json").content(json.writeValueAsString(body)));
    }
}
