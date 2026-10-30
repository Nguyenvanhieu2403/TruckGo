package com.truckgo.admin;

import com.truckgo.auth.RefreshTokenService;
import com.truckgo.auth.SessionRevokeReason;
import com.truckgo.common.exception.ConflictException;
import com.truckgo.common.exception.ForbiddenException;
import com.truckgo.common.exception.ResourceNotFoundException;
import com.truckgo.common.response.PageResponse;
import com.truckgo.user.User;
import com.truckgo.user.UserRepository;
import com.truckgo.user.UserRole;
import com.truckgo.user.UserStatus;
import com.truckgo.user.dto.UserResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AdminUserService {
    private final UserRepository users;
    private final RefreshTokenService refreshTokens;
    private final AdminAuditService audit;

    public AdminUserService(UserRepository users, RefreshTokenService refreshTokens, AdminAuditService audit) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(UserRole role, UserStatus status, Pageable pageable) {
        return PageResponse.of(users.search(role, status, pageable), UserResponse::from);
    }

    /**
     * Suspending or locking signs the user out everywhere: refresh sessions are revoked and the per-request status
     * check rejects any access token still in flight.
     */
    @Transactional
    public UserResponse changeStatus(UUID adminId, UUID userId, UserStatus status, String reason) {
        if (adminId.equals(userId)) throw new ForbiddenException("Không thể thay đổi trạng thái tài khoản của chính mình");
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));
        if (user.getRole() == UserRole.ADMIN) throw new ForbiddenException("Không thể thay đổi trạng thái tài khoản quản trị qua API");
        if (user.getStatus() == status) throw new ConflictException("Tài khoản đã ở trạng thái " + status);
        UserStatus previous = user.getStatus();
        user.setStatus(status);
        if (status != UserStatus.ACTIVE) refreshTokens.revokeAllForUser(userId, SessionRevokeReason.ACCOUNT_DISABLED);
        audit.record(adminId, "USER_STATUS_CHANGED", "USER", userId, previous + " -> " + status + (reason == null || reason.isBlank() ? "" : ": " + reason.trim()));
        return UserResponse.from(users.saveAndFlush(user));
    }
}
