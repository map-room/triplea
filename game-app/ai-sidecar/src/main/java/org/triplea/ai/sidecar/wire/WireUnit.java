package org.triplea.ai.sidecar.wire;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record WireUnit(
    String unitId, String unitType, int hitsTaken, int movesUsed, int bombingDamage,
    @Nullable String owner) {
  @JsonCreator
  public static WireUnit of(
      @JsonProperty("unitId") final String unitId,
      @JsonProperty("unitType") final String unitType,
      @JsonProperty("hitsTaken") final Integer hitsTaken,
      @JsonProperty("movesUsed") final Integer movesUsed,
      @JsonProperty("bombingDamage") final Integer bombingDamage,
      @JsonProperty("owner") final String owner) {
    return new WireUnit(
        unitId,
        unitType,
        hitsTaken == null ? 0 : hitsTaken,
        movesUsed == null ? 0 : movesUsed,
        bombingDamage == null ? 0 : bombingDamage,
        owner);
  }

  /** Backward-compat constructor used by existing tests that don't specify an owner. */
  public WireUnit(
      final String unitId, final String unitType, final int hitsTaken, final int movesUsed) {
    this(unitId, unitType, hitsTaken, movesUsed, 0, null);
  }

  /** Backward-compat constructor for tests that specify bombingDamage but not owner. */
  public WireUnit(
      final String unitId, final String unitType,
      final int hitsTaken, final int movesUsed, final int bombingDamage) {
    this(unitId, unitType, hitsTaken, movesUsed, bombingDamage, null);
  }
}
