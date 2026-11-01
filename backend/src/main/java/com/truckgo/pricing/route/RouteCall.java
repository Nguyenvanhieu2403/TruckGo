package com.truckgo.pricing.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/**
 * One HTTP GET to a route provider with a small retry for transient failures (timeouts, connection errors, 5xx).
 * Input errors (other 4xx, provider "no route"/"invalid" answers) are returned once, never retried; HTTP 429 is
 * reported as quota so the caller does not hammer a provider that is already refusing. URLs are never logged (they
 * carry API keys).
 */
public final class RouteCall {
    private static final Logger log = LoggerFactory.getLogger(RouteCall.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Failure { TRANSIENT, QUOTA }

    /** The provider's answer: HTTP status and parsed body (may be null when the body is not JSON). */
    public record Response(int status, JsonNode body) { }

    public static final class FailedException extends RuntimeException {
        private final Failure failure;
        FailedException(Failure failure, String message) { super(message); this.failure = failure; }
        public Failure failure() { return failure; }
    }

    private final RestClient http;
    private final int maxAttempts;
    private final Duration backoff;

    public RouteCall(RestClient http, int maxAttempts, Duration backoff) {
        this.http = http;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoff = backoff;
    }

    public Response get(URI uri, String provider) {
        for (int attempt = 1; ; attempt++) {
            Response response;
            try {
                response = http.get().uri(uri).exchange((request, res) -> {
                    JsonNode body;
                    try (InputStream in = res.getBody()) { body = JSON.readTree(in); } catch (Exception ex) { body = null; }
                    return new Response(res.getStatusCode().value(), body);
                });
            } catch (RuntimeException ex) {
                log.warn("Route provider {} call failed (attempt {}/{}): {}", provider, attempt, maxAttempts, ex.getClass().getSimpleName());
                if (attempt >= maxAttempts) throw new FailedException(Failure.TRANSIENT, ex.getClass().getSimpleName());
                pause(attempt);
                continue;
            }
            if (response.status() == 429) {
                log.warn("Route provider {} refused the request: quota or rate limit (HTTP 429)", provider);
                throw new FailedException(Failure.QUOTA, "HTTP 429");
            }
            if (response.status() >= 500) {
                log.warn("Route provider {} returned HTTP {} (attempt {}/{})", provider, response.status(), attempt, maxAttempts);
                if (attempt >= maxAttempts) throw new FailedException(Failure.TRANSIENT, "HTTP " + response.status());
                pause(attempt);
                continue;
            }
            return response;
        }
    }

    private void pause(int attempt) {
        try { Thread.sleep(backoff.toMillis() * attempt); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    /** The answer to give when the call could not be completed. */
    static RouteDistanceResult unavailable(String provider, FailedException ex) {
        return ex.failure() == Failure.QUOTA
                ? RouteDistanceResult.unavailable(provider, "PROVIDER_QUOTA", "Dịch vụ bản đồ đã hết hạn mức truy vấn; quản trị viên sẽ xác nhận quãng đường")
                : RouteDistanceResult.unavailable(provider, "PROVIDER_ERROR", "Không kết nối được dịch vụ bản đồ; quản trị viên sẽ xác nhận quãng đường");
    }
}
