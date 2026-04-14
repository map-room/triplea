package games.strategy.triplea.ai.pro;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameSequence;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.delegate.PurchaseDelegate;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Issue #1735 — step 2 of the ProAI HTTP service spike.
 *
 * <p>Spins up a minimum-viable {@link HttpServer} in-process, POSTs to {@code /decision}, and has
 * the handler invoke {@link AbstractProAi#purchase(boolean, int, PurchaseDelegate, GameData,
 * GamePlayer)} against a canonical Global 1940 game state held in memory. Measures end-to-end
 * latency after the ProAi instance has been warmed with one prior purchase call.
 *
 * <p>Zero new dependencies: uses JDK built-in {@code com.sun.net.httpserver} and {@code
 * java.net.http.HttpClient}.
 */
public class ProAiHttpServerSpikeTest {

  @Test
  public void postDecisionRoundTrip() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());

    final GameData gameData = TestMapGameData.GLOBAL1940.getGameData();
    final GamePlayer germans = gameData.getPlayerList().getPlayerId("Germans");
    Assertions.assertNotNull(germans);
    advanceToPurchaseStepFor(gameData, "Germans");

    final ProAi proAi = new ProAi("Spike", "Germans");
    final IDelegateBridge bridge = newDelegateBridge(germans);
    final PurchaseDelegate purchaseDelegate = (PurchaseDelegate) gameData.getDelegate("purchase");
    purchaseDelegate.setDelegateBridgeAndPlayer(bridge);

    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    when(playerBridgeMock.getGameData()).thenReturn(gameData);
    proAi.initialize(playerBridgeMock, germans);

    // Warm up: one full purchase call so JIT + ProData are hot.
    final int pus = germans.getResources().getQuantity("PUs");
    proAi.purchase(false, pus, purchaseDelegate, gameData, germans);

    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/decision",
        (HttpExchange exchange) -> {
          try {
            final long handleStart = System.nanoTime();
            proAi.purchase(false, pus, purchaseDelegate, gameData, germans);
            final long handleNs = System.nanoTime() - handleStart;
            final String body =
                String.format(
                    "{\"kind\":\"purchase\",\"player\":\"Germans\",\"pus\":%d,"
                        + "\"handleMs\":%.1f,\"result\":\"ok\"}",
                    pus, handleNs / 1_000_000.0);
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(bytes);
            }
          } catch (final RuntimeException e) {
            final byte[] err = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes();
            exchange.sendResponseHeaders(500, err.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(err);
            }
          }
        });
    server.start();
    try {
      final int port = server.getAddress().getPort();
      System.out.printf("[spike-http] listening on 127.0.0.1:%d%n", port);

      final HttpClient client =
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      final HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create("http://127.0.0.1:" + port + "/decision"))
              .timeout(Duration.ofSeconds(60))
              .POST(HttpRequest.BodyPublishers.ofString("{\"kind\":\"purchase\"}"))
              .build();

      final long t0 = System.nanoTime();
      final HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
      final long roundTripNs = System.nanoTime() - t0;

      System.out.printf(
          "[spike-http] POST /decision round-trip=%.1fms status=%d body=%s%n",
          roundTripNs / 1_000_000.0, resp.statusCode(), resp.body());

      Assertions.assertEquals(200, resp.statusCode());
      Assertions.assertTrue(resp.body().contains("\"result\":\"ok\""));
    } finally {
      server.stop(0);
    }
  }

  private static void advanceToPurchaseStepFor(final GameData gameData, final String playerName) {
    final GameSequence sequence = gameData.getSequence();
    int tries = 0;
    while (tries++ < 200) {
      final GameStep step = sequence.getStep();
      if (step != null
          && step.getPlayerId() != null
          && playerName.equals(step.getPlayerId().getName())
          && GameStep.isPurchaseStepName(step.getName())) {
        return;
      }
      sequence.next();
    }
    throw new IllegalStateException("Never reached " + playerName + " purchase step");
  }
}
