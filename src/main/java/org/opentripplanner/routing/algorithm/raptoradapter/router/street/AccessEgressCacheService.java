package org.opentripplanner.routing.algorithm.raptoradapter.router.street;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheStats;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.opentripplanner.routing.graphfinder.NearbyStop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for caching access/egress search results to improve performance
 * by avoiding repeated expensive street searches for the same origins.
 */
public class AccessEgressCacheService {

  private static final Logger LOG = LoggerFactory.getLogger(AccessEgressCacheService.class);

  private final Cache<AccessEgressCacheKey, List<NearbyStop>> cache;
  private final boolean enabled;

  /**
   * Creates a new cache service with default configuration.
   */
  public AccessEgressCacheService() {
    this(true, 10000, Duration.ofHours(1));
  }

  /**
   * Creates a new cache service with custom configuration.
   *
   * @param enabled whether caching is enabled
   * @param maxSize maximum number of entries in the cache
   * @param expireAfter duration after which entries expire
   */
  public AccessEgressCacheService(boolean enabled, int maxSize, Duration expireAfter) {
    this.enabled = enabled;
    
    if (enabled) {
      this.cache = CacheBuilder.newBuilder()
        .maximumSize(maxSize)
        .expireAfterWrite(expireAfter.toMinutes(), TimeUnit.MINUTES)
        .recordStats()
        .build();
      
      LOG.info(
        "AccessEgressCacheService initialized: enabled={}, maxSize={}, expireAfter={}",
        enabled, maxSize, expireAfter
      );
    } else {
      this.cache = null;
      LOG.info("AccessEgressCacheService disabled");
    }
  }

  /**
   * Gets cached nearby stops for the given key, or null if not cached.
   *
   * @param key the cache key
   * @return cached nearby stops or null if not found
   */
  public List<NearbyStop> get(AccessEgressCacheKey key) {
    if (!enabled || cache == null) {
      return null;
    }

    List<NearbyStop> result = cache.getIfPresent(key);
    
    if (result != null) {
      LOG.debug("Cache HIT for key: {}", key);
    } else {
      LOG.debug("Cache MISS for key: {}", key);
    }
    
    return result;
  }

  /**
   * Puts nearby stops in the cache for the given key.
   *
   * @param key the cache key
   * @param nearbyStops the nearby stops to cache
   */
  public void put(AccessEgressCacheKey key, List<NearbyStop> nearbyStops) {
    if (!enabled || cache == null) {
      return;
    }

    // Only cache non-empty results to avoid caching failed searches
    if (nearbyStops != null && !nearbyStops.isEmpty()) {
      cache.put(key, nearbyStops);
      LOG.debug("Cached {} nearby stops for key: {}", nearbyStops.size(), key);
    }
  }

  /**
   * Clears all entries from the cache.
   */
  public void clear() {
    if (enabled && cache != null) {
      cache.invalidateAll();
      LOG.info("AccessEgressCache cleared");
    }
  }

  /**
   * Gets cache statistics.
   *
   * @return cache statistics or null if caching is disabled
   */
  public CacheStats getStats() {
    if (!enabled || cache == null) {
      return null;
    }
    return cache.stats();
  }

  /**
   * Gets the current cache size.
   *
   * @return number of entries in the cache
   */
  public long size() {
    if (!enabled || cache == null) {
      return 0;
    }
    return cache.size();
  }

  /**
   * Checks if caching is enabled.
   *
   * @return true if caching is enabled
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Logs cache statistics at INFO level.
   */
  public void logStats() {
    if (!enabled || cache == null) {
      LOG.info("AccessEgressCache is disabled");
      return;
    }

    CacheStats stats = cache.stats();
    LOG.info(
      "AccessEgressCache stats: size={}, hitRate={:.2f}%, requests={}, hits={}, misses={}, evictions={}",
      cache.size(),
      stats.hitRate() * 100,
      stats.requestCount(),
      stats.hitCount(),
      stats.missCount(),
      stats.evictionCount()
    );
  }

  /**
   * Estimates the memory usage of the cache in bytes.
   * This is a rough estimate based on the number of entries.
   *
   * @return estimated memory usage in bytes
   */
  public long estimateMemoryUsage() {
    if (!enabled || cache == null) {
      return 0;
    }

    // Rough estimate: each cache entry (key + value) is approximately 1-5KB
    // depending on the number of nearby stops and path complexity
    long entries = cache.size();
    return entries * 2048; // 2KB average per entry
  }
}
