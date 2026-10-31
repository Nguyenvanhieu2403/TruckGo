package com.truckgo.geo;

import com.truckgo.common.exception.BusinessRuleException;
import com.truckgo.common.exception.TooManyRequestsException;
import com.truckgo.common.response.ApiResponse;
import com.truckgo.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/** Address search for the order form. Only signed-in users, with a per-user rate limit to protect the API key. */
@RestController
@RequestMapping("/api/v1/geo")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Geocoding", description = "Tìm địa chỉ và tọa độ (khi đã cấu hình nhà cung cấp bản đồ)")
public class GeoController {
    public record GeoStatus(String provider, boolean enabled) { }

    private static final Logger log = LoggerFactory.getLogger(GeoController.class);
    private final GeocodingProvider geocoder;
    private final StringRedisTemplate redis;
    private final GeoProperties properties;

    public GeoController(GeocodingProvider geocoder, StringRedisTemplate redis, GeoProperties properties) {
        this.geocoder = geocoder;
        this.redis = redis;
        this.properties = properties;
    }

    @GetMapping("/status")
    @Operation(summary = "Nhà cung cấp bản đồ có khả dụng không")
    public ApiResponse<GeoStatus> status(HttpServletRequest request) {
        return ApiResponse.success("Request completed successfully", new GeoStatus(geocoder.name(), geocoder.enabled()), request.getRequestURI());
    }

    @GetMapping("/search")
    @Operation(summary = "Gợi ý địa chỉ kèm tọa độ", description = "503 GEOCODING_DISABLED khi chưa cấu hình, 503 GEOCODING_UNAVAILABLE khi nhà cung cấp lỗi")
    public ApiResponse<List<GeoCandidate>> search(@AuthenticationPrincipal AuthenticatedUser me, @RequestParam("q") String query,
                                                  @RequestParam(defaultValue = "5") int limit, HttpServletRequest request) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 3 || q.length() > 300) throw new IllegalArgumentException("Nhập 3–300 ký tự để tìm địa chỉ");
        if (!geocoder.enabled())
            throw new BusinessRuleException(HttpStatus.SERVICE_UNAVAILABLE, "GEOCODING_DISABLED", "Chưa cấu hình dịch vụ bản đồ, vui lòng nhập địa chỉ thủ công");
        rateLimit(me);
        try {
            return ApiResponse.success("Request completed successfully", geocoder.search(q, Math.min(Math.max(limit, 1), properties.maxResults())), request.getRequestURI());
        } catch (GeocodingUnavailableException ex) {
            throw new BusinessRuleException(HttpStatus.SERVICE_UNAVAILABLE, "GEOCODING_UNAVAILABLE", ex.getMessage() + ". Bạn vẫn có thể nhập địa chỉ thủ công");
        }
    }

    /** Fixed one-minute window per user in Redis; if Redis is down the search is allowed (the provider has its own quota). */
    private void rateLimit(AuthenticatedUser me) {
        String key = "geo:rate:" + me.id() + ":" + (System.currentTimeMillis() / 60_000);
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) redis.expire(key, Duration.ofSeconds(70));
            if (count != null && count > properties.searchesPerMinute()) throw new TooManyRequestsException("Bạn tìm địa chỉ quá nhanh, vui lòng thử lại sau ít giây", 60);
        } catch (TooManyRequestsException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Geocoding rate limit unavailable: {}", ex.getClass().getSimpleName());
        }
    }
}
