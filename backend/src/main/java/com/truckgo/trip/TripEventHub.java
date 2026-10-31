package com.truckgo.trip;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truckgo.shipment.Shipment;
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
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Server-sent events for one order's trip ("status" and "location"). Events are published to Redis Pub/Sub after the
 * transaction commits, so every backend instance forwards them to the streams it holds; if Redis is unavailable they
 * are delivered to this instance's streams only. Authorisation happens when a stream is opened (see the trip
 * controllers): an event is only ever written to streams opened for that order, never broadcast.
 */
@Component
public class TripEventHub implements MessageListener {
    static final String CHANNEL = "truckgo:trip-events";
    private static final Logger log = LoggerFactory.getLogger(TripEventHub.class);

    private final Map<UUID, Set<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final TrackingProperties properties;

    public TripEventHub(StringRedisTemplate redis, ObjectMapper json, TrackingProperties properties) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
    }

    /** Opens a stream; the caller has already checked the viewer may see this order. */
    public SseEmitter open(UUID shipmentId, Object initialTracking, Object initialStatus) {
        SseEmitter emitter = new SseEmitter(properties.streamTimeout().toMillis());
        Set<SseEmitter> set = streams.computeIfAbsent(shipmentId, id -> new CopyOnWriteArraySet<>());
        set.add(emitter);
        Runnable remove = () -> { set.remove(emitter); streams.computeIfPresent(shipmentId, (id, s) -> s.isEmpty() ? null : s); };
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> { remove.run(); emitter.complete(); });
        emitter.onError(e -> remove.run());
        send(emitter, "status", initialStatus);
        send(emitter, "location", initialTracking);
        return emitter;
    }

    public void publishStatus(Shipment shipment) {
        publish(shipment.getId(), "status", Map.of("status", shipment.getStatus().name(), "at", Instant.now().toString()));
    }

    public void publishLocation(UUID shipmentId, Object tracking) { publish(shipmentId, "location", tracking); }

    void publish(UUID shipmentId, String type, Object data) {
        Runnable send = () -> {
            try {
                redis.convertAndSend(CHANNEL, json.writeValueAsString(Map.of("shipmentId", shipmentId.toString(), "type", type, "data", data)));
            } catch (Exception ex) {
                log.warn("Trip event via Redis failed, delivering locally only: {}", ex.getClass().getSimpleName());
                deliver(shipmentId, type, json.valueToTree(data));
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode node = json.readTree(message.getBody());
            deliver(UUID.fromString(node.get("shipmentId").asText()), node.get("type").asText(), node.get("data"));
        } catch (Exception ex) {
            log.warn("Ignoring malformed trip event: {}", ex.getClass().getSimpleName());
        }
    }

    void deliver(UUID shipmentId, String type, Object data) {
        Set<SseEmitter> set = streams.get(shipmentId);
        if (set != null) set.forEach(e -> send(e, type, data));
    }

    /** Keeps proxies from closing idle streams and detects dead clients. */
    @Scheduled(fixedDelayString = "${app.tracking.heartbeat:20s}", initialDelayString = "${app.tracking.heartbeat:20s}")
    public void heartbeat() {
        streams.values().forEach(set -> set.forEach(e -> {
            try { e.send(SseEmitter.event().comment("hb")); } catch (IOException | IllegalStateException ex) { set.remove(e); }
        }));
    }

    int openStreams(UUID shipmentId) { return streams.getOrDefault(shipmentId, Set.of()).size(); }

    private void send(SseEmitter emitter, String type, Object data) {
        try { emitter.send(SseEmitter.event().name(type).data(data)); }
        catch (IOException | IllegalStateException ex) { emitter.completeWithError(ex); }
    }
}
