package com.truckgo.auth;

import com.truckgo.common.exception.ForbiddenException;
import com.truckgo.security.SecurityProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the HttpOnly refresh-token cookie and guards the cookie-authenticated endpoints against CSRF:
 * the cookie is SameSite and path-scoped to /api/v1/auth, the request must carry {@code X-Requested-With}
 * (a header cross-site forms cannot send and cross-origin scripts cannot send without a CORS preflight), and a
 * present Origin header must be in the CORS allow-list.
 */
@Component
public class RefreshCookieService {
    public static final String CSRF_HEADER = "X-Requested-With";

    private final SecurityProperties.Refresh config;
    private final List<String> allowedOrigins;

    public RefreshCookieService(SecurityProperties properties, Environment environment) {
        this.config = properties.refresh();
        if (!config.cookieSecure() && environment.acceptsProfiles(Profiles.of("prod")))
            throw new IllegalStateException("REFRESH_COOKIE_SECURE=false is not allowed in the prod profile");
        this.allowedOrigins = properties.corsAllowedOrigins().stream().map(String::trim).toList();
    }

    public ResponseCookie create(String rawToken) { return base(rawToken).maxAge(config.ttl()).build(); }

    public ResponseCookie clear() { return base("").maxAge(Duration.ZERO).build(); }

    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies).filter(c -> config.cookieName().equals(c.getName())).map(Cookie::getValue).findFirst().orElse(null);
    }

    public void verifyRequestOrigin(HttpServletRequest request) {
        if (request.getHeader(CSRF_HEADER) == null) throw new ForbiddenException("Yêu cầu không hợp lệ");
        String origin = request.getHeader("Origin");
        if (origin != null && !isSameOrigin(request, origin) && !allowedOrigins.contains(origin)) throw new ForbiddenException("Yêu cầu không hợp lệ");
    }

    private static boolean isSameOrigin(HttpServletRequest request, String origin) {
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(request.getScheme()) && port == 80) || ("https".equals(request.getScheme()) && port == 443);
        String self = request.getScheme() + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
        return self.equalsIgnoreCase(origin);
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(config.cookieName(), value)
                .httpOnly(true)
                .secure(config.cookieSecure())
                .sameSite(config.cookieSameSite())
                .path(config.cookiePath());
    }
}
