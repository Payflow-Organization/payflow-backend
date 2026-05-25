package com.payflow.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;

import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoggingCacheErrorHandlerTest {

    @Mock private Cache cache;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private LoggingCacheErrorHandler handler;

    @BeforeEach
    void setUp() {
        handler = new LoggingCacheErrorHandler(meterRegistry);
        when(cache.getName()).thenReturn("wallets");
    }

    static Stream<Arguments> cacheOperations() {
        RuntimeException e = new RuntimeException("redis down");
        return Stream.of(
            op((h, c) -> h.handleCacheGetError(e, c, "key"),        "get"),
            op((h, c) -> h.handleCachePutError(e, c, "key", "val"), "put"),
            op((h, c) -> h.handleCacheEvictError(e, c, "key"),      "evict"),
            op((h, c) -> h.handleCacheClearError(e, c),             "clear")
        );
    }

    private static Arguments op(BiConsumer<LoggingCacheErrorHandler, Cache> operation, String tag) {
        return arguments(operation, tag);
    }

    @ParameterizedTest
    @MethodSource("cacheOperations")
    void redisFailureDoesNotPropagateToCallers(BiConsumer<LoggingCacheErrorHandler, Cache> operation, String ignored) {
        assertThatNoException().isThrownBy(() -> operation.accept(handler, cache));
    }

    @ParameterizedTest
    @MethodSource("cacheOperations")
    void redisFailureIsVisibleViaMetrics(BiConsumer<LoggingCacheErrorHandler, Cache> operation, String expectedOperation) {
        operation.accept(handler, cache);

        assertThat(meterRegistry.counter("payflow.cache.error", "operation", expectedOperation, "cache", "wallets").count()).isEqualTo(1);
    }
}
