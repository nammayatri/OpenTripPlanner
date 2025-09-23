package org.opentripplanner.routing.algorithm.raptoradapter.router.street;

import java.time.Duration;
import java.util.Objects;
import org.locationtech.jts.geom.Coordinate;

/**
 * Cache key for access/egress search results. This key uniquely identifies
 * a street search request based on origin, mode, and search parameters.
 */
public class AccessEgressCacheKey {

  private final Coordinate origin;
  private final Duration maxDuration;
  private final int maxStopCount;
  private final boolean isEgress;
  private final int hashCode;

  public AccessEgressCacheKey(
    Coordinate origin,
    Duration maxDuration,
    int maxStopCount,
    boolean isEgress
  ) {
    this.origin = new Coordinate(origin); // defensive copy
    this.maxDuration = maxDuration;
    this.maxStopCount = maxStopCount;
    this.isEgress = isEgress;
    this.hashCode = computeHashCode();
  }

  private int computeHashCode() {
    return Objects.hash(
      // Round coordinates to ~100 meter precision to allow for cache hits
      // on nearby but not identical coordinates
      Math.round(origin.x * 1000.0) / 1000.0,
      Math.round(origin.y * 1000.0) / 1000.0,
      maxDuration,
      isEgress
      // Removed maxStopCount, walkSpeed, walkReluctance for more lenient caching
    );
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;
    
    AccessEgressCacheKey that = (AccessEgressCacheKey) obj;
    
    // Use rounded coordinates for comparison to allow cache hits for nearby points
    double thisX = Math.round(this.origin.x * 1000.0) / 1000.0;
    double thisY = Math.round(this.origin.y * 1000.0) / 1000.0;
    double thatX = Math.round(that.origin.x * 1000.0) / 1000.0;
    double thatY = Math.round(that.origin.y * 1000.0) / 1000.0;
    
    return Double.compare(thisX, thatX) == 0 &&
           Double.compare(thisY, thatY) == 0 &&
           Objects.equals(maxDuration, that.maxDuration) &&
           isEgress == that.isEgress;
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public String toString() {
    return String.format(
      "AccessEgressCacheKey{origin=(%.4f,%.4f), maxDuration=%s, maxStops=%d, egress=%s}",
      origin.x, origin.y, maxDuration, maxStopCount, isEgress
    );
  }

  public Coordinate getOrigin() {
    return new Coordinate(origin); // defensive copy
  }

  public Duration getMaxDuration() {
    return maxDuration;
  }

  public int getMaxStopCount() {
    return maxStopCount;
  }

  public boolean isEgress() {
    return isEgress;
  }
}
