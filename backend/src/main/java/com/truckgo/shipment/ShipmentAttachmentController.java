package com.truckgo.shipment;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.security.AuthenticatedUser;
import com.truckgo.shipment.dto.ShipmentResponses.AttachmentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shipments/{shipmentId}/attachments")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Shipment", description = "Ảnh hàng hóa")
public class ShipmentAttachmentController {
    private final ShipmentAttachmentService attachments;

    public ShipmentAttachmentController(ShipmentAttachmentService attachments) { this.attachments = attachments; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Tải ảnh hàng hóa", description = "Trường multipart \"file\"; JPEG/PNG/WebP, tối đa 5 MB; chỉ khi đơn là nháp.")
    public ApiResponse<AttachmentResponse> upload(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId,
                                                  @RequestParam("file") MultipartFile file, HttpServletRequest request) {
        return ApiResponse.success("Attachment uploaded", attachments.upload(me.id(), shipmentId, file), request.getRequestURI());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @Operation(summary = "Danh sách ảnh của đơn")
    public ApiResponse<List<AttachmentResponse>> list(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", attachments.list(me, shipmentId), request.getRequestURI());
    }

    @DeleteMapping("/{attachmentId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Xóa ảnh", description = "Xóa metadata và tệp trên object storage.")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, @PathVariable UUID attachmentId, HttpServletRequest request) {
        attachments.delete(me.id(), shipmentId, attachmentId);
        return ApiResponse.success("Attachment deleted", null, request.getRequestURI());
    }

    @GetMapping("/{attachmentId}/content")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @Operation(summary = "Nội dung ảnh", description = "Truyền qua API sau khi kiểm tra quyền; bucket không bao giờ công khai.")
    public ResponseEntity<InputStreamResource> content(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID shipmentId, @PathVariable UUID attachmentId) {
        ShipmentAttachmentService.Content content = attachments.open(me, shipmentId, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.size())
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(content.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(new InputStreamResource(content.stream()));
    }
}
