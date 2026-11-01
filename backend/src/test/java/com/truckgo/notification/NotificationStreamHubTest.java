package com.truckgo.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.truckgo.notification.NotificationService.NotificationResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two hubs on one real Redis behave like two backend instances: a notification stored on instance A reaches the
 * recipient's stream held by instance B, and nobody else's.
 */
@Testcontainers(disabledWithoutDocker = true)
class NotificationStreamHubTest {
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /** Hub whose streams record the events written to them. */
    static class RecordingHub extends NotificationStreamHub {
        final Map<UUID, List<String>> received = new ConcurrentHashMap<>();
        private final ThreadLocal<UUID> opening = new ThreadLocal<>();

        RecordingHub(StringRedisTemplate redis, ObjectMapper json) { super(redis, json, new NotificationProperties(Duration.ofMinutes(1), Duration.ofSeconds(5), 50)); }

        SseEmitter openFor(UUID userId, List<NotificationResponse> missed) {
            opening.set(userId);
            try { return open(userId, missed); } finally { opening.remove(); }
        }

        @Override protected SseEmitter newEmitter(long timeoutMillis) {
            List<String> sink = received.computeIfAbsent(opening.get(), k -> new CopyOnWriteArrayList<>());
            return new SseEmitter(timeoutMillis) {
                @Override public void send(SseEventBuilder builder) {
                    StringBuilder text = new StringBuilder();
                    builder.build().forEach(part -> text.append(part.getData()));
                    sink.add(text.toString());
                }
            };
        }
    }

    final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    LettuceConnectionFactory connections;
    RedisMessageListenerContainer containerA;
    RedisMessageListenerContainer containerB;
    RecordingHub hubA;
    RecordingHub hubB;

    @BeforeEach void setUp() {
        connections = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        connections.afterPropertiesSet();
        connections.start();
        StringRedisTemplate template = new StringRedisTemplate(connections);
        hubA = new RecordingHub(template, json);
        hubB = new RecordingHub(template, json);
        containerA = listen(hubA);
        containerB = listen(hubB);
    }

    @AfterEach void tearDown() throws Exception {
        containerA.stop();
        containerB.stop();
        containerA.destroy();
        containerB.destroy();
        connections.destroy();
    }

    private RedisMessageListenerContainer listen(NotificationStreamHub hub) {
        RedisMessageListenerContainer c = new RedisMessageListenerContainer();
        c.setConnectionFactory(connections);
        c.addMessageListener(hub, new ChannelTopic(NotificationStreamHub.CHANNEL));
        c.afterPropertiesSet();
        c.start();
        return c;
    }

    /** Retries the block (an assertion) for up to 10 seconds. */
    private static void eventually(Runnable block) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            try { block.run(); return; }
            catch (AssertionError | NullPointerException e) {
                if (System.nanoTime() > deadline) throw e;
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }

    private static NotificationResponse notification(String title) {
        return new NotificationResponse(UUID.randomUUID(), NotificationType.APPLICATION_ACCEPTED, title, title, "APPLICATION", UUID.randomUUID(), false, null, Instant.now());
    }

    @Test void notificationStoredOnOneInstanceReachesTheRecipientOnAnotherOnly() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        hubB.openFor(alice, List.of());
        hubB.openFor(bob, List.of());
        // Subscriptions are asynchronous; publish until instance B has received the first message.
        eventually(() -> {
            hubA.publishAfterCommit(alice, notification("Bạn đã được chọn"));
            assertThat(hubB.received.get(alice)).anyMatch(e -> e.contains("Bạn đã được chọn"));
        });
        assertThat(hubB.received.get(bob)).noneMatch(e -> e.contains("Bạn đã được chọn"));   // private: not broadcast
        assertThat(hubA.received).doesNotContainKey(alice);                                     // A holds no stream for alice
    }

    @Test void reconnectingStreamFirstReceivesWhatItMissedThenLiveEvents() {
        UUID user = UUID.randomUUID();
        hubA.openFor(user, List.of(notification("Bị lỡ 1"), notification("Bị lỡ 2")));
        List<String> events = hubA.received.get(user);
        assertThat(events.get(0)).contains("replayed");
        assertThat(events.subList(1, 3)).satisfiesExactly(e -> assertThat(e).contains("Bị lỡ 1"), e -> assertThat(e).contains("Bị lỡ 2"));
        eventually(() -> {
            hubB.publishAfterCommit(user, notification("Mới"));
            assertThat(hubA.received.get(user)).anyMatch(e -> e.contains("Mới"));
        });
    }

    @Test void withoutRedisTheLocalStreamsStillReceive() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        dead.afterPropertiesSet();
        dead.start();
        RecordingHub offline = new RecordingHub(new StringRedisTemplate(dead), json);
        UUID user = UUID.randomUUID();
        offline.openFor(user, List.of());
        offline.publishAfterCommit(user, notification("Không có Redis"));
        assertThat(offline.received.get(user)).anyMatch(e -> e.contains("Không có Redis"));
        assertThat(Set.copyOf(offline.received.keySet())).containsExactly(user);
        dead.destroy();
    }
}
