package com.truckgo.shipment;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.common.response.PageResponse;
import com.truckgo.shipment.dto.ShipmentResponses.ShipmentSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Read-only overview for admins; details use GET /shipments/{id}. */
@RestController
@RequestMapping("/api/v1/admin/shipments")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin", description = "Xem đơn vận chuyển")
public class AdminShipmentController {
    private final ShipmentService shipments;

    public AdminShipmentController(ShipmentService shipments) { this.shipments = shipments; }

    @GetMapping
    @Operation(summary = "Tất cả đơn vận chuyển")
    public ApiResponse<PageResponse<ShipmentSummary>> list(@RequestParam(required = false) ShipmentStatus status, @RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                                           @RequestParam(defaultValue = "createdAt") String sortBy, @RequestParam(defaultValue = "desc") String sortDirection,
                                                           HttpServletRequest request) {
        ShipmentFilter filter = new ShipmentFilter(status, keyword, fromDate, toDate, page, size, sortBy, sortDirection);
        return ApiResponse.success("Request completed successfully", shipments.listAll(filter), request.getRequestURI());
    }
}
