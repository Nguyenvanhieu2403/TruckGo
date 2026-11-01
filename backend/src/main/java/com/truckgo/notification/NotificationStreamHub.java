package com.truckgo.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truckgo.notification.NotificationService.NotificationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Per-user server-sent event streams of notifications. A stored notification is published to Redis Pub/Sub after its
 * transaction commits, so whichever instance holds the recipient's stream forwards it; each message names one
 * recipient and is written only to that user's streams (nothing is broadcast). When Redis is down the event reaches
 * this instance's streams only, and other clients get it from PostgreSQL when they reconnect or poll.
 */
@Component
public class NotificationStreamHub implements MessageListener {
    static final String CHANNEL = "truckgo:notifications";
    private static final Logger log = LoggerFactory.getLogger(NotificationStreamHub.class);

    private final Map<UUID, Set<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final NotificationProperties properties;

    public NotificationStreamHub(StringRedisTemplate redis, ObjectMapper json, NotificationProperties properties) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
    }

    /** Registers the stream first, then replays what the client missed, so nothing falls between the two (the client drops duplicates by id). */
    public SseEmitter open(UUID userId, List<NotificationResponse> missed) {
        SseEmitter emitter = newEmitter(properties.streamTimeout().toMillis());
        Set<SseEmitter> set = streams.computeIfAbsent(userId, id -> new CopyOnWriteArraySet<>());
        set.add(emitter);
        Runnable remove = () -> { set.remove(emitter); streams.computeIfPresent(userId, (id, s) -> s.isEmpty() ? null : s); };
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> { remove.run(); emitter.complete(); });
        emitter.onError(e -> remove.run());
        send(emitter, "ready", Map.of("replayed", missed.size()), null);
        missed.forEach(n -> send(emitter, "notification", n, n.id()));
        return emitter;
    }

    public void publishAfterCommit(UUID userId, NotificationResponse notification) {
        Runnable publish = () -> {
            try {
                redis.convertAndSend(CHANNEL, json.writeValueAsString(Map.of("userId", userId.toString(), "notification", notification)));
            } catch (Exception ex) {
                log.warn("Notification push via Redis failed, delivering locally only: {}", ex.getClass().getSimpleName());
                deliver(userId, json.valueToTree(notification));
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else {
            publish.run();
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode node = json.readTree(message.getBody());
            deliver(UUID.fromString(node.get("userId").asText()), node.get("notification"));
        } catch (Exception ex) {
            log.warn("Ignoring malformed notification event: {}", ex.getClass().getSimpleName());
        }
    }

    void deliver(UUID userId, JsonNode notification) {
        Set<SseEmitter> set = streams.get(userId);
        if (set == null) return;
        String id = notification.hasNonNull("id") ? notification.get("id").asText() : null;
        set.forEach(e -> send(e, "notification", notification, id == null ? null : UUID.fromString(id)));
    }

    @Scheduled(fixedDelayString = "${app.notifications.heartbeat:25s}", initialDelayString = "${app.notifications.heartbeat:25s}")
    public void heartbeat() {
        streams.values().forEach(set -> set.forEach(e -> {
            try { e.send(SseEmitter.event().comment("hb")); } catch (IOException | IllegalStateException ex) { set.remove(e); }
        }));
    }

    /** Overridable so a test can observe what a stream receives without an HTTP connection. */
    protected SseEmitter newEmitter(long timeoutMillis) { return new SseEmitter(timeoutMillis); }

    public int openStreams(UUID userId) { return streams.getOrDefault(userId, Set.of()).size(); }

    private void send(SseEmitter emitter, String type, Object data, UUID id) {
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event().name(type).data(data);
            if (id != null) event.id(id.toString());
            emitter.send(event);
        } catch (IOException | IllegalStateException ex) {
            emitter.completeWithError(ex);
        }
    }
}
