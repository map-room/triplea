package org.triplea.ai.sidecar.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SessionManifestTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void toleratesUnknownFieldsOnDeserialization() throws Exception {
    // JSON written by an older sidecar version that included combatMoveMap and other fields
    // not present in the current SessionManifest schema.
    String json =
        """
        {
          "sessionId": "sess-1",
          "gameId":    "game-abc",
          "nation":    "Germans",
          "seed":      42,
          "createdAt": 1000,
          "updatedAt": 2000,
          "combatMoveMap": {"Germany": ["tank", "infantry"]},
          "legacyField": "ignored"
        }
        """;

    SessionManifest manifest =
        assertDoesNotThrow(() -> mapper.readValue(json, SessionManifest.class));

    assertEquals("sess-1", manifest.sessionId());
    assertEquals("game-abc", manifest.gameId());
    assertEquals("Germans", manifest.nation());
    assertEquals(42L, manifest.seed());
    assertEquals(1000L, manifest.createdAt());
    assertEquals(2000L, manifest.updatedAt());
  }
}
