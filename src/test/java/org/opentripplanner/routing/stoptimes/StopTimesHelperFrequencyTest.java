package org.opentripplanner.routing.stoptimes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opentripplanner.ConstantsForTests;
import org.opentripplanner.TestOtpModel;
import org.opentripplanner.model.StopTimesInPattern;
import org.opentripplanner.model.TripTimeOnDate;
import org.opentripplanner.test.support.ResourceLoader;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.service.DefaultTransitService;
import org.opentripplanner.transit.service.TransitModel;

/**
 * Departure boards for GTFS frequency-based trips.
 *
 * <p>The fixture at {@code /gtfs/frequency/} is deliberately different from
 * {@code /gtfs/simple/}, which cannot express these cases:
 * <ul>
 *   <li>its windows end at {@code ..:01}, so an inclusive and an exclusive end
 *       bound agree and an off-by-one there is invisible;
 *   <li>its trip has {@code arrival == departure} at every stop, so a bound
 *       computed from the arrival offset instead of the departure offset is
 *       invisible;
 *   <li>it has no {@code exact_times=0} row, so the gate is uncovered.
 * </ul>
 * All three trips run FA (00:00) to FB (arrive 00:10, depart 00:12 - a two minute dwell):
 * <ul>
 *   <li>{@code T_EXACT}: 06:00-10:00 every 3600s. The window is an exact multiple of the
 *       headway, so only an exclusive end bound gives four departures instead of five.
 *   <li>{@code T_DWELL}: 06:00-09:01 every 3600s. The last departure at FA (09:00) is within
 *       the dwell of the window's end, so a bound computed from FB's arrival offset (10 min)
 *       instead of its departure offset (12 min) drops FB's 09:12 departure.
 *   <li>{@code T_INEXACT}: identical to T_EXACT but {@code exact_times=0}.
 * </ul>
 * T_EXACT and T_DWELL share a stop pattern, so they also exercise a pattern holding more
 * than one frequency entry.
 */
class StopTimesHelperFrequencyTest {

  private static final LocalDate SERVICE_DATE = LocalDate.of(2021, Month.JULY, 26);
  private static final int H = 3600;

  private static DefaultTransitService transitService;
  private static String feedId;

  @BeforeAll
  static void setUp() {
    var gtfs = ResourceLoader.of(StopTimesHelperFrequencyTest.class).file("/gtfs/frequency/");
    TestOtpModel model = ConstantsForTests.buildGtfsGraph(gtfs);
    TransitModel transitModel = model.transitModel();
    transitService = new DefaultTransitService(transitModel);
    feedId = transitModel.getFeedIds().iterator().next();
  }

  private List<TripTimeOnDate> departures(String stop, int startHour, Duration range, int max) {
    Instant start = SERVICE_DATE
      .atStartOfDay(transitService.getTimeZone())
      .plusHours(startHour)
      .toInstant();
    List<StopTimesInPattern> result = StopTimesHelper.stopTimesForStop(
      transitService,
      transitService.getRegularStop(new FeedScopedId(feedId, stop)),
      start,
      range,
      max,
      ArrivalDeparture.BOTH,
      false
    );
    return result.stream().flatMap(p -> p.times.stream()).toList();
  }

  private static List<Integer> departureSeconds(List<TripTimeOnDate> times) {
    return times.stream().map(TripTimeOnDate::getScheduledDeparture).sorted().toList();
  }

  private static List<TripTimeOnDate> forTrip(List<TripTimeOnDate> times, String tripId) {
    return times.stream().filter(t -> t.getTrip().getId().getId().equals(tripId)).toList();
  }

  /**
   * The window is 06:00-10:00 with a 3600s headway, so the departures are
   * 06, 07, 08 and 09 - four, not five. GTFS treats end_time as exclusive, and
   * FrequencyEntry.nextDepartureTime agrees (its loop is {@code dep < end}), so a
   * board that also advertised 10:00 would offer a trip routing never returns.
   */
  @Test
  void endTimeIsExclusive() {
    var all = departures("FA", 5, Duration.ofHours(24), 100);
    assertEquals(
      List.of(6 * H, 7 * H, 8 * H, 9 * H),
      departureSeconds(forTrip(all, "T_EXACT")),
      "06:00-10:00 every hour is 06, 07, 08, 09 - the end is exclusive"
    );
  }

  /**
   * A frequency window is expressed at the first stop of the trip. At a later stop
   * every departure shifts by that stop's offset, and the offset has to be taken
   * from the departure times, not the arrivals. FB departs 12 minutes after FA
   * departs but is reached after only 10, so for T_DWELL (window ends 09:01) an
   * arrival-based bound ends at 09:11 and loses the legitimate 09:12 departure.
   */
  @Test
  void laterStopIsOffsetByItsDepartureTime() {
    var atFb = departures("FB", 5, Duration.ofHours(24), 100);
    assertEquals(
      List.of(6 * H + 720, 7 * H + 720, 8 * H + 720, 9 * H + 720),
      departureSeconds(forTrip(atFb, "T_DWELL")),
      "FB keeps all four departures, each 12 minutes after FA's, including the one at 09:12"
    );
    assertEquals(
      List.of(6 * H + 720, 7 * H + 720, 8 * H + 720, 9 * H + 720),
      departureSeconds(forTrip(atFb, "T_EXACT"))
    );
  }

  /** exact_times=0 publishes a headway, not departure times, so nothing is listed. */
  @Test
  void inexactFrequencyIsNotExpanded() {
    var all = departures("FA", 5, Duration.ofHours(24), 100);
    assertTrue(
      all.stream().noneMatch(t -> t.getTrip().getId().getId().equals("T_INEXACT")),
      "exact_times=0 trips must not appear on a departure board"
    );
    assertFalse(all.isEmpty(), "the exact_times=1 trip should still be there");
  }

  /** numberOfDepartures keeps the earliest n. */
  @Test
  void numberOfDeparturesIsRespected() {
    // T_EXACT and T_DWELL both depart FA at 06:00, so the earliest two are those.
    assertEquals(
      List.of(6 * H, 6 * H),
      departureSeconds(departures("FA", 5, Duration.ofHours(24), 2))
    );
    assertEquals(List.of(), departureSeconds(departures("FA", 5, Duration.ofHours(24), 0)));
  }

  /** Departures outside the requested time range are not returned. */
  @Test
  void timeRangeIsRespected() {
    // 05:00 + 3h30m reaches 08:30, so 06, 07 and 08 only.
    assertEquals(
      List.of(6 * H, 7 * H, 8 * H),
      departureSeconds(
        forTrip(departures("FA", 5, Duration.ofHours(3).plusMinutes(30), 100), "T_EXACT")
      )
    );
    // starting after the window has closed yields nothing
    assertEquals(List.of(), departureSeconds(departures("FA", 11, Duration.ofHours(5), 100)));
  }

  /**
   * A materialized departure is a shifted copy of the template, not a delayed
   * trip: the shift must not surface as realtime data.
   */
  @Test
  void materializedDeparturesAreNotRealtime() {
    var times = departures("FA", 5, Duration.ofHours(24), 100);
    assertFalse(times.isEmpty());
    for (TripTimeOnDate t : times) {
      assertEquals(t.getScheduledDeparture(), t.getRealtimeDeparture(), "no phantom delay");
      assertEquals(t.getScheduledArrival(), t.getRealtimeArrival(), "no phantom delay");
      assertFalse(t.isRealtime(), "a frequency departure is scheduled data");
    }
  }

  /** Each frequency entry in the pattern contributes its own departures, on the right day. */
  @Test
  void eachEntryInThePatternContributesItsOwnDepartures() {
    var times = departures("FA", 5, Duration.ofHours(24), 100);
    assertEquals(8, times.size(), "four from T_EXACT and four from T_DWELL, none from T_INEXACT");
    assertEquals(4, forTrip(times, "T_EXACT").size());
    assertEquals(4, forTrip(times, "T_DWELL").size());
    for (TripTimeOnDate t : times) {
      assertEquals(SERVICE_DATE, t.getServiceDay());
    }
  }
}
