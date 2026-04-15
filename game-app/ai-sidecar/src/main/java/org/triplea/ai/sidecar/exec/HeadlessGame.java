package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.PlayerManager;
import games.strategy.engine.display.IDisplay;
import games.strategy.engine.framework.IGame;
import games.strategy.engine.random.IRandomSource;
import games.strategy.engine.vault.Vault;
import games.strategy.net.Messengers;
import games.strategy.triplea.ResourceLoader;
import java.nio.file.Path;
import javax.annotation.Nullable;
import org.triplea.sound.ISound;

/**
 * Bare-minimum {@link IGame} used purely to back a {@link
 * games.strategy.engine.player.PlayerBridge} that wraps the sidecar session's cloned {@link
 * GameData}.
 *
 * <p>{@code PlayerBridge} calls exactly one method on the {@code IGame} during construction
 * ({@code getData().addGameDataEventListener}) and thereafter uses {@code getData()} for the
 * lookups that {@code AbstractBasePlayer.getGameData()} performs on behalf of the AI. Every
 * other entry point on {@code IGame} is unreachable from the decision-execution path and
 * throws {@link UnsupportedOperationException} so that future accidental reliance on them
 * fails loudly instead of silently mis-wiring message passing / vaults / random sources.
 */
final class HeadlessGame implements IGame {
  private final GameData data;

  HeadlessGame(final GameData data) {
    this.data = data;
  }

  @Override
  public GameData getData() {
    return data;
  }

  @Override
  public Messengers getMessengers() {
    throw new UnsupportedOperationException("HeadlessGame has no messengers");
  }

  @Override
  public Vault getVault() {
    throw new UnsupportedOperationException("HeadlessGame has no vault");
  }

  @Override
  public void addChange(final Change change) {
    throw new UnsupportedOperationException("HeadlessGame does not accept remote changes");
  }

  @Override
  public @Nullable IRandomSource getRandomSource() {
    return null;
  }

  @Override
  public void setDisplay(final @Nullable IDisplay display) {
    // no-op
  }

  @Override
  public void setSoundChannel(final @Nullable ISound display) {
    // no-op
  }

  @Override
  public boolean isGameOver() {
    return false;
  }

  @Override
  public PlayerManager getPlayerManager() {
    throw new UnsupportedOperationException("HeadlessGame has no player manager");
  }

  @Override
  public void saveGame(final Path f) {
    throw new UnsupportedOperationException("HeadlessGame cannot save");
  }

  @Override
  public ResourceLoader getResourceLoader() {
    throw new UnsupportedOperationException("HeadlessGame has no resource loader");
  }

  @Override
  public void setResourceLoader(final ResourceLoader resourceLoader) {
    // no-op
  }
}
