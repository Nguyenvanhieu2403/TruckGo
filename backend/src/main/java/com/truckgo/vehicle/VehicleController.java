package com.truckgo.vehicle;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.security.AuthenticatedUser;
import com.truckgo.vehicle.dto.CreateVehicleRequest;
import com.truckgo.vehicle.dto.UpdateVehicleRequest;
import com.truckgo.vehicle.dto.VehicleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vehicles")
@PreAuthorize("hasRole('DRIVER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Vehicle", description = "Phương tiện của tài xế hiện tại")
public class VehicleController {
    private final VehicleService vehicles;

    public VehicleController(VehicleService vehicles) { this.vehicles = vehicles; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Đăng ký phương tiện mới")
    public ApiResponse<VehicleResponse> create(@AuthenticationPrincipal AuthenticatedUser me, @Valid @RequestBody CreateVehicleRequest body, HttpServletRequest request) {
        return ApiResponse.success("Vehicle created", vehicles.create(me.id(), body), request.getRequestURI());
    }

    @GetMapping("/my")
    @Operation(summary = "Danh sách phương tiện của tôi")
    public ApiResponse<List<VehicleResponse>> mine(@AuthenticationPrincipal AuthenticatedUser me, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", vehicles.listMine(me.id()), request.getRequestURI());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết phương tiện của tôi")
    public ApiResponse<VehicleResponse> get(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", vehicles.getMine(me.id(), id), request.getRequestURI());
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Cập nhật phương tiện", description = "Phương tiện đã duyệt sẽ quay về trạng thái chờ duyệt nếu thông tin thay đổi.")
    public ApiResponse<VehicleResponse> update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, @Valid @RequestBody UpdateVehicleRequest body, HttpServletRequest request) {
        return ApiResponse.success("Vehicle updated", vehicles.update(me.id(), id, body), request.getRequestURI());
    }

    @PostMapping("/{id}/submit-verification")
    @Operation(summary = "Gửi phương tiện để quản trị viên duyệt")
    public ApiResponse<VehicleResponse> submit(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success("Vehicle submitted for review", vehicles.submitForVerification(me.id(), id), request.getRequestURI());
    }
}
