package com.payflow.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

@Slf4j
@RequiredArgsConstructor
public class LoggingCacheErrorHandler implements CacheErrorHandler {

    private final MeterRegistry meterRegistry;

    @Override
    public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
        log.warn("Cache get error cache={} key={}", cache.getName(), key, e);
        counter(cache, "get");
    }

    @Override
    public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
        log.warn("Cache put error cache={} key={}", cache.getName(), key, e);
        counter(cache, "put");
    }

    @Override
    public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
        log.warn("Cache evict error cache={} key={}", cache.getName(), key, e);
        counter(cache, "evict");
    }

    @Override
    public void handleCacheClearError(RuntimeException e, Cache cache) {
        log.warn("Cache clear error cache={}", cache.getName(), e);
        counter(cache, "clear");
    }

    private void counter(Cache cache, String operation) {
        meterRegistry.counter("payflow.cache.error", "operation", operation, "cache", cache.getName()).increment();
    }
}
