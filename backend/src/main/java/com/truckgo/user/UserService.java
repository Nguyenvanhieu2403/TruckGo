package com.truckgo.user;

import com.truckgo.auth.RefreshTokenService;
import com.truckgo.auth.SessionRevokeReason;
import com.truckgo.common.exception.FieldErrorException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.user.dto.ChangePasswordRequest;
import com.truckgo.user.dto.UpdateProfileRequest;
import com.truckgo.user.dto.UserResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

@Service
public class UserService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, RefreshTokenService refreshTokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
    }

    @Transactional(readOnly = true)
    public UserResponse getMe(UUID userId) { return UserResponse.from(require(userId)); }

    /** Changing e-mail or phone resets its verified flag; nothing is ever marked verified here. */
    @Transactional
    public UserResponse updateMe(UUID userId, UpdateProfileRequest request) {
        User user = require(userId);
        if (request.fullName() != null) {
            String name = request.fullName().trim();
            if (name.length() < 2) throw FieldErrorException.invalid("fullName", "Họ tên dài 2–100 ký tự");
            user.setFullName(name);
        }
        String email = request.email() == null ? user.getEmail() : normalize("email", () -> ContactNormalizer.email(request.email()));
        String phone = request.phone() == null ? user.getPhone() : normalize("phone", () -> ContactNormalizer.phone(request.phone()));
        if (email == null && phone == null) throw FieldErrorException.invalid("email", "Cần giữ lại ít nhất email hoặc số điện thoại");
        if (email != null && !email.equals(user.getEmail()) && users.existsByEmailAndIdNot(email, userId))
            throw FieldErrorException.conflict("email", "Email đã được sử dụng");
        if (phone != null && !phone.equals(user.getPhone()) && users.existsByPhoneAndIdNot(phone, userId))
            throw FieldErrorException.conflict("phone", "Số điện thoại đã được sử dụng");
        user.changeEmail(email);
        user.changePhone(phone);
        return UserResponse.from(users.saveAndFlush(user));
    }

    /** Revokes every other refresh session; the session that made the request stays signed in. */
    @Transactional
    public void changePassword(UUID userId, UUID currentSessionFamily, ChangePasswordRequest request) {
        User user = require(userId);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash()))
            throw FieldErrorException.invalid("currentPassword", "Mật khẩu hiện tại không đúng");
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash()))
            throw FieldErrorException.invalid("newPassword", "Mật khẩu mới phải khác mật khẩu hiện tại");
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        int revoked = refreshTokens.revokeOtherSessions(userId, currentSessionFamily, SessionRevokeReason.PASSWORD_CHANGED);
        log.info("Password changed userId={} revokedSessions={}", userId, revoked);
    }

    private User require(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));
    }

    private static String normalize(String field, Supplier<String> normalizer) {
        try { return normalizer.get(); }
        catch (IllegalArgumentException ex) { throw FieldErrorException.invalid(field, ex.getMessage()); }
    }
}
