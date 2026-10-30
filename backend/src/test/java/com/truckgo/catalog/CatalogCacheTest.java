package com.truckgo.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truckgo.catalog.dto.CatalogDtos.ItemCategoryDto;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class CatalogCacheTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ObjectMapper json = new ObjectMapper();
    private final CatalogCache cache = new CatalogCache(redis, json, Duration.ofMinutes(5));
    private final TypeReference<List<ItemCategoryDto>> type = new TypeReference<>() { };
    private final List<ItemCategoryDto> fromDb = List.of(new ItemCategoryDto("FOOD", "Thực phẩm", null));

    @Test void missLoadsFromDatabaseAndStoresWithTtl() throws Exception {
        when(redis.opsForValue()).thenReturn(values);
        AtomicInteger loads = new AtomicInteger();
        assertThat(cache.get("item-categories", type, () -> { loads.incrementAndGet(); return fromDb; })).isEqualTo(fromDb);
        assertThat(loads).hasValue(1);
        verify(values).set(eq("catalog:v1:item-categories"), eq(json.writeValueAsString(fromDb)), eq(Duration.ofMinutes(5)));
    }

    @Test void hitDoesNotTouchDatabase() throws Exception {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("catalog:v1:item-categories")).thenReturn(json.writeValueAsString(fromDb));
        assertThat(cache.get("item-categories", type, () -> { throw new AssertionError("database should not be queried"); })).isEqualTo(fromDb);
    }

    @Test void redisOutageFallsBackToDatabase() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        when(redis.delete(anyCollection())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(cache.get("item-categories", type, () -> fromDb)).isEqualTo(fromDb);
        assertThatCode(cache::evictAll).doesNotThrowAnyException();
    }

    @Test void corruptEntryIsIgnored() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("{not json");
        assertThat(cache.get("item-categories", type, () -> fromDb)).isEqualTo(fromDb);
    }
}
