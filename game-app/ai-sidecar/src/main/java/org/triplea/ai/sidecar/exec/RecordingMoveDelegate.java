package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Territory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.message.IRemote;
import games.strategy.engine.posted.game.pbem.PbemMessagePoster;
import games.strategy.net.websocket.ClientNetworkBridge;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.delegate.UndoableMove;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * {@link IMoveDelegate} stub that captures every {@link MoveDescription} handed to
 * {@link #performMove}, tagging each one with whether it is a strategic-bombing-raid move via
 * {@link ProAi#shouldBomberBomb}. Used by {@link CombatMoveExecutor} to intercept the side-effect
 * output of {@link games.strategy.triplea.ai.pro.AbstractProAi#invokeCombatMoveForSidecar} without
 * mutating the session's {@link games.strategy.engine.data.GameData}.
 *
 * <p>{@link ProAi#shouldBomberBomb} is sampled at capture time because
 * {@code ProCombatMoveAi.isBombing} is set to {@code true} exactly during the
 * {@code calculateBombingRoutes} batch call (batch 4 of the four sequential
 * {@code ProMoveUtils.doMove} calls in {@code doMove}) and reset to {@code false} immediately
 * after. The flag is therefore live and correct at the moment {@code performMove} fires.
 *
 * <p><b>Thread-safety:</b> not thread-safe. Each {@link CombatMoveExecutor#execute} call creates
 * a fresh instance; the session-scoped executor serialises access on the Java side.
 */
public final class RecordingMoveDelegate implements IMoveDelegate {

  /**
   * A single captured move together with its bombing classification at the time of capture.
   *
   * @param move the raw {@link MoveDescription} handed to the delegate
   * @param isBombing {@code true} when the move was issued during the SBR batch
   */
  public record CapturedMove(MoveDescription move, boolean isBombing) {}

  private final Predicate<Territory> bombingCheck;
  private final List<CapturedMove> captured = new ArrayList<>();
  private IDelegateBridge bridge;
  private String name = "recordingMove";
  private String displayName = "Recording Move";

  public RecordingMoveDelegate(final ProAi proAi) {
    this.bombingCheck = proAi::shouldBomberBomb;
  }

  /** Package-private constructor for unit tests that don't need a real {@link ProAi}. */
  RecordingMoveDelegate(final Predicate<Territory> bombingCheck) {
    this.bombingCheck = bombingCheck;
  }

  /** Returns an unmodifiable snapshot of all captured moves in call order. */
  public List<CapturedMove> captured() {
    return List.copyOf(captured);
  }

  @Override
  public Optional<String> performMove(final MoveDescription move) {
    final Territory end = move.getRoute().getEnd();
    captured.add(new CapturedMove(move, bombingCheck.test(end)));
    return Optional.empty();
  }

  // ---- IMoveDelegate no-ops ----

  @Override
  public Collection<Territory> getTerritoriesWhereAirCantLand(final GamePlayer player) {
    return List.of();
  }

  @Override
  public Collection<Territory> getTerritoriesWhereAirCantLand() {
    return List.of();
  }

  @Override
  public Collection<Territory> getTerritoriesWhereUnitsCantFight() {
    return List.of();
  }

  @Override
  public List<UndoableMove> getMovesMade() {
    return List.of();
  }

  @Override
  public String undoMove(final int moveIndex) {
    return null;
  }

  @Override
  public void setHasPostedTurnSummary(final boolean hasPostedTurnSummary) {}

  @Override
  public boolean getHasPostedTurnSummary() {
    return false;
  }

  @Override
  public boolean postTurnSummary(final PbemMessagePoster poster, final String title) {
    return true;
  }

  @Override
  public void initialize(final String name, final String displayName) {
    this.name = name;
    this.displayName = displayName;
  }

  @Override
  public void setDelegateBridgeAndPlayer(final IDelegateBridge delegateBridge) {
    this.bridge = delegateBridge;
  }

  @Override
  public void setDelegateBridgeAndPlayer(
      final IDelegateBridge delegateBridge, final ClientNetworkBridge clientNetworkBridge) {
    this.bridge = delegateBridge;
  }

  @Override
  public void start() {}

  @Override
  public void end() {}

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getDisplayName() {
    return displayName;
  }

  @Override
  public IDelegateBridge getBridge() {
    return bridge;
  }

  @Override
  public Serializable saveState() {
    return new Serializable() {
      private static final long serialVersionUID = 1L;
    };
  }

  @Override
  public void loadState(final Serializable state) {}

  @Override
  public Class<? extends IRemote> getRemoteType() {
    return IMoveDelegate.class;
  }

  @Override
  public boolean delegateCurrentlyRequiresUserInput() {
    return false;
  }
}
