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
  private static final String XML_RESOURCE = "ww2_g40_balanced.xml";

  private final GameData template;
  private final byte[] serialized;

  private CanonicalGameData(final GameData template, final byte[] serialized) {
    this.template = template;
    this.serialized = serialized;
  }

  public static CanonicalGameData load() {
    try {
      final GameData data = parseCanonicalXml();
      final ByteArrayOutputStream baos = new ByteArrayOutputStream();
      try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
        oos.writeObject(data);
      }
      return new CanonicalGameData(data, baos.toByteArray());
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to serialize canonical Global 1940 GameData", e);
    }
  }

  public GameData template() {
    return template;
  }

  public GameData cloneForSession() {
    try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
      return (GameData) ois.readObject();
    } catch (final IOException | ClassNotFoundException e) {
      throw new IllegalStateException("Failed to clone canonical GameData", e);
    }
  }

  private static GameData parseCanonicalXml() throws IOException {
    final URL url = CanonicalGameData.class.getClassLoader().getResource(XML_RESOURCE);
    if (url == null) {
      throw new IllegalStateException("Missing classpath resource: " + XML_RESOURCE);
    }
    final Path tmp = Files.createTempFile("ai-sidecar-g40-", ".xml");
    tmp.toFile().deleteOnExit();
    try (InputStream in = url.openStream()) {
      Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
    }
    return GameParser.parse(tmp, false)
        .orElseThrow(
            () -> new IllegalStateException("GameParser returned empty for " + XML_RESOURCE));
  }
}
