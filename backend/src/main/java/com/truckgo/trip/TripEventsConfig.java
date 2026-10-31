package com.truckgo.trip;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Subscribes this instance to trip events published by any instance. */
@Configuration
class TripEventsConfig {
    @Bean
    RedisMessageListenerContainer tripEventListener(RedisConnectionFactory connections, TripEventHub hub) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.addMessageListener(hub, new ChannelTopic(TripEventHub.CHANNEL));
        // A Redis outage must not stop the application from starting; streams then work per instance.
        container.setErrorHandler(ex -> org.slf4j.LoggerFactory.getLogger(TripEventsConfig.class).warn("Trip event subscription error: {}", ex.getClass().getSimpleName()));
        return container;
    }
}
