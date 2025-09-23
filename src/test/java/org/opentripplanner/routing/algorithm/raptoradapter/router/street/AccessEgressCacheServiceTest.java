package org.opentripplanner.routing.algorithm.raptoradapter.router.street;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.opentripplanner.routing.api.request.StreetMode;
import org.opentripplanner.routing.api.request.preference.WalkPreferences;
import org.opentripplanner.routing.graphfinder.NearbyStop;
import org.opentripplanner.street.search.request.StreetSearchRequest;
import org.opentripplanner.street.search.state.State;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.site.StopLocation;

class AccessEgressCacheServiceTest {

  private AccessEgressCacheService cacheService;
  private AccessEgressCacheKey testKey;
  private List<NearbyStop> testNearbyStops;

  @BeforeEach
  void setUp() {
    cacheService = new AccessEgressCacheService(true, 100, Duration.ofMinutes(30));
    
    testKey = new AccessEgressCacheKey(
      new Coordinate(12.345, 67.890),
      Duration.ofMinutes(15),
      10,
      false
    );

    // Create mock nearby stops for testing
    testNearbyStops = createMockNearbyStops();
  }

  @Test
  void testCacheEnabled() {
    assertTrue(cacheService.isEnabled());
    assertEquals(0, cacheService.size());
  }

  @Test
  void testCacheDisabled() {
    AccessEgressCacheService disabledCache = new AccessEgressCacheService(false, 100, Duration.ofMinutes(30));
    assertFalse(disabledCache.isEnabled());
    assertEquals(0, disabledCache.size());
    
    // Operations on disabled cache should be no-ops
    disabledCache.put(testKey, testNearbyStops);
    assertNull(disabledCache.get(testKey));
  }

  @Test
  void testPutAndGet() {
    // Initially cache should be empty
    assertNull(cacheService.get(testKey));
    assertEquals(0, cacheService.size());

    // Put data in cache
    cacheService.put(testKey, testNearbyStops);
    assertEquals(1, cacheService.size());

    // Retrieve data from cache
    List<NearbyStop> cachedResult = cacheService.get(testKey);
    assertNotNull(cachedResult);
    assertEquals(testNearbyStops.size(), cachedResult.size());
  }

  @Test
  void testCacheMiss() {
    AccessEgressCacheKey differentKey = new AccessEgressCacheKey(
      new Coordinate(99.999, 88.888),
      Duration.ofMinutes(20),
      5,
      true
    );

    cacheService.put(testKey, testNearbyStops);
    
    // Different key should result in cache miss
    assertNull(cacheService.get(differentKey));
  }

  @Test
  void testCacheKeyEquality() {
    AccessEgressCacheKey sameKey = new AccessEgressCacheKey(
      new Coordinate(12.345, 67.890),
      Duration.ofMinutes(15),
      10,
      false
    );

    // Keys with same parameters should be equal
    assertEquals(testKey, sameKey);
    assertEquals(testKey.hashCode(), sameKey.hashCode());

    cacheService.put(testKey, testNearbyStops);
    
    // Should be able to retrieve using equivalent key
    List<NearbyStop> cachedResult = cacheService.get(sameKey);
    assertNotNull(cachedResult);
  }

  @Test
  void testCacheKeyCoordinateRounding() {
    // Test that nearby coordinates (within ~10m) are treated as the same
    AccessEgressCacheKey nearbyKey = new AccessEgressCacheKey(
      new Coordinate(12.3451, 67.8901), // Slightly different coordinates
      Duration.ofMinutes(15),
      10,
      false
    );

    cacheService.put(testKey, testNearbyStops);
    
    // Should get cache hit for nearby coordinates
    List<NearbyStop> cachedResult = cacheService.get(nearbyKey);
    assertNotNull(cachedResult);
  }

  @Test
  void testClearCache() {
    cacheService.put(testKey, testNearbyStops);
    assertEquals(1, cacheService.size());

    cacheService.clear();
    assertEquals(0, cacheService.size());
    assertNull(cacheService.get(testKey));
  }

  @Test
  void testEmptyListNotCached() {
    List<NearbyStop> emptyList = Collections.emptyList();
    
    cacheService.put(testKey, emptyList);
    assertEquals(0, cacheService.size()); // Empty lists should not be cached
    
    cacheService.put(testKey, null);
    assertEquals(0, cacheService.size()); // Null should not be cached
  }

  @Test
  void testCacheStats() {
    assertNotNull(cacheService.getStats());
    
    // Initially no requests
    assertEquals(0, cacheService.getStats().requestCount());
    
    // Cache miss
    cacheService.get(testKey);
    assertEquals(1, cacheService.getStats().requestCount());
    assertEquals(1, cacheService.getStats().missCount());
    
    // Cache put and hit
    cacheService.put(testKey, testNearbyStops);
    cacheService.get(testKey);
    assertEquals(2, cacheService.getStats().requestCount());
    assertEquals(1, cacheService.getStats().hitCount());
  }

  private List<NearbyStop> createMockNearbyStops() {
    List<NearbyStop> nearbyStops = new ArrayList<>();
    
    // Create a mock stop with proper ID and index supplier
    var stopId = org.opentripplanner.transit.model.framework.FeedScopedId.ofNullable("TEST", "STOP1");
    StopLocation mockStop = RegularStop.of(stopId, () -> 1)
      .withName(org.opentripplanner.framework.i18n.I18NString.of("Test Stop"))
      .withCoordinate(12.345, 67.890)
      .build();
    
    // Create a mock state (simplified for testing)
    StreetSearchRequest mockRequest = StreetSearchRequest.of().build();
    State mockState = new State(null, mockRequest);
    
    nearbyStops.add(new NearbyStop(mockStop, 100.0, Collections.emptyList(), mockState));
    nearbyStops.add(new NearbyStop(mockStop, 200.0, Collections.emptyList(), mockState));
    
    return nearbyStops;
  }
}
