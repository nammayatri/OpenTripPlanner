package org.opentripplanner.routing.algorithm.raptoradapter.router.street;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheStats;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.opentripplanner.routing.graphfinder.NearbyStop;

/**
 * Service for caching access/egress search results to improve performance
 * by avoiding repeated expensive street searches for the same origins.
 */
public class AccessEgressCacheService {

  private final Cache<AccessEgressCacheKey, List<NearbyStop>> cache;
  private final boolean enabled;

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
    } else {
      this.cache = null;
    }
  }

  private static class Holder {
    private static final AccessEgressCacheService INSTANCE =
      new AccessEgressCacheService(true, 10000, Duration.ofHours(1));
  }

  // Global access
  public static AccessEgressCacheService getInstance() {
    return Holder.INSTANCE;
  }

  /**
   * Gets cached nearby stops for the given key, or null if not cached.
   * Measures and logs the time taken for cache retrieval.
   *
   * @param key the cache key
   * @return cached nearby stops or null if not found
   */
  public List<NearbyStop> get(AccessEgressCacheKey key) {
    if (!enabled || cache == null) {
      return null;
    }

    return cache.getIfPresent(key);
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
    }
  }

  /**
   * Puts nearby stops in the cache and logs the street search performance.
   *
   * @param key the cache key
   * @param nearbyStops the nearby stops to cache
   */
  public void putNonNullNearbyStops(AccessEgressCacheKey key, List<NearbyStop> nearbyStops) {
    if (!enabled || cache == null) {
      return;
    }
    // Only cache non-empty results to avoid caching failed searches
    if (nearbyStops != null && !nearbyStops.isEmpty()) {
      cache.put(key, nearbyStops);
    }
  }

  /**
   * Clears all entries from the cache.
   */
  public void clear() {
    if (enabled && cache != null) {
      cache.invalidateAll();
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
}
