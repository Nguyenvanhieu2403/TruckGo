package com.truckgo.common.web;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.common.response.HealthData;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "System", description = "Trạng thái API nền tảng")
public class HealthController {
    @GetMapping
    @Operation(summary = "Kiểm tra backend", description = "Xác nhận REST API đang nhận và xử lý yêu cầu.")
    public ApiResponse<HealthData> health(HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", new HealthData("TruckGo API", "UP"), request.getRequestURI());
    }
}
