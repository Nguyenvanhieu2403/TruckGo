package com.truckgo.notification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Subscribes this instance to notifications stored by any instance. */
@Configuration
class NotificationEventsConfig {
    @Bean
    RedisMessageListenerContainer notificationListener(RedisConnectionFactory connections, NotificationStreamHub hub) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.addMessageListener(hub, new ChannelTopic(NotificationStreamHub.CHANNEL));
        // A Redis outage must not stop the application; streams then work per instance and clients poll.
        container.setErrorHandler(ex -> org.slf4j.LoggerFactory.getLogger(NotificationEventsConfig.class).warn("Notification subscription error: {}", ex.getClass().getSimpleName()));
        return container;
    }
}
