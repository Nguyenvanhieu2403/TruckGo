package com.truckgo.auth;

import com.truckgo.auth.dto.AccountRegistration;
import com.truckgo.auth.dto.LoginRequest;
import com.truckgo.auth.dto.RegisterCustomerRequest;
import com.truckgo.auth.dto.RegisterDriverRequest;
import com.truckgo.auth.dto.RegistrationResponse;
import com.truckgo.common.exception.FieldErrorException;
import com.truckgo.common.exception.ForbiddenException;
import com.truckgo.common.exception.UnauthorizedException;
import com.truckgo.driver.DriverLicenseValidator;
import com.truckgo.driver.DriverProfile;
import com.truckgo.driver.DriverProfileRepository;
import com.truckgo.security.JwtTokenService;
import com.truckgo.user.ContactNormalizer;
import com.truckgo.user.User;
import com.truckgo.user.UserRepository;
import com.truckgo.user.UserRole;
import com.truckgo.user.UserStatus;
import com.truckgo.user.dto.UserResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    static final String INVALID_CREDENTIALS = "Thông tin đăng nhập không chính xác";
    /**
     * New accounts are ACTIVE with unverified contacts because no OTP/e-mail verification channel exists yet.
     * When that flow ships, switch this to PENDING_VERIFICATION.
     */
    static final UserStatus INITIAL_STATUS = UserStatus.ACTIVE;

    private final UserRepository users;
    private final DriverProfileRepository driverProfiles;
    private final DriverLicenseValidator licenseValidator;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final JwtTokenService jwt;
    private final LoginAttemptService loginAttempts;
    /** Compared against when the identifier is unknown so response time does not reveal whether an account exists. */
    private final String dummyHash;

    public AuthService(UserRepository users, DriverProfileRepository driverProfiles, DriverLicenseValidator licenseValidator, PasswordEncoder passwordEncoder,
                       RefreshTokenService refreshTokens, JwtTokenService jwt, LoginAttemptService loginAttempts) {
        this.users = users;
        this.driverProfiles = driverProfiles;
        this.licenseValidator = licenseValidator;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.jwt = jwt;
        this.loginAttempts = loginAttempts;
        this.dummyHash = passwordEncoder.encode("timing-equaliser-" + System.nanoTime());
    }

    public record LoginResult(User user, JwtTokenService.AccessToken accessToken, String refreshToken) { }

    @Transactional
    public RegistrationResponse registerCustomer(RegisterCustomerRequest request) {
        User user = createUser(request, UserRole.CUSTOMER);
        log.info("Customer registered userId={}", user.getId());
        return new RegistrationResponse(UserResponse.from(user), null);
    }

    /** User and driver profile are created in one transaction: if the profile fails, the account is rolled back too. */
    @Transactional
    public RegistrationResponse registerDriver(RegisterDriverRequest request) {
        DriverLicenseValidator.License license = licenseValidator.normalize(request.licenseNumber(), request.licenseClass());
        if (driverProfiles.existsByLicenseNumber(license.number()))
            throw FieldErrorException.conflict("licenseNumber", "Số giấy phép lái xe đã được đăng ký");
        User user = createUser(request, UserRole.DRIVER);
        DriverProfile profile = driverProfiles.saveAndFlush(new DriverProfile(user.getId(), license.number(), license.licenseClass()));
        log.info("Driver registered userId={} driverProfileId={}", user.getId(), profile.getId());
        return new RegistrationResponse(UserResponse.from(user), profile.getVerificationStatus());
    }

    @Transactional
    public LoginResult login(LoginRequest request, String clientIp, String userAgent) {
        Optional<String> normalized = normalizeIdentifier(request.identifier());
        String throttleKey = throttleKey(normalized.orElse(request.identifier().trim().toLowerCase(Locale.ROOT)));
        loginAttempts.checkAllowed(throttleKey, clientIp);

        Optional<User> found = normalized.flatMap(this::findByIdentifier);
        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyHash);
            loginAttempts.recordFailure(throttleKey);
            throw new UnauthorizedException(INVALID_CREDENTIALS);
        }
        User user = found.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttempts.recordFailure(throttleKey);
            log.info("Login failed userId={}", user.getId());
            throw new UnauthorizedException(INVALID_CREDENTIALS);
        }
        // Status is only disclosed once the password has been proven.
        if (!user.isActive()) {
            log.info("Login rejected for inactive account userId={} status={}", user.getId(), user.getStatus());
            throw new ForbiddenException(user.getStatus() == UserStatus.PENDING_VERIFICATION
                    ? "Tài khoản chưa được xác minh" : "Tài khoản đã bị khóa hoặc tạm ngưng");
        }
        loginAttempts.recordSuccess(throttleKey);
        user.setLastLoginAt(Instant.now());
        RefreshTokenService.IssuedToken refresh = refreshTokens.startSession(user, userAgent);
        log.info("Login succeeded userId={}", user.getId());
        return new LoginResult(user, jwt.issue(user, refresh.familyId()), refresh.rawToken());
    }

    /** Not transactional here: rotate() must be able to commit a family revocation while still failing with 401. */
    public LoginResult refresh(String rawRefreshToken, String userAgent) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken, userAgent);
        return new LoginResult(rotation.user(), jwt.issue(rotation.user(), rotation.token().familyId()), rotation.token().rawToken());
    }

    public void logout(String rawRefreshToken) { refreshTokens.revoke(rawRefreshToken); }

    private User createUser(AccountRegistration request, UserRole role) {
        String email = normalizeField("email", () -> ContactNormalizer.email(request.email()));
        String phone = normalizeField("phone", () -> ContactNormalizer.phone(request.phone()));
        if (email != null && users.existsByEmail(email)) throw FieldErrorException.conflict("email", "Email đã được sử dụng");
        if (phone != null && users.existsByPhone(phone)) throw FieldErrorException.conflict("phone", "Số điện thoại đã được sử dụng");
        User user = new User(request.fullName().trim(), email, phone, passwordEncoder.encode(request.password()), role, INITIAL_STATUS);
        return users.saveAndFlush(user);
    }

    private Optional<String> normalizeIdentifier(String identifier) {
        try {
            return Optional.ofNullable(ContactNormalizer.looksLikeEmail(identifier) ? ContactNormalizer.email(identifier) : ContactNormalizer.phone(identifier));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private Optional<User> findByIdentifier(String normalized) {
        return normalized.startsWith("+") ? users.findByPhone(normalized) : users.findByEmail(normalized);
    }

    static String normalizeField(String field, java.util.function.Supplier<String> normalizer) {
        try { return normalizer.get(); }
        catch (IllegalArgumentException ex) { throw FieldErrorException.invalid(field, ex.getMessage()); }
    }

    private static String throttleKey(String identifier) {
        return RefreshTokenService.hash(identifier).substring(0, 32);
    }
}
