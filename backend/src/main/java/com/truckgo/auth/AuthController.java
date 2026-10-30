package com.truckgo.auth;

import com.truckgo.auth.dto.AuthTokenResponse;
import com.truckgo.auth.dto.LoginRequest;
import com.truckgo.auth.dto.RegisterCustomerRequest;
import com.truckgo.auth.dto.RegisterDriverRequest;
import com.truckgo.auth.dto.RegistrationResponse;
import com.truckgo.common.response.ApiResponse;
import com.truckgo.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Đăng ký, đăng nhập, làm mới phiên và đăng xuất")
public class AuthController {
    private final AuthService auth;
    private final RefreshCookieService cookies;

    public AuthController(AuthService auth, RefreshCookieService cookies) {
        this.auth = auth;
        this.cookies = cookies;
    }

    @PostMapping("/register/customer")
    @Operation(summary = "Đăng ký khách hàng", description = "Vai trò CUSTOMER do máy chủ gán; trường role trong request bị bỏ qua.")
    public ResponseEntity<ApiResponse<RegistrationResponse>> registerCustomer(@Valid @RequestBody RegisterCustomerRequest body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Registration successful", auth.registerCustomer(body), request.getRequestURI()));
    }

    @PostMapping("/register/driver")
    @Operation(summary = "Đăng ký tài xế", description = "Tạo tài khoản DRIVER và hồ sơ tài xế ở trạng thái NOT_SUBMITTED.")
    public ResponseEntity<ApiResponse<RegistrationResponse>> registerDriver(@Valid @RequestBody RegisterDriverRequest body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Registration successful", auth.registerDriver(body), request.getRequestURI()));
    }

    @PostMapping("/login")
    @Operation(summary = "Đăng nhập bằng email hoặc số điện thoại", description = "Trả access token trong body; refresh token đặt trong cookie HttpOnly.")
    public ResponseEntity<ApiResponse<AuthTokenResponse>> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        AuthService.LoginResult result = auth.login(body, request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
        return tokenResponse("Login successful", result, request);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Làm mới access token", description = "Dùng cookie refresh token; yêu cầu header X-Requested-With. Refresh token được xoay vòng.")
    public ResponseEntity<ApiResponse<AuthTokenResponse>> refresh(HttpServletRequest request) {
        cookies.verifyRequestOrigin(request);
        // A failed refresh deliberately leaves the cookie alone: in a two-tab race the browser may already hold the
        // newer cookie set by the winning request, and clearing it here would log both tabs out.
        AuthService.LoginResult result = auth.refresh(cookies.read(request), request.getHeader(HttpHeaders.USER_AGENT));
        return tokenResponse("Token refreshed", result, request);
    }

    @PostMapping("/logout")
    @Operation(summary = "Đăng xuất", description = "Thu hồi phiên refresh hiện tại và xóa cookie. Access token đã cấp vẫn hợp lệ tới khi hết hạn (tối đa 15 phút).")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        cookies.verifyRequestOrigin(request);
        auth.logout(cookies.read(request));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .body(ApiResponse.success("Logout successful", null, request.getRequestURI()));
    }

    private ResponseEntity<ApiResponse<AuthTokenResponse>> tokenResponse(String message, AuthService.LoginResult result, HttpServletRequest request) {
        AuthTokenResponse body = AuthTokenResponse.bearer(result.accessToken().value(), result.accessToken().expiresInSeconds(), UserResponse.from(result.user()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.create(result.refreshToken()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.success(message, body, request.getRequestURI()));
    }
}
