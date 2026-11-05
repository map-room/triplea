package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.data.ProSplitResourceTracker.Pool;
import games.strategy.triplea.attachments.TerritoryAttachment;
import java.io.Serializable;
import java.util.Optional;
import java.util.Set;
import lombok.Getter;

/**
 * Map Room's two UK economies (Global 1940: Europe and Pacific), as the sidecar sees them.
 *
 * <p>TripleA's Global 1940 XML models them as two players: {@code British} (capital London) and
 * {@code UK_Pacific} (capital Calcutta). Map Room has one UK player with two income pools, so the
 * wire sends every UK territory as {@code British}-owned, tags each with its economy, and sends the
 * pools separately. Every capital helper in {@link ProUtils} asks whether a territory is "the
 * owner's capital", and on that board Calcutta is not {@code British}'s capital. This class carries
 * the missing fact: which economy a UK capital belongs to, which territories each economy owns, and
 * what each pool holds.
 *
 * <p>The territories stay {@code British}-owned on purpose. ProAI's purchase, placement and
 * movement all key on {@code isOwnedBy(player)}, so re-owning Pacific land to {@code UK_Pacific}
 * would stop British from placing at its Pacific factories.
 *
 * <p>Stored on the game's properties by the sidecar's {@code WireStateApplier} and absent on every
 * board with one UK economy (Anniversary), where every helper behaves as upstream does.
 */
public final class UkEconomySplit implements Serializable {
  private static final long serialVersionUID = 1L;

  /** Key under which the sidecar stores the split on {@code GameData.getProperties()}. */
  public static final String PROPERTY = "MapRoom UK Economy Split";

  /** The player that owns both economies' territories, and whose capital is the Europe one. */
  public static final String UK_PLAYER = "British";

  /** The player the map names as the Pacific economy's capital owner. */
  public static final String PACIFIC_CAPITAL_PLAYER = "UK_Pacific";

  private final Set<String> pacificTerritories;
  @Getter private final int europePus;
  @Getter private final int pacificPus;

  public UkEconomySplit(
      final Set<String> pacificTerritories, final int europePus, final int pacificPus) {
    this.pacificTerritories = Set.copyOf(pacificTerritories);
    this.europePus = europePus;
    this.pacificPus = pacificPus;
  }

  /** The split stored on {@code data}, if this board has one. */
  public static Optional<UkEconomySplit> of(final GameState data) {
    return Optional.ofNullable(data.getProperties().get(PROPERTY))
        .filter(UkEconomySplit.class::isInstance)
        .map(UkEconomySplit.class::cast);
  }

  /**
   * The economy whose capital {@code t} is, when its capital attachment names one of the UK's two
   * capital players. Empty for any other territory.
   */
  public static Optional<Pool> capitalEconomy(final Territory t) {
    return TerritoryAttachment.get(t)
        .flatMap(TerritoryAttachment::getCapital)
        .flatMap(
            capital -> {
              if (UK_PLAYER.equals(capital)) {
                return Optional.of(Pool.EUROPE);
              }
              return PACIFIC_CAPITAL_PLAYER.equals(capital)
                  ? Optional.of(Pool.PACIFIC)
                  : Optional.empty();
            });
  }

  /** The economy whose capital {@code t} is, only when {@code t} is held by the UK player. */
  public static Optional<Pool> heldCapitalEconomy(final Territory t) {
    if (!UK_PLAYER.equals(t.getOwner().getName()) || UkEconomySplit.of(t.getData()).isEmpty()) {
      return Optional.empty();
    }
    return capitalEconomy(t);
  }

  /** Whether {@code t} belongs to {@code economy}. Untagged territories are Europe's. */
  public boolean isIn(final Pool economy, final Territory t) {
    return pacificTerritories.contains(t.getName()) == (economy == Pool.PACIFIC);
  }

  /** What {@code economy}'s pool holds. */
  public int treasury(final Pool economy) {
    return economy == Pool.PACIFIC ? pacificPus : europePus;
  }
}
