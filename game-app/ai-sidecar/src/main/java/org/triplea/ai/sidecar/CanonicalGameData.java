package org.triplea.ai.sidecar;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.gameparser.GameParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class CanonicalGameData {

  private final GameData template;
  private final byte[] serialized;

  private CanonicalGameData(final GameData template, final byte[] serialized) {
    this.template = template;
    this.serialized = serialized;
  }

  /**
   * Parses the given classpath XML resource (e.g. {@code "ww2global40_2nd_edition.xml"}) into a
   * template {@link GameData} plus a serialized snapshot used by {@link #cloneForSession()}.
   */
  public static CanonicalGameData load(final String resource) {
    try {
      final GameData data = parseCanonicalXml(resource);
      final ByteArrayOutputStream baos = new ByteArrayOutputStream();
      try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
        oos.writeObject(data);
      }
      return new CanonicalGameData(data, baos.toByteArray());
    } catch (final IOException e) {
      throw new UncheckedIOException(
          "Failed to serialize canonical GameData for resource: " + resource, e);
    }
  }

  public GameData template() {
    return template;
  }

  public GameData cloneForSession() {
    try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
      final GameData data = (GameData) ois.readObject();
      // GameData has transient listener lists / delegate maps wiped by the default
      // readObject; postDeSerialize() re-initializes them. Normally GameDataManager does
      // this after loading a save; we must do the same here or any Change that fires
      // territory listeners (e.g. RemoveUnits / OwnerChange) will NPE.
      data.postDeSerialize();
      return data;
    } catch (final IOException | ClassNotFoundException e) {
      throw new IllegalStateException("Failed to clone canonical GameData", e);
    }
  }

  private static GameData parseCanonicalXml(final String resource) throws IOException {
    final URL url = CanonicalGameData.class.getClassLoader().getResource(resource);
    if (url == null) {
      throw new IllegalStateException("Missing classpath resource: " + resource);
    }
    final Path tmp = Files.createTempFile("ai-sidecar-g40-", ".xml");
    tmp.toFile().deleteOnExit();
    try (InputStream in = url.openStream()) {
      Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
    }
    return GameParser.parse(tmp, false)
        .orElseThrow(() -> new IllegalStateException("GameParser returned empty for " + resource));
  }
}
