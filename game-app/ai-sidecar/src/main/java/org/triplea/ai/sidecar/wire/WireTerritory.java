package org.triplea.ai.sidecar.wire;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record WireTerritory(
    String territoryId, String owner, List<WireUnit> units, boolean conqueredThisTurn) {
  @JsonCreator
  public WireTerritory(
      @JsonProperty("territoryId") final String territoryId,
      @JsonProperty("owner") final String owner,
      @JsonProperty("units") final List<WireUnit> units,
      @JsonProperty("conqueredThisTurn") final Boolean conqueredThisTurn) {
    this(
        territoryId,
        owner,
        units == null ? List.of() : units,
        Boolean.TRUE.equals(conqueredThisTurn));
  }

  public WireTerritory(final String territoryId, final String owner, final List<WireUnit> units) {
    this(territoryId, owner, units, false);
  }
}
