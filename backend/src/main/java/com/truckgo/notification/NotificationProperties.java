package com.truckgo.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** app.notifications.*: lifetime of a realtime stream, heartbeat interval, how many missed notifications are replayed on reconnect. */
@ConfigurationProperties("app.notifications")
public record NotificationProperties(Duration streamTimeout, Duration heartbeat, int replayLimit) {
    public NotificationProperties {
        if (streamTimeout == null) streamTimeout = Duration.ofMinutes(10);
        if (heartbeat == null) heartbeat = Duration.ofSeconds(25);
        if (replayLimit <= 0) replayLimit = 100;
    }
}
