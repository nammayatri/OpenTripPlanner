package org.opentripplanner.ext.restapi.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

public class ApiStopShort {

  public String id;
  public String code;
  public String name;
  public double lat;
  public double lon;
  public String url;

  /**
   * The fully qualified parent station id including the feedId.
   */
  public String stationId;

  /** @deprecated Use "stationId" instead */
  @Deprecated
  public String cluster;

  /**
   * Raw value of the non-standard {@code info_json} column from stops.txt. Clients are
   * expected to parse the JSON. Null when the column or value is absent; omitted from JSON
   * in that case via {@link JsonInclude}.
   */

  @JsonInclude(Include.NON_NULL)
  public String desc;

  @JsonInclude(Include.NON_NULL)
  public String infoJson;

  /** Distance to the stop when it is returned from a location-based query. */
  @JsonInclude(Include.NON_NULL)
  public Integer dist;
}
