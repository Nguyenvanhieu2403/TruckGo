package com.truckgo.user;

import com.truckgo.common.response.ApiResponse;
import com.truckgo.security.AuthenticatedUser;
import com.truckgo.user.dto.ChangePasswordRequest;
import com.truckgo.user.dto.UpdateProfileRequest;
import com.truckgo.user.dto.UserResponse;
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
@RequestMapping("/api/v1/users/me")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "User", description = "Hồ sơ cá nhân của người dùng hiện tại")
public class UserController {
    private final UserService users;

    public UserController(UserService users) { this.users = users; }

    @GetMapping
    @Operation(summary = "Xem hồ sơ cá nhân")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal AuthenticatedUser me, HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", users.getMe(me.id()), request.getRequestURI());
    }

    @PatchMapping
    @Operation(summary = "Cập nhật hồ sơ cá nhân", description = "Chỉ họ tên, email, số điện thoại. Email/số điện thoại mới ở trạng thái chưa xác minh.")
    public ApiResponse<UserResponse> update(@AuthenticationPrincipal AuthenticatedUser me, @Valid @RequestBody UpdateProfileRequest body, HttpServletRequest request) {
        return ApiResponse.success("Profile updated", users.updateMe(me.id(), body), request.getRequestURI());
    }

    @PostMapping("/change-password")
    @Operation(summary = "Đổi mật khẩu", description = "Thu hồi mọi phiên đăng nhập khác của tài khoản.")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal AuthenticatedUser me, @Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request) {
        users.changePassword(me.id(), me.sessionFamilyId(), body);
        return ApiResponse.success("Password changed", null, request.getRequestURI());
    }
}
