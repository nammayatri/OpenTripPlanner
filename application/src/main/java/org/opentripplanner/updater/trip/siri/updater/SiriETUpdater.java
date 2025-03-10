package org.opentripplanner.updater.trip.siri.updater;

import java.util.List;
import java.util.function.Consumer;
import org.opentripplanner.updater.spi.PollingGraphUpdater;
import org.opentripplanner.updater.spi.PollingGraphUpdaterParameters;
import org.opentripplanner.updater.spi.ResultLogger;
import org.opentripplanner.updater.spi.UpdateResult;
import org.opentripplanner.updater.trip.UrlUpdaterParameters;
import org.opentripplanner.updater.trip.siri.SiriRealTimeTripUpdateAdapter;
import org.opentripplanner.utils.tostring.ToStringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.siri.siri20.EstimatedTimetableDeliveryStructure;
import uk.org.siri.siri20.ServiceDelivery;

/**
 * Update OTP stop timetables from some a Siri-ET HTTP sources.
 */
public class SiriETUpdater extends PollingGraphUpdater {

  private static final Logger LOG = LoggerFactory.getLogger(SiriETUpdater.class);
  /**
   * Update streamer
   */
  private final EstimatedTimetableSource updateSource;

  /**
   * Feed id that is used for the trip ids in the TripUpdates
   */
  private final String feedId;

  private final EstimatedTimetableHandler estimatedTimetableHandler;

  private final Consumer<UpdateResult> metricsConsumer;

  public SiriETUpdater(
    Parameters config,
    SiriRealTimeTripUpdateAdapter adapter,
    EstimatedTimetableSource source,
    Consumer<UpdateResult> metricsConsumer
  ) {
    super(config);
    this.feedId = config.feedId();

    this.updateSource = source;

    this.blockReadinessUntilInitialized = config.blockReadinessUntilInitialized();

    LOG.info("Creating SIRI-ET updater running every {}: {}", pollingPeriod(), updateSource);

    estimatedTimetableHandler = new EstimatedTimetableHandler(
      adapter,
      config.fuzzyTripMatching(),
      feedId
    );

    this.metricsConsumer = metricsConsumer;
  }

  /**
   * Repeatedly makes blocking calls to an UpdateStreamer to retrieve new stop time updates, and
   * applies those updates to the graph.
   */
  @Override
  public void runPolling() {
    boolean moreData = false;
    do {
      var updates = updateSource.getUpdates();
      if (updates.isPresent()) {
        var incrementality = updateSource.incrementalityOfLastUpdates();
        ServiceDelivery serviceDelivery = updates.get().getServiceDelivery();
        moreData = Boolean.TRUE.equals(serviceDelivery.isMoreData());
        // Mark this updater as primed after last page of updates. Copy moreData into a final
        // primitive, because the object moreData persists across iterations.
        final boolean markPrimed = !moreData;
        List<EstimatedTimetableDeliveryStructure> etds =
          serviceDelivery.getEstimatedTimetableDeliveries();
        if (etds != null) {
          updateGraph(context -> {
            var result = estimatedTimetableHandler.applyUpdate(etds, incrementality, context);
            ResultLogger.logUpdateResult(feedId, "siri-et", result);
            metricsConsumer.accept(result);
            if (markPrimed) {
              primed = true;
            }
          });
        }
      }
    } while (moreData);
  }

  @Override
  public String toString() {
    return ToStringBuilder.of(SiriETUpdater.class)
      .addStr("source", updateSource.toString())
      .addDuration("frequency", pollingPeriod())
      .toString();
  }

  public interface Parameters extends UrlUpdaterParameters, PollingGraphUpdaterParameters {
    String url();

    boolean blockReadinessUntilInitialized();

    boolean fuzzyTripMatching();
  }
}
