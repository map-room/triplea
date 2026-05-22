package games.strategy.triplea.ai.pro.data;

import games.strategy.engine.data.Unit;
import java.util.Set;
import java.util.UUID;

/**
 * Snapshot of the amphibious-movement state delivered from Map Room via the wire protocol.
 *
 * <p>Populated once per executor call from {@code WireStateApplier.apply()} and stored on {@link
 * games.strategy.triplea.ai.pro.ProData} so Phase 2.4/2.5 AI subsystems can gate amphib planning on
 * the toggle and query per-unit embarkation state without re-parsing the wire.
 *
 * <p>All sets are immutable. {@link #DISABLED} is the zero-cost sentinel for the toggle-off case —
 * callers that only check {@link #isEnabled()} pay nothing when the rule is inactive.
 */
public final class AmphContext {

  public static final AmphContext DISABLED = new AmphContext(false, Set.of(), Set.of());

  private final boolean enabled;
  private final Set<UUID> embarkedUnitIds;
  private final Set<UUID> embarkedThisTurnUnitIds;

  public AmphContext(
      final boolean enabled,
      final Set<UUID> embarkedUnitIds,
      final Set<UUID> embarkedThisTurnUnitIds) {
    this.enabled = enabled;
    this.embarkedUnitIds = Set.copyOf(embarkedUnitIds);
    this.embarkedThisTurnUnitIds = Set.copyOf(embarkedThisTurnUnitIds);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public boolean isEmbarked(final Unit unit) {
    return embarkedUnitIds.contains(unit.getId());
  }

  public boolean isEmbarkedThisTurn(final Unit unit) {
    return embarkedThisTurnUnitIds.contains(unit.getId());
  }

  public Set<UUID> embarkedUnitIds() {
    return embarkedUnitIds;
  }

  public Set<UUID> embarkedThisTurnUnitIds() {
    return embarkedThisTurnUnitIds;
  }
}
