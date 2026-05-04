package com.payflow.infrastructure.cache;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

@Slf4j
@RequiredArgsConstructor
public class LoggingCacheErrorHandler implements CacheErrorHandler {
    private final MeterRegistry meterRegistry;
    private static final String CACHE_ERROR = "payflow.cache.error";
    private static final String CACHE_TAG = "cache";
    private static final String OPERATION_TAG = "operation";
    @Override
    public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
        log.warn("Cache GET failed cache={} key={}", cache.getName(), key, e);
        meterRegistry.counter(CACHE_ERROR, OPERATION_TAG, "get", CACHE_TAG, cache.getName()).increment();
    }

    @Override
    public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
        log.warn("Cache PUT failed cache={} key={}", cache.getName(), key, e);
        meterRegistry.counter(CACHE_ERROR, OPERATION_TAG, "put", CACHE_TAG, cache.getName()).increment();
    }

    @Override
    public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
        log.warn("Cache EVICT failed cache={} key={}", cache.getName(), key, e);
        meterRegistry.counter(CACHE_ERROR, OPERATION_TAG, "evict", CACHE_TAG, cache.getName()).increment();
    }

    @Override
    public void handleCacheClearError(RuntimeException e, Cache cache) {
        log.warn("Cache CLEAR failed cache={}", cache.getName(), e);
        meterRegistry.counter(CACHE_ERROR, OPERATION_TAG, "clear", CACHE_TAG, cache.getName()).increment();
    }
}
