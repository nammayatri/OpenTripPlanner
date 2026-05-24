package org.opentripplanner.gtfs.mapping;

import com.csvreader.CsvReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.onebusaway.csv_entities.CsvInputSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the non-standard {@code info_json} column from a GTFS feed's {@code stops.txt} as a
 * side-channel. onebusaway-gtfs only parses spec columns and silently drops unknown ones, so this
 * helper opens the same file separately and extracts a {@code Map<stop_id, info_json>} that callers
 * attach to the stop entity downstream.
 */
public final class InfoJsonStopReader {

  private static final Logger LOG = LoggerFactory.getLogger(InfoJsonStopReader.class);
  private static final String STOPS_FILE = "stops.txt";
  private static final String STOP_ID = "stop_id";
  private static final String INFO_JSON = "info_json";

  private InfoJsonStopReader() {}

  public static Map<String, String> read(CsvInputSource source) {
    Map<String, String> result = new HashMap<>();
    try {
      if (!source.hasResource(STOPS_FILE)) {
        return result;
      }
      try (InputStream in = source.getResource(STOPS_FILE)) {
        CsvReader csv = new CsvReader(in, StandardCharsets.UTF_8);
        try {
          if (!csv.readHeaders()) {
            return result;
          }
          if (csv.getIndex(STOP_ID) < 0) {
            LOG.debug("stops.txt has no stop_id header; skipping info_json extraction");
            return result;
          }
          if (csv.getIndex(INFO_JSON) < 0) {
            return result;
          }
          while (csv.readRecord()) {
            String stopId = csv.get(STOP_ID);
            String value = csv.get(INFO_JSON);
            if (stopId == null || stopId.isEmpty()) continue;
            if (value == null || value.isEmpty()) continue;
            result.put(stopId, value);
          }
        } finally {
          csv.close();
        }
      }
    } catch (IOException e) {
      LOG.warn("Failed to read info_json column from stops.txt: {}", e.getMessage());
    }
    return result;
  }
}
