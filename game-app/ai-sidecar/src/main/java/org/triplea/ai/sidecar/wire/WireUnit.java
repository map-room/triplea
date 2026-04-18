package org.triplea.ai.sidecar.wire;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record WireUnit(
    String unitId, String unitType, int hitsTaken, int movesUsed, int bombingDamage,
    @Nullable String owner,
    @Nullable String transportedBy,
    boolean submerged,
    boolean wasInCombat,
    boolean wasLoadedThisTurn,
    boolean wasUnloadedInCombatPhase,
    int bonusMovement) {
  @JsonCreator
  public static WireUnit of(
      @JsonProperty("unitId") final String unitId,
      @JsonProperty("unitType") final String unitType,
      @JsonProperty("hitsTaken") final Integer hitsTaken,
      @JsonProperty("movesUsed") final Integer movesUsed,
      @JsonProperty("bombingDamage") final Integer bombingDamage,
      @JsonProperty("owner") final String owner,
      @JsonProperty("transportedBy") final String transportedBy,
      @JsonProperty("submerged") final Boolean submerged,
      @JsonProperty("wasInCombat") final Boolean wasInCombat,
      @JsonProperty("wasLoadedThisTurn") final Boolean wasLoadedThisTurn,
      @JsonProperty("wasUnloadedInCombatPhase") final Boolean wasUnloadedInCombatPhase,
      @JsonProperty("bonusMovement") final Integer bonusMovement) {
    return new WireUnit(
        unitId,
        unitType,
        hitsTaken == null ? 0 : hitsTaken,
        movesUsed == null ? 0 : movesUsed,
        bombingDamage == null ? 0 : bombingDamage,
        owner,
        transportedBy,
        submerged != null && submerged,
        wasInCombat != null && wasInCombat,
        wasLoadedThisTurn != null && wasLoadedThisTurn,
        wasUnloadedInCombatPhase != null && wasUnloadedInCombatPhase,
        bonusMovement == null ? 0 : bonusMovement);
  }

  /** Backward-compat constructor used by existing tests that don't specify an owner. */
  public WireUnit(
      final String unitId, final String unitType, final int hitsTaken, final int movesUsed) {
    this(unitId, unitType, hitsTaken, movesUsed, 0, null, null, false, false, false, false, 0);
  }

  /** Backward-compat constructor for tests that specify bombingDamage but not owner. */
  public WireUnit(
      final String unitId, final String unitType,
      final int hitsTaken, final int movesUsed, final int bombingDamage) {
    this(unitId, unitType, hitsTaken, movesUsed, bombingDamage, null, null, false, false, false, false, 0);
  }

  /** Backward-compat constructor for tests that specify an owner but not the new fields. */
  public WireUnit(
      final String unitId, final String unitType,
      final int hitsTaken, final int movesUsed, final int bombingDamage,
      final String owner) {
    this(unitId, unitType, hitsTaken, movesUsed, bombingDamage, owner, null, false, false, false, false, 0);
  }
}
