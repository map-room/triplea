package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.delegate.MoveDelegate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Validating {@link MoveDelegate} subclass that captures every {@link MoveDescription} that passes
 * {@code super.performMove()} validation, tagging each with whether it is a strategic-bombing-raid
 * move via {@link ProAi#shouldBomberBomb}.
 *
 * <p>Each move is forwarded to {@code super.performMove()} first, which runs the full {@link
 * games.strategy.triplea.delegate.move.validation.MoveValidator} pipeline. Only moves that pass
 * validation are captured; invalid moves return the error {@link Optional} so that ProAi can
 * observe the rejection and avoid re-submitting the same move.
 *
 * <p>Callers must set up the bridge before use:
 *
 * <ol>
 *   <li>{@code recorder.initialize("move", "Move")}
 *   <li>{@code recorder.setDelegateBridgeAndPlayer(new ProDummyDelegateBridge(proAi, player,
 *       data))}
 * </ol>
 *
 * <p>{@link ProAi#shouldBomberBomb} is sampled at capture time because {@code
 * ProCombatMoveAi.isBombing} is set to {@code true} exactly during the {@code
 * calculateBombingRoutes} batch call and reset to {@code false} immediately after. The flag is
 * therefore live and correct at the moment {@code performMove} fires.
 *
 * <p><b>Thread-safety:</b> not thread-safe. Each executor call creates a fresh instance; the
 * session-scoped executor serialises access on the Java side.
 */
public final class RecordingMoveDelegate extends MoveDelegate {

  /**
   * A single captured move together with its bombing classification at the time of capture.
   *
   * @param move the raw {@link MoveDescription} handed to the delegate
   * @param isBombing {@code true} when the move was issued during the SBR batch
   */
  public record CapturedMove(MoveDescription move, boolean isBombing) {}

  private final ProAi proAi;
  private final List<CapturedMove> captured = new ArrayList<>();

  public RecordingMoveDelegate(final ProAi proAi) {
    this.proAi = proAi;
  }

  /** Returns an unmodifiable snapshot of all successfully validated moves in call order. */
  public List<CapturedMove> captured() {
    return List.copyOf(captured);
  }

  @Override
  public Optional<String> performMove(final MoveDescription move) {
    final Optional<String> error = super.performMove(move);
    if (error.isPresent()) {
      // Move failed MoveValidator — do NOT record, propagate error to ProAi.
      return error;
    }
    final Territory end = move.getRoute().getEnd();
    captured.add(new CapturedMove(move, proAi.shouldBomberBomb(end)));
    return Optional.empty();
  }
}
