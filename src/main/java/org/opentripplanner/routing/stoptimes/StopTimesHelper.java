package org.opentripplanner.routing.stoptimes;

import static org.opentripplanner.routing.stoptimes.ArrivalDeparture.ARRIVALS;
import static org.opentripplanner.routing.stoptimes.ArrivalDeparture.DEPARTURES;

import com.google.common.collect.MinMaxPriorityQueue;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import org.opentripplanner.framework.time.ServiceDateUtils;
import org.opentripplanner.model.PickDrop;
import org.opentripplanner.model.StopTimesInPattern;
import org.opentripplanner.model.Timetable;
import org.opentripplanner.model.TripTimeOnDate;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.model.timetable.FrequencyEntry;
import org.opentripplanner.transit.model.timetable.TripTimes;
import org.opentripplanner.transit.service.TransitService;

public class StopTimesHelper {

  /**
   * Fetch upcoming vehicle departures from a stop. It goes though all patterns passing the stop for
   * the previous, current and next service date. It uses a priority queue to keep track of the next
   * departures. The queue is shared between all dates, as services from the previous service date
   * can visit the stop later than the current service date's services. This happens eg. with
   * sleeper trains.
   * <p>
   * @param stop                  Stop object to perform the search for
   * @param startTime             Start time for the search.
   * @param timeRange             Searches forward for timeRange from startTime
   * @param numberOfDepartures    Number of departures to fetch per pattern
   * @param arrivalDeparture      Filter by arrivals, departures, or both
   * @param includeCancelledTrips If true, cancelled trips will also be included in result
   */
  public static List<StopTimesInPattern> stopTimesForStop(
    TransitService transitService,
    StopLocation stop,
    Instant startTime,
    Duration timeRange,
    int numberOfDepartures,
    ArrivalDeparture arrivalDeparture,
    boolean includeCancelledTrips
  ) {
    if (numberOfDepartures <= 0) {
      return List.of();
    }

    List<StopTimesInPattern> result = new ArrayList<>();

    // Fetch all patterns, including those from realtime sources
    Collection<TripPattern> patterns = transitService.getPatternsForStop(stop, true);

    for (TripPattern pattern : patterns) {
      Queue<TripTimeOnDate> pq = listTripTimeShortsForPatternAtStop(
        transitService,
        stop,
        pattern,
        startTime,
        timeRange,
        numberOfDepartures,
        arrivalDeparture,
        includeCancelledTrips,
        false
      );

      result.addAll(getStopTimesInPattern(pattern, pq));
    }

    return result;
  }

  /**
   * Get a list of all trips that pass through a stop during a single ServiceDate. Useful when
   * creating complete stop timetables for a single day.
   *
   * @param stop        Stop object to perform the search for
   * @param serviceDate Return all departures for the specified date
   */
  public static List<StopTimesInPattern> stopTimesForStop(
    TransitService transitService,
    StopLocation stop,
    LocalDate serviceDate,
    ArrivalDeparture arrivalDeparture,
    boolean includeCancellations
  ) {
    List<StopTimesInPattern> ret = new ArrayList<>();

    var servicesRunning = transitService.getServiceCodesRunningForDate(serviceDate);
    Instant midnight = ServiceDateUtils
      .asStartOfService(serviceDate, transitService.getTimeZone())
      .toInstant();

    for (TripPattern pattern : transitService.getPatternsForStop(stop, true)) {
      StopTimesInPattern stopTimes = new StopTimesInPattern(pattern);
      Timetable tt = transitService.getTimetableForTripPattern(pattern, serviceDate);
      List<StopLocation> stops = pattern.getStops();
      for (int i = 0; i < stops.size(); i++) {
        StopLocation currStop = stops.get(i);
        if (currStop == stop) {
          if (skipByPickUpDropOff(pattern, arrivalDeparture, i)) {
            continue;
          }
          if (skipByStopCancellation(pattern, includeCancellations, i)) {
            continue;
          }
          for (TripTimes t : tt.getTripTimes()) {
            if (skipByTripCancellation(t, includeCancellations)) {
              continue;
            }
            if (servicesRunning.contains(t.getServiceCode())) {
              stopTimes.times.add(new TripTimeOnDate(t, i, pattern, serviceDate, midnight));
            }
          }
        }
      }
      ret.add(stopTimes);
    }
    return ret;
  }

  /**
   * Fetch upcoming vehicle departures from a stop for a single pattern, passing the stop for the
   * previous, current and next service date. It uses a priority queue to keep track of the next
   * departures. The queue is shared between all dates, as services from the previous service date
   * can visit the stop later than the current service date's services.
   * <p>
   * TODO: Add frequency based trips
   *
   * @param stop               Stop object to perform the search for
   * @param pattern            Pattern object to perform the search for
   * @param startTime          Start time for the search.
   * @param timeRange          Searches forward for timeRange from startTime
   * @param numberOfDepartures Number of departures to fetch per pattern
   * @param arrivalDeparture   Filter by arrivals, departures, or both.
   */
  public static List<TripTimeOnDate> stopTimesForPatternAtStop(
    TransitService transitService,
    StopLocation stop,
    TripPattern pattern,
    Instant startTime,
    Duration timeRange,
    int numberOfDepartures,
    ArrivalDeparture arrivalDeparture,
    boolean includeCancellations
  ) {
    Queue<TripTimeOnDate> pq = listTripTimeShortsForPatternAtStop(
      transitService,
      stop,
      pattern,
      startTime,
      timeRange,
      numberOfDepartures,
      arrivalDeparture,
      includeCancellations,
      true
    );

    return new ArrayList<>(pq);
  }

  private static List<StopTimesInPattern> getStopTimesInPattern(
    TripPattern pattern,
    Queue<TripTimeOnDate> pq
  ) {
    List<StopTimesInPattern> result = new ArrayList<>();
    if (!pq.isEmpty()) {
      StopTimesInPattern stopTimes = new StopTimesInPattern(pattern);
      while (!pq.isEmpty()) {
        stopTimes.times.add(0, pq.poll());
      }
      result.add(stopTimes);
    }
    return result;
  }

  private static Queue<TripTimeOnDate> listTripTimeShortsForPatternAtStop(
    TransitService transitService,
    StopLocation stop,
    TripPattern pattern,
    Instant startTime,
    Duration timeRange,
    int numberOfDepartures,
    ArrivalDeparture arrivalDeparture,
    boolean includeCancellations,
    boolean includeReplaced
  ) {
    ZoneId zoneId = transitService.getTimeZone();
    LocalDate startDate = startTime.atZone(zoneId).toLocalDate().minusDays(1);
    LocalDate endDate = startTime.plus(timeRange).atZone(zoneId).toLocalDate();

    // datesUntil is exclusive in the end, so need to add one day
    List<LocalDate> serviceDates = startDate.datesUntil(endDate.plusDays(1)).toList();

    // The bounded priority Q is used to keep a sorted short list of trip times. We can not
    // rely on the trip times to be in order because of real-time updates. This code can
    // probably be optimized, and the trip search in the Raptor search does almost the same
    // thing. This is no part of a routing request, but is a used frequently in some
    // operation like Entur for "departure boards" (apps, widgets, screens on platforms, and
    // hotel lobbies). Setting the numberOfDepartures and timeRange to a big number for a
    // transit hub could result in a DOS attack, but there are probably other more effective
    // ways to do it.
    //
    // The {@link MinMaxPriorityQueue} is marked beta, but we do not have a god alternative.
    MinMaxPriorityQueue<TripTimeOnDate> pq = MinMaxPriorityQueue
      .orderedBy(
        Comparator.comparing((TripTimeOnDate tts) ->
          tts.getServiceDayMidnight() + tts.getRealtimeDeparture()
        )
      )
      .maximumSize(numberOfDepartures)
      .create();

    int timeRangeSeconds = (int) timeRange.toSeconds();

    // Loop through all possible days
    for (LocalDate serviceDate : serviceDates) {
      Timetable timetable = transitService.getTimetableForTripPattern(pattern, serviceDate);
      ZonedDateTime midnight = ServiceDateUtils.asStartOfService(serviceDate, zoneId);
      int secondsSinceMidnight = ServiceDateUtils.secondsSinceStartOfService(
        midnight,
        ZonedDateTime.ofInstant(startTime, zoneId)
      );
      var servicesRunning = transitService.getServiceCodesRunningForDate(serviceDate);

      List<StopLocation> stops = pattern.getStops();
      for (int stopIndex = 0; stopIndex < stops.size(); stopIndex++) {
        StopLocation currStop = stops.get(stopIndex);
        if (currStop == stop) {
          if (skipByPickUpDropOff(pattern, arrivalDeparture, stopIndex)) {
            continue;
          }
          if (skipByStopCancellation(pattern, includeCancellations, stopIndex)) {
            continue;
          }

          for (TripTimes tripTimes : timetable.getTripTimes()) {
            if (!servicesRunning.contains(tripTimes.getServiceCode())) {
              continue;
            }
            if (skipByTripCancellation(tripTimes, includeCancellations)) {
              continue;
            }
            if (
              !includeReplaced &&
              isReplacedByAnotherPattern(tripTimes.getTrip(), serviceDate, pattern, transitService)
            ) {
              continue;
            }

            boolean departureTimeInRange =
              tripTimes.getDepartureTime(stopIndex) >= secondsSinceMidnight &&
              tripTimes.getDepartureTime(stopIndex) <= secondsSinceMidnight + timeRangeSeconds;

            boolean arrivalTimeInRange =
              tripTimes.getArrivalTime(stopIndex) >= secondsSinceMidnight &&
              tripTimes.getArrivalTime(stopIndex) <= secondsSinceMidnight + timeRangeSeconds;

            // ARRIVAL: Arrival time has to be within range
            // DEPARTURES: Departure time has to be within range
            // BOTH: Either arrival time or departure time has to be within range
            if (
              (arrivalDeparture != ARRIVALS && departureTimeInRange) ||
              (arrivalDeparture != DEPARTURES && arrivalTimeInRange)
            ) {
              pq.add(
                new TripTimeOnDate(tripTimes, stopIndex, pattern, serviceDate, midnight.toInstant())
              );
            }
          }

          // Frequency-based trips carry a window plus a headway instead of a
          // concrete schedule, so each departure has to be materialized here.
          //
          // Read the entries from the SCHEDULED timetable, not from `timetable`:
          // the per-date copy constructor (Timetable(Timetable, LocalDate))
          // copies tripTimes only, so a realtime snapshot has no frequency
          // entries at all. Since a frequency trip cannot receive a TripUpdate
          // (createUpdatedTripTimes resolves trips through tripTimes and fails
          // with TRIP_NOT_FOUND_IN_PATTERN), the scheduled entries are always
          // the live ones.
          //
          // Only exact_times=1 windows are expanded: exact_times=0 means the
          // operator publishes a headway and no departure times, so listing
          // times for it would invent a timetable that was never published.
          for (FrequencyEntry freq : pattern.getScheduledTimetable().getFrequencyEntries()) {
            if (!freq.exactTimes) {
              continue;
            }
            // A non-positive headway would never advance the loop below.
            if (freq.headway <= 0) {
              continue;
            }
            if (!servicesRunning.contains(freq.tripTimes.getServiceCode())) {
              continue;
            }
            if (skipByTripCancellation(freq.tripTimes, includeCancellations)) {
              continue;
            }
            if (
              !includeReplaced &&
              isReplacedByAnotherPattern(
                freq.tripTimes.getTrip(),
                serviceDate,
                pattern,
                transitService
              )
            ) {
              continue;
            }

            // Same convention as FrequencyEntry.nextDepartureTime: the window is
            // expressed at the first stop, so shift it to the queried stop by the
            // DEPARTURE offset, and treat its end as exclusive. Using the arrival
            // offset here would cut the window short by the dwell at this stop.
            int stopOffset =
              freq.tripTimes.getDepartureTime(stopIndex) - freq.tripTimes.getDepartureTime(0);
            int windowEndExclusive = freq.endTime + stopOffset;
            int rangeEndInclusive = secondsSinceMidnight + timeRangeSeconds;

            int departureTime = freq.nextDepartureTime(stopIndex, secondsSinceMidnight);
            if (departureTime == -1) {
              continue;
            }

            int emitted = 0;
            while (
              departureTime < windowEndExclusive &&
              departureTime <= rangeEndInclusive &&
              emitted < numberOfDepartures
            ) {
              // null for a trip carrying realtime updates; unreachable today
              TripTimes materialized = freq.materialize(stopIndex, departureTime, true);
              if (materialized != null) {
                boolean departureTimeInRange =
                  materialized.getDepartureTime(stopIndex) >= secondsSinceMidnight &&
                  materialized.getDepartureTime(stopIndex) <= rangeEndInclusive;

                boolean arrivalTimeInRange =
                  materialized.getArrivalTime(stopIndex) >= secondsSinceMidnight &&
                  materialized.getArrivalTime(stopIndex) <= rangeEndInclusive;

                if (
                  (arrivalDeparture != ARRIVALS && departureTimeInRange) ||
                  (arrivalDeparture != DEPARTURES && arrivalTimeInRange)
                ) {
                  pq.add(
                    new TripTimeOnDate(
                      materialized,
                      stopIndex,
                      pattern,
                      serviceDate,
                      midnight.toInstant()
                    )
                  );
                }
              }
              departureTime += freq.headway;
              emitted++;
            }
          }
        }
      }
    }
    return pq;
  }

  private static boolean isReplacedByAnotherPattern(
    Trip trip,
    LocalDate serviceDate,
    TripPattern pattern,
    TransitService transitService
  ) {
    final TripPattern replacement = transitService.getRealtimeAddedTripPattern(
      trip.getId(),
      serviceDate
    );
    return replacement != null && !replacement.equals(pattern);
  }

  public static boolean skipByTripCancellation(TripTimes tripTimes, boolean includeCancellations) {
    if (tripTimes.isDeleted()) {
      return true;
    }

    return (
      (tripTimes.isCanceled() || tripTimes.getTrip().getNetexAlteration().isCanceledOrReplaced()) &&
      !includeCancellations
    );
  }

  private static boolean skipByPickUpDropOff(
    TripPattern pattern,
    ArrivalDeparture arrivalDeparture,
    int stopIndex
  ) {
    boolean noPickup = pattern.getBoardType(stopIndex).is(PickDrop.NONE);
    boolean noDropoff = pattern.getAlightType(stopIndex).is(PickDrop.NONE);

    if (noPickup && noDropoff) {
      return true;
    }
    if (noPickup && arrivalDeparture == DEPARTURES) {
      return true;
    }
    if (noDropoff && arrivalDeparture == ARRIVALS) {
      return true;
    }
    return false;
  }

  private static boolean skipByStopCancellation(
    TripPattern pattern,
    boolean includeCancelledTrips,
    int stopIndex
  ) {
    boolean pickupCancelled = pattern.getBoardType(stopIndex).is(PickDrop.CANCELLED);
    boolean dropOffCancelled = pattern.getAlightType(stopIndex).is(PickDrop.CANCELLED);

    return (pickupCancelled || dropOffCancelled) && !includeCancelledTrips;
  }
}
