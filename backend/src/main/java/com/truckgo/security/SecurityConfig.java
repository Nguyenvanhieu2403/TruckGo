package com.truckgo.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/** Web-only: the one-shot {@code --bootstrap-admin} command runs without a servlet context. */
@Configuration
@ConditionalOnWebApplication
@EnableMethodSecurity
public class SecurityConfig {
    private static final String[] PUBLIC_AUTH = {
            "/api/v1/auth/register/customer", "/api/v1/auth/register/driver", "/api/v1/auth/login",
            "/api/v1/auth/refresh", "/api/v1/auth/logout"
    };

    /**
     * CSRF protection is disabled because authenticated APIs only accept a Bearer header, which browsers never attach
     * automatically. The only cookie-authenticated endpoints (refresh/logout) are guarded by SameSite, an Origin
     * allow-list and a mandatory custom header (see {@code RefreshCookieGuard}).
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder, JwtUserConverter converter, JsonSecurityErrorHandler errors) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Disabled entirely in the prod profile (springdoc.*.enabled=false).
                        .requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/drivers/**", "/api/v1/vehicles/**").hasRole("DRIVER")
                        // Drivers get a restricted view of published orders in Sprint 5, not these endpoints.
                        .requestMatchers("/api/v1/shipments/**").hasAnyRole("CUSTOMER", "ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(errors).accessDeniedHandler(errors));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtTokenService tokens) { return tokens.decoder(); }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        List<String> origins = properties.corsAllowedOrigins().stream().map(String::trim).filter(o -> !o.isEmpty()).toList();
        if (origins.contains("*")) throw new IllegalStateException("CORS_ALLOWED_ORIGINS must list explicit origins when credentials are allowed");
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-ID", "X-Requested-With"));
        config.setExposedHeaders(List.of("X-Request-ID", "Retry-After"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
