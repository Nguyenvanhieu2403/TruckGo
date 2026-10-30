package com.truckgo.driver;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.driver.dto.DriverProfileResponse;
import com.truckgo.driver.dto.UpdateDriverProfileRequest;
import com.truckgo.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/drivers/me")
@PreAuthorize("hasRole('DRIVER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Driver", description = "Hồ sơ tài xế của người dùng hiện tại")
public class DriverController {
    private final DriverService drivers;

    public DriverController(DriverService drivers) { this.drivers = drivers; }

    @GetMapping
    @Operation(summary = "Xem hồ sơ tài xế")
    public ApiResponse<DriverProfileResponse> get(@AuthenticationPrincipal AuthenticatedUser me, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", drivers.getMine(me.id()), request.getRequestURI());
    }

    @PatchMapping
    @Operation(summary = "Cập nhật giấy phép lái xe", description = "Hồ sơ đã duyệt sẽ quay về trạng thái chờ duyệt nếu thông tin giấy phép thay đổi.")
    public ApiResponse<DriverProfileResponse> update(@AuthenticationPrincipal AuthenticatedUser me, @Valid @RequestBody UpdateDriverProfileRequest body, HttpServletRequest request) {
        return ApiResponse.success("Driver profile updated", drivers.updateMine(me.id(), body), request.getRequestURI());
    }

    @PostMapping("/submit-verification")
    @Operation(summary = "Gửi hồ sơ tài xế để quản trị viên duyệt")
    public ApiResponse<DriverProfileResponse> submit(@AuthenticationPrincipal AuthenticatedUser me, HttpServletRequest request) {
        return ApiResponse.success("Driver profile submitted for review", drivers.submitForVerification(me.id()), request.getRequestURI());
    }
}
