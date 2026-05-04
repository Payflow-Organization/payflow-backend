package com.payflow.infrastructure.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoggingCacheErrorHandlerTest {

    @Mock private Cache cache;

    private SimpleMeterRegistry meterRegistry;
    private LoggingCacheErrorHandler handler;

    private static final String CACHE_NAME = "wallets";

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        handler = new LoggingCacheErrorHandler(meterRegistry);
        when(cache.getName()).thenReturn(CACHE_NAME);
    }

    @Test
    void shouldIncrementGetCounterWhenCacheGetFails() {
        handler.handleCacheGetError(new RuntimeException("redis down"), cache, "key:1");

        assertThat(counterValue("get")).isEqualTo(1.0);
    }

    @Test
    void shouldIncrementPutCounterWhenCachePutFails() {
        handler.handleCachePutError(new RuntimeException("redis down"), cache, "key:1", "value");

        assertThat(counterValue("put")).isEqualTo(1.0);
    }

    @Test
    void shouldIncrementEvictCounterWhenCacheEvictFails() {
        handler.handleCacheEvictError(new RuntimeException("redis down"), cache, "key:1");

        assertThat(counterValue("evict")).isEqualTo(1.0);
    }

    @Test
    void shouldIncrementClearCounterWhenCacheClearFails() {
        handler.handleCacheClearError(new RuntimeException("redis down"), cache);

        assertThat(counterValue("clear")).isEqualTo(1.0);
    }

    @Test
    void shouldTagCounterWithCacheNameOnGet() {
        handler.handleCacheGetError(new RuntimeException(), cache, "key:1");

        Counter counter = meterRegistry.find("payflow.cache.error")
                .tag("operation", "get")
                .tag("cache", CACHE_NAME)
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void shouldAccumulateCountAcrossMultipleFailures() {
        RuntimeException ex = new RuntimeException("redis down");

        handler.handleCacheGetError(ex, cache, "key:1");
        handler.handleCacheGetError(ex, cache, "key:2");
        handler.handleCacheGetError(ex, cache, "key:3");

        assertThat(counterValue("get")).isEqualTo(3.0);
    }

    @Test
    void shouldTrackOperationsIndependently() {
        RuntimeException ex = new RuntimeException();

        handler.handleCacheGetError(ex, cache, "key:1");
        handler.handleCachePutError(ex, cache, "key:1", "val");
        handler.handleCacheEvictError(ex, cache, "key:1");
        handler.handleCacheClearError(ex, cache);

        assertThat(counterValue("get")).isEqualTo(1.0);
        assertThat(counterValue("put")).isEqualTo(1.0);
        assertThat(counterValue("evict")).isEqualTo(1.0);
        assertThat(counterValue("clear")).isEqualTo(1.0);
    }

    private double counterValue(String operation) {
        Counter counter = meterRegistry.find("payflow.cache.error")
                .tag("operation", operation)
                .tag("cache", CACHE_NAME)
                .counter();
        assertThat(counter).as("counter for operation=%s not found", operation).isNotNull();
        return counter.count();
    }
}
