package org.opentripplanner.ext.siri.updater;

import jakarta.xml.bind.JAXBException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.opentripplanner.framework.io.OtpHttpClient;
import org.opentripplanner.updater.spi.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.siri.siri20.Siri;

/**
 * Load real-time updates from SIRI-SX and SIRI-ET feeds over HTTP.
 */
public class SiriHttpLoader implements SiriLoader {

  private static final Logger LOG = LoggerFactory.getLogger(SiriHttpLoader.class);
  private final HttpHeaders requestHeaders;
  private final String url;
  private final Duration timeout;
  private final Duration previewInterval;
  private final OtpHttpClient otpHttpClient;

  public SiriHttpLoader(String url, Duration timeout, HttpHeaders requestHeaders) {
    this(url, timeout, requestHeaders, null);
  }

  public SiriHttpLoader(
    String url,
    Duration timeout,
    HttpHeaders requestHeaders,
    Duration previewInterval
  ) {
    this.url = url;
    this.timeout = timeout;
    this.requestHeaders = requestHeaders;
    this.previewInterval = previewInterval;
    this.otpHttpClient = new OtpHttpClient(timeout, timeout);
  }

  /**
   * Send a SIRI-SX service request and unmarshal the response as JAXB.
   */
  @Override
  public Optional<Siri> fetchSXFeed(String requestorRef) throws JAXBException {
    RequestTimer requestTimer = new RequestTimer("SX");
    requestTimer.init();
    String sxServiceRequest = SiriHelper.createSXServiceRequestAsXml(requestorRef);
    requestTimer.serviceRequestCreated();
    return fetchFeed(sxServiceRequest, requestTimer, requestorRef);
  }

  /**
   * Send a SIRI-ET service request and unmarshal the response as JAXB.
   */
  @Override
  public Optional<Siri> fetchETFeed(String requestorRef) throws JAXBException {
    RequestTimer requestTimer = new RequestTimer("ET");
    requestTimer.init();
    String etServiceRequest = SiriHelper.createETServiceRequestAsXml(requestorRef, previewInterval);
    requestTimer.serviceRequestCreated();
    return fetchFeed(etServiceRequest, requestTimer, requestorRef);
  }

  private Optional<Siri> fetchFeed(
    String serviceRequest,
    RequestTimer requestTimer,
    String requestorRef
  ) {
    try {
      final OtpHttpClient.ResponseMapper<Optional<Siri>> responseMapper = (is) -> {
        requestTimer.responseFetched();
        Siri siri = SiriHelper.unmarshal(is);
        requestTimer.responseUnmarshalled();
        return Optional.of(siri);
      };
      return otpHttpClient.getAndMap(URI.create(url), timeout, requestHeaders.asMap(), responseMapper);
//      return otpHttpClient.postXmlAndMap(
//        url,
//        serviceRequest,
//        timeout,
//        requestHeaders.asMap(),
//      );
    } finally {
      LOG.info(
        "Updating SIRI-{} [{}]: Create req: {} ms, Fetching data: {} ms, Unmarshalling: {} ms",
        requestTimer.feedType,
        requestorRef,
        requestTimer.creating(),
        requestTimer.fetching(),
        requestTimer.unmarshalling()
      );
    }
  }

  private static final class RequestTimer {

    private final String feedType;
    private long initAt;
    private long createdAt;
    private long fetchedAt;
    private long unmarshalledAt;

    RequestTimer(String feedType) {
      this.feedType = feedType;
    }

    void init() {
      initAt = System.currentTimeMillis();
    }

    void serviceRequestCreated() {
      createdAt = System.currentTimeMillis();
    }

    void responseFetched() {
      fetchedAt = System.currentTimeMillis();
    }

    void responseUnmarshalled() {
      unmarshalledAt = System.currentTimeMillis();
    }

    /**
     * @return time spent creating the request body.
     */
    long creating() {
      return createdAt - initAt;
    }

    /**
     * @return time spent fetching the response, or -1 if the fetch never completed.
     */
    long fetching() {
      return (fetchedAt == 0) ? -1 : fetchedAt - createdAt;
    }

    /**
     * @return time spent unmarshalling the response.
     */
    long unmarshalling() {
      return unmarshalledAt - fetchedAt;
    }
  }
}
