package com.truckgo.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectRequest(
        @NotBlank(message = "Vui lòng nhập lý do từ chối") @Size(min = 5, max = 500, message = "Lý do dài 5–500 ký tự") String reason) { }
