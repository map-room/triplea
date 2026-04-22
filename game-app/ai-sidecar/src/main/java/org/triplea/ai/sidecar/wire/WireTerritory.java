package org.triplea.ai.sidecar.wire;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Wire serialization of a Map Room territory. The {@code economy} field is set by Map Room for
 * British-owned territories (and sea zones with a single adjacent British factory) — {@code
 * "europe"} or {@code "pacific"} — and is consumed by {@link
 * org.triplea.ai.sidecar.exec.PurchaseExecutor} to build the split-resource tracker's per-territory
 * pool map. Absent for non-British territories and for ambiguous/unmapped sea zones.
 */
public record WireTerritory(
    String territoryId,
    String owner,
    List<WireUnit> units,
    boolean conqueredThisTurn,
    @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String economy) {
  @JsonCreator
  public WireTerritory(
      @JsonProperty("territoryId") final String territoryId,
      @JsonProperty("owner") final String owner,
      @JsonProperty("units") final List<WireUnit> units,
      @JsonProperty("conqueredThisTurn") final Boolean conqueredThisTurn,
      @JsonProperty("economy") final String economy) {
    this(
        territoryId,
        owner,
        units == null ? List.of() : units,
        Boolean.TRUE.equals(conqueredThisTurn),
        economy);
  }

  public WireTerritory(final String territoryId, final String owner, final List<WireUnit> units) {
    this(territoryId, owner, units, false, null);
  }

  public WireTerritory(
      final String territoryId,
      final String owner,
      final List<WireUnit> units,
      final boolean conqueredThisTurn) {
    this(territoryId, owner, units, conqueredThisTurn, null);
  }
}
