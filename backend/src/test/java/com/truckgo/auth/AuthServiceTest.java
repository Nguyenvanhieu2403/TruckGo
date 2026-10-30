package com.truckgo.auth;

import com.truckgo.auth.dto.LoginRequest;
import com.truckgo.auth.dto.RegisterCustomerRequest;
import com.truckgo.auth.dto.RegisterDriverRequest;
import com.truckgo.common.domain.VerificationStatus;
import com.truckgo.common.exception.FieldErrorException;
import com.truckgo.common.exception.ForbiddenException;
import com.truckgo.common.exception.TooManyRequestsException;
import com.truckgo.common.exception.UnauthorizedException;
import com.truckgo.driver.DriverLicenseValidator;
import com.truckgo.driver.DriverProfile;
import com.truckgo.driver.DriverProfileRepository;
import com.truckgo.driver.DriverProperties;
import com.truckgo.security.JwtTokenService;
import com.truckgo.user.User;
import com.truckgo.user.UserRepository;
import com.truckgo.user.UserRole;
import com.truckgo.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {
    private static final String PASSWORD = "Example-Password-123";

    private final UserRepository users = mock(UserRepository.class);
    private final DriverProfileRepository profiles = mock(DriverProfileRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final JwtTokenService jwt = mock(JwtTokenService.class);
    private final LoginAttemptService attempts = mock(LoginAttemptService.class);
    private final DriverLicenseValidator licenses = new DriverLicenseValidator(new DriverProperties("^[0-9]{12}$", List.of("B", "C1", "C", "CE")));
    private final AuthService service = new AuthService(users, profiles, licenses, encoder, refreshTokens, jwt, attempts);

    @BeforeEach void setUp() {
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(profiles.saveAndFlush(any(DriverProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(refreshTokens.startSession(any(), any())).thenReturn(new RefreshTokenService.IssuedToken("refresh", UUID.randomUUID(), UUID.randomUUID()));
        when(jwt.issue(any(), any())).thenReturn(new JwtTokenService.AccessToken("access", 900));
    }

    @Test void registersCustomerWithServerAssignedRoleAndHashedPassword() {
        var response = service.registerCustomer(new RegisterCustomerRequest(" Nguyen Van A ", "Customer@Example.com", "0912345678", PASSWORD, PASSWORD));
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        User user = saved.getValue();
        assertThat(user.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(user.getEmail()).isEqualTo("customer@example.com");
        assertThat(user.getPhone()).isEqualTo("+84912345678");
        assertThat(user.getFullName()).isEqualTo("Nguyen Van A");
        assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(response.user().role()).isEqualTo(UserRole.CUSTOMER);
    }

    @Test void rejectsDuplicateEmailOrPhone() {
        when(users.existsByEmail("dup@example.com")).thenReturn(true);
        assertThatThrownBy(() -> service.registerCustomer(new RegisterCustomerRequest("Nguyen Van A", "dup@example.com", null, PASSWORD, PASSWORD)))
                .isInstanceOfSatisfying(FieldErrorException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.errors()).containsKey("email");
                });
        when(users.existsByPhone("+84987654321")).thenReturn(true);
        assertThatThrownBy(() -> service.registerCustomer(new RegisterCustomerRequest("Nguyen Van A", null, "0987654321", PASSWORD, PASSWORD)))
                .isInstanceOfSatisfying(FieldErrorException.class, ex -> assertThat(ex.errors()).containsKey("phone"));
    }

    @Test void registersDriverWithUnapprovedProfile() {
        var response = service.registerDriver(new RegisterDriverRequest("Nguyen Van B", "driver@example.com", null, PASSWORD, PASSWORD, "0123 4567 8901", "c"));
        ArgumentCaptor<DriverProfile> profile = ArgumentCaptor.forClass(DriverProfile.class);
        verify(profiles).saveAndFlush(profile.capture());
        assertThat(profile.getValue().getLicenseNumber()).isEqualTo("012345678901");
        assertThat(profile.getValue().getLicenseClass()).isEqualTo("C");
        assertThat(profile.getValue().getVerificationStatus()).isEqualTo(VerificationStatus.NOT_SUBMITTED);
        assertThat(response.user().role()).isEqualTo(UserRole.DRIVER);
        assertThat(response.driverVerificationStatus()).isEqualTo(VerificationStatus.NOT_SUBMITTED);
    }

    @Test void rejectsUnsupportedLicence() {
        assertThatThrownBy(() -> service.registerDriver(new RegisterDriverRequest("Nguyen Van B", "d@example.com", null, PASSWORD, PASSWORD, "LICENSE-DEMO", "C")))
                .isInstanceOfSatisfying(FieldErrorException.class, ex -> assertThat(ex.errors()).containsKey("licenseNumber"));
        assertThatThrownBy(() -> service.registerDriver(new RegisterDriverRequest("Nguyen Van B", "d@example.com", null, PASSWORD, PASSWORD, "012345678901", "A1")))
                .isInstanceOfSatisfying(FieldErrorException.class, ex -> assertThat(ex.errors()).containsKey("licenseClass"));
        verify(users, never()).saveAndFlush(any());
    }

    @Test void loginWithEmailOrPhoneSucceeds() {
        User user = activeUser();
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(user));
        when(users.findByPhone("+84912345678")).thenReturn(Optional.of(user));
        assertThat(service.login(new LoginRequest("A@example.com", PASSWORD), "127.0.0.1", null).accessToken().value()).isEqualTo("access");
        assertThat(service.login(new LoginRequest("+84 912 345 678", PASSWORD), "127.0.0.1", null).refreshToken()).isEqualTo("refresh");
        assertThat(user.getLastLoginAt()).isNotNull();
        verify(attempts, org.mockito.Mockito.times(2)).recordSuccess(anyString());
    }

    @Test void wrongPasswordAndUnknownUserGiveTheSameError() {
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(activeUser()));
        assertThatThrownBy(() -> service.login(new LoginRequest("a@example.com", "Wrong-Password-1"), "127.0.0.1", null))
                .isInstanceOf(UnauthorizedException.class).hasMessage(AuthService.INVALID_CREDENTIALS);
        assertThatThrownBy(() -> service.login(new LoginRequest("nobody@example.com", PASSWORD), "127.0.0.1", null))
                .isInstanceOf(UnauthorizedException.class).hasMessage(AuthService.INVALID_CREDENTIALS);
        verify(attempts, org.mockito.Mockito.times(2)).recordFailure(anyString());
        verify(refreshTokens, never()).startSession(any(), any());
    }

    @Test void lockedAccountCannotLogin() {
        User user = activeUser();
        user.setStatus(UserStatus.LOCKED);
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.login(new LoginRequest("a@example.com", PASSWORD), "127.0.0.1", null)).isInstanceOf(ForbiddenException.class);
        verify(refreshTokens, never()).startSession(any(), any());
    }

    @Test void throttledLoginIsRejectedBeforePasswordCheck() {
        doThrow(new TooManyRequestsException("slow down", 60)).when(attempts).checkAllowed(anyString(), eq("10.0.0.1"));
        assertThatThrownBy(() -> service.login(new LoginRequest("a@example.com", PASSWORD), "10.0.0.1", null)).isInstanceOf(TooManyRequestsException.class);
        verify(users, never()).findByEmail(any());
    }

    private User activeUser() {
        return new User("Nguyen Van A", "a@example.com", "+84912345678", encoder.encode(PASSWORD), UserRole.CUSTOMER, UserStatus.ACTIVE);
    }
}
