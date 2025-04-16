package org.opentripplanner.openstreetmap.tagmapping;

import org.opentripplanner.openstreetmap.wayproperty.WayPropertySet;

/**
 * OSM way properties for Chennai roads. {@link ChennaiMapper} is derived from {@link NorwayMapper}
 * by jayanth-parthsarathy
 * <p>
 * The main difference compared to the default property set is that most of the roads have a
 * different speed.
 *
 * @author jayanth-parthsarathy
 * @see OsmTagMapper
 * @see DefaultMapper
 */

class ChennaiMapper implements OsmTagMapper {

  @Override
  public void populateProperties(WayPropertySet props) {
    // Motorways/Expressways (Chennai Bypass, etc.)
    props.setCarSpeed("highway=motorway", 22.2f); // ~80 km/h
    props.setCarSpeed("highway=motorway_link", 18.9f); // ~68 km/h

    // Trunk Roads (GST Road, Inner Ring Road, etc.)
    props.setCarSpeed("highway=trunk", 16.7f); // ~60 km/h
    props.setCarSpeed("highway=trunk_link", 14.4f); // ~52 km/h

    // Primary Roads (Anna Salai, Mount Road, etc.)
    props.setCarSpeed("highway=primary", 13.9f); // ~50 km/h
    props.setCarSpeed("highway=primary_link", 11.1f); // ~40 km/h

    // Secondary Roads (smaller main roads)
    props.setCarSpeed("highway=secondary", 11.1f); // ~40 km/h
    props.setCarSpeed("highway=secondary_link", 9.4f); // ~34 km/h

    // Tertiary Roads (local roads in residential areas)
    props.setCarSpeed("highway=tertiary", 8.3f); // ~30 km/h
    props.setCarSpeed("highway=tertiary_link", 6.9f); // ~25 km/h

    // Residential Roads (local lanes, residential areas)
    props.setCarSpeed("highway=residential", 6.9f); // ~25 km/h

    // Pedestrian Zones (shopping streets, markets)
    props.setCarSpeed("highway=pedestrian", 2.8f); // ~10 km/h

    // Unclassified Roads (miscellaneous roads)
    props.setCarSpeed("highway=unclassified", 6.9f); // ~25 km/h

    // Service Roads (near highways, bus terminals)
    props.setCarSpeed("highway=service", 5.6f); // ~20 km/h

    // Tracks (rough roads, rural or peripheral areas)
    props.setCarSpeed("highway=track", 4.2f); // ~15 km/h

    // Generic Roads (fallback case)
    props.setCarSpeed("highway=road", 6.9f); // ~25 km/h

    // Default and Maximum Possible Speed
    props.defaultCarSpeed = 6.9f; // ~25 km/h
    props.maxPossibleCarSpeed = 22.2f; // ~80 km/h
    new DefaultMapper().populateProperties(props);
  }
}
