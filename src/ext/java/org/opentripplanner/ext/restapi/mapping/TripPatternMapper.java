package org.opentripplanner.ext.restapi.mapping;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.opentripplanner.ext.restapi.model.ApiPatternDetail;
import org.opentripplanner.ext.restapi.model.ApiPatternShort;
import org.opentripplanner.ext.restapi.model.ApiStopShort;
import org.opentripplanner.framework.i18n.I18NStringMapper;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.timetable.TripTimes;

public class TripPatternMapper {

  public static List<ApiPatternShort> mapToApiShort(Collection<TripPattern> domainList) {
    if (domainList == null) {
      return null;
    }
    return domainList.stream().map(TripPatternMapper::mapToApiShort).collect(Collectors.toList());
  }

  public static ApiPatternShort mapToApiShort(TripPattern domain) {
    if (domain == null) {
      return null;
    }
    return mapToApiShort(domain, ApiPatternShort::new);
  }

  public static ApiPatternDetail mapToApiDetailed(TripPattern domain) {
    if (domain == null) {
      return null;
    }

    ApiPatternDetail api = mapToApiShort(domain, ApiPatternDetail::new);
    api.stops = StopMapper.mapToApiShort(domain.getStops());
    addHeadsigns(api.stops, domain.getScheduledTimetable().getRepresentativeTripTimes());
    api.trips = TripMapper.mapToApiShort(domain.scheduledTripsAsStream());
    return api;
  }

  private static void addHeadsigns(Collection<ApiStopShort> stops, TripTimes tripTimes) {
    if (stops == null || tripTimes == null) {
      return;
    }
    var tripHeadsign = tripTimes.getTrip().getHeadsign();
    int i = 0;
    for (ApiStopShort stop : stops) {
      if (i >= tripTimes.getNumStops()) {
        return;
      }
      var headsign = tripTimes.getHeadsign(i++);
      if (headsign != null && !headsign.equals(tripHeadsign)) {
        stop.headsign = I18NStringMapper.mapToApi(headsign, null);
      }
    }
  }

  private static <T extends ApiPatternShort> T mapToApiShort(
    TripPattern domain,
    Supplier<T> create
  ) {
    T api = create.get();
    api.id = FeedScopedIdMapper.mapToApi(domain.getId());
    api.desc = domain.getName();
    api.routeId = FeedScopedIdMapper.mapIdToApi(domain.getRoute());
    return api;
  }
}
