package org.opentripplanner.routing.algorithm.raptoradapter.router.street;

import java.time.Duration;
import java.util.Objects;
import org.locationtech.jts.geom.Coordinate;
import org.opentripplanner.routing.api.request.StreetMode;
import org.opentripplanner.routing.api.request.preference.WalkPreferences;

/**
 * Cache key for access/egress search results. This key uniquely identifies
 * a street search request based on origin, mode, and search parameters.
 */
public class AccessEgressCacheKey {

  private final Coordinate origin;
  private final StreetMode mode;
  private final Duration maxDuration;
  private final int maxStopCount;
  private final boolean isEgress;
  private final double walkSpeed;
  private final double walkReluctance;
  private final int hashCode;

  public AccessEgressCacheKey(
    Coordinate origin,
    StreetMode mode,
    Duration maxDuration,
    int maxStopCount,
    boolean isEgress,
    WalkPreferences walkPreferences
  ) {
    this.origin = new Coordinate(origin); // defensive copy
    this.mode = mode;
    this.maxDuration = maxDuration;
    this.maxStopCount = maxStopCount;
    this.isEgress = isEgress;
    this.walkSpeed = walkPreferences.speed();
    this.walkReluctance = walkPreferences.reluctance();
    this.hashCode = computeHashCode();
  }

  private int computeHashCode() {
    return Objects.hash(
      // Round coordinates to ~100 meter precision to allow for cache hits
      // on nearby but not identical coordinates
      Math.round(origin.x * 1000.0) / 1000.0,
      Math.round(origin.y * 1000.0) / 1000.0,
      mode,
      maxDuration,
      maxStopCount,
      isEgress,
      Math.round(walkSpeed * 100.0) / 100.0,
      Math.round(walkReluctance * 100.0) / 100.0
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
           mode == that.mode &&
           Objects.equals(maxDuration, that.maxDuration) &&
           maxStopCount == that.maxStopCount &&
           isEgress == that.isEgress &&
           Double.compare(Math.round(walkSpeed * 100.0) / 100.0, 
                         Math.round(that.walkSpeed * 100.0) / 100.0) == 0 &&
           Double.compare(Math.round(walkReluctance * 100.0) / 100.0,
                         Math.round(that.walkReluctance * 100.0) / 100.0) == 0;
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public String toString() {
    return String.format(
      "AccessEgressCacheKey{origin=(%.4f,%.4f), mode=%s, maxDuration=%s, maxStops=%d, egress=%s}",
      origin.x, origin.y, mode, maxDuration, maxStopCount, isEgress
    );
  }

  public Coordinate getOrigin() {
    return new Coordinate(origin); // defensive copy
  }

  public StreetMode getMode() {
    return mode;
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
