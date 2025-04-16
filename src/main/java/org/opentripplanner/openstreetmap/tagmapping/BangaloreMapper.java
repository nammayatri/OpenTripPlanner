package org.opentripplanner.openstreetmap.tagmapping;

import org.opentripplanner.openstreetmap.wayproperty.WayPropertySet;

/**
 * OSM way properties for Bangalore roads. {@link BangaloreMapper} is derived from {@link ChennaiMapper}
 * by jayanth-parthsarathy
 * <p>
 * The main differences include lower average speeds for most road types due to frequent traffic jams,
 * signal stops, and bottlenecks.
 *
 * @author jayanth-parthsarathy
 * @see OsmTagMapper
 * @see DefaultMapper
 */
class BangaloreMapper implements OsmTagMapper {

  @Override
  public void populateProperties(WayPropertySet props) {
    // Motorways/Expressways (NICE Road, Elevated Flyovers)
    props.setCarSpeed("highway=motorway", 19.4f); // ~70 km/h
    props.setCarSpeed("highway=motorway_link", 16.7f); // ~60 km/h

    // Trunk Roads (Outer Ring Road, Tumkur Road, etc.)
    props.setCarSpeed("highway=trunk", 13.9f); // ~50 km/h
    props.setCarSpeed("highway=trunk_link", 11.1f); // ~40 km/h

    // Primary Roads (Old Airport Road, Bellary Road, etc.)
    props.setCarSpeed("highway=primary", 11.1f); // ~40 km/h
    props.setCarSpeed("highway=primary_link", 8.3f); // ~30 km/h

    // Secondary Roads (Commercial Street, connecting roads)
    props.setCarSpeed("highway=secondary", 8.3f); // ~30 km/h
    props.setCarSpeed("highway=secondary_link", 6.9f); // ~25 km/h

    // Tertiary Roads (Residential but broader streets)
    props.setCarSpeed("highway=tertiary", 6.9f); // ~25 km/h
    props.setCarSpeed("highway=tertiary_link", 5.6f); // ~20 km/h

    // Residential Roads (Inner layouts, local streets)
    props.setCarSpeed("highway=residential", 5.6f); // ~20 km/h

    // Pedestrian Zones (Brigade Road, MG Road pedestrian parts)
    props.setCarSpeed("highway=pedestrian", 2.8f); // ~10 km/h

    // Unclassified Roads (Internal undefined roads)
    props.setCarSpeed("highway=unclassified", 5.6f); // ~20 km/h

    // Service Roads (parallel roads to highways, tech park loops)
    props.setCarSpeed("highway=service", 4.2f); // ~15 km/h

    // Tracks (rural outskirts or underdeveloped roads)
    props.setCarSpeed("highway=track", 3.3f); // ~12 km/h

    // Generic fallback
    props.setCarSpeed("highway=road", 5.6f); // ~20 km/h

    // Defaults
    props.defaultCarSpeed = 5.6f; // ~20 km/h
    props.maxPossibleCarSpeed = 19.4f; // ~70 km/h

    new DefaultMapper().populateProperties(props);
  }
}
