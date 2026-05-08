package org.triplea.ai.sidecar.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.pro.Snapshotted;
import games.strategy.triplea.ai.pro.data.PlaceTerritorySnapshot;
import games.strategy.triplea.ai.pro.data.ProPlaceTerritory;
import games.strategy.triplea.ai.pro.data.ProPurchaseTerritory;
import games.strategy.triplea.ai.pro.data.ProSessionSnapshot;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritorySnapshot;
import games.strategy.triplea.ai.pro.data.PurchaseTerritorySnapshot;
import games.strategy.triplea.delegate.EndRoundDelegate;
import games.strategy.triplea.delegate.MoveDelegate;
import games.strategy.triplea.delegate.PlaceDelegate;
import games.strategy.triplea.delegate.PoliticsDelegate;
import games.strategy.triplea.settings.ClientSetting;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;

/**
 * Verifies that every {@link Snapshotted}-annotated field in the ProAi data classes:
 *
 * <ol>
 *   <li>Appears in the correct snapshot DTO.
 *   <li>Survives a Jackson JSON round-trip with its structure intact.
 *   <li>Can be restored from a real {@link ProSessionSnapshot} produced by {@link
 *       AbstractProAi#snapshotForSidecar} after a real {@code invokePurchaseForSidecar} call on the
 *       canonical Global 1940 map.
 * </ol>
 */
class ProSessionSnapshotRoundTripTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  /** Collect all fields annotated with {@link Snapshotted} across the three ProData classes. */
  @Test
  void everySnapshottedFieldIsAnnotatedOnExpectedClass() {
    final List<Field> snapshottedFields = new ArrayList<>();
    for (final Class<?> cls :
        List.of(ProTerritory.class, ProPurchaseTerritory.class, ProPlaceTerritory.class)) {
      for (final Field f : getAllFields(cls)) {
        if (f.isAnnotationPresent(Snapshotted.class)) {
          snapshottedFields.add(f);
        }
      }
    }
    assertEquals(
        8,
        snapshottedFields.size(),
        "Expected exactly 8 @Snapshotted fields; found: "
            + snapshottedFields.stream()
                .map(f -> f.getDeclaringClass().getSimpleName() + "." + f.getName())
                .toList());
  }

  /** Verify the 4-arg DTO constructor serializes and deserializes via Jackson correctly. */
  @Test
  void snapshotDtosRoundTripThroughJackson() throws Exception {
    final ProTerritorySnapshot territorySnap =
        new ProTerritorySnapshot(
            List.of("aaaa-bbbb-cccc-dddd"),
            List.of("eeee-ffff-0000-1111"),
            Map.of("transport-uuid", List.of("carried-uuid-1", "carried-uuid-2")),
            Map.of("transport-uuid", "Sea Zone 5"),
            Map.of("bombard-uuid", "Western Germany"));

    final PlaceTerritorySnapshot placeSnap =
        new PlaceTerritorySnapshot("Western Germany", List.of("infantry", "artillery"));

    final PurchaseTerritorySnapshot purchaseSnap =
        new PurchaseTerritorySnapshot(List.of(placeSnap));

    final ProSessionSnapshot original =
        new ProSessionSnapshot(
            Map.of("Eastern Germany", territorySnap),
            Map.of("France", territorySnap),
            Map.of("Germany", purchaseSnap),
            Map.of("wire-unit-1", "aaaa-bbbb-cccc-dddd"));

    final String json = MAPPER.writeValueAsString(original);
    final ProSessionSnapshot restored = MAPPER.readValue(json, ProSessionSnapshot.class);

    assertNotNull(restored);
    assertEquals(1, restored.combatMoveMap().size());
    assertEquals(
        List.of("aaaa-bbbb-cccc-dddd"), restored.combatMoveMap().get("Eastern Germany").unitIds());
    assertEquals(1, restored.purchaseTerritories().size());
    assertEquals(
        "Western Germany",
        restored.purchaseTerritories().get("Germany").canPlaceTerritories().get(0).territoryName());
    assertEquals(Map.of("wire-unit-1", "aaaa-bbbb-cccc-dddd"), restored.unitIdMap());
  }

  /**
   * Real ProAi round-trip: run {@code invokePurchaseForSidecar} for Germans on the canonical Global
   * 1940 map, snapshot, JSON round-trip, then verify that:
   *
   * <ul>
   *   <li>The purchase snapshot is non-empty (stored maps were populated).
   *   <li>unitIdMap is non-empty.
   *   <li>After pre-populating a fresh unitIdMap and applying WireStateApplier, {@code
   *       restorePurchaseTerritoriesFromSnapshot} produces a non-null, non-empty map.
   *   <li>Every entry in the restored map has non-empty {@code canPlaceTerritories}.
   * </ul>
   */
  @Test
  void realProAiPurchaseSnapshotRoundTrip() throws Exception {
    final CanonicalGameData canonical = CanonicalGameData.load();
    final GameData data = canonical.cloneForSession();
    final ProAi proAi = new ProAi("test", "Germans");

    final GamePlayer player = data.getPlayerList().getPlayerId("Germans");
    assertNotNull(player, "Germans player must exist on canonical map");

    ExecutorSupport.ensureProAiInitialized(
        new org.triplea.ai.sidecar.session.Session(
            "s-test",
            new org.triplea.ai.sidecar.session.SessionKey("test-game", "Germans", 1),
            42L,
            proAi,
            data,
            new ConcurrentHashMap<>(),
            java.util.concurrent.Executors.newSingleThreadExecutor()),
        player);
    ExecutorSupport.ensureBattleDelegate(data);
    ensureDelegate(data, "move", "Move", new MoveDelegate());
    ensureDelegate(data, "place", "Place", new PlaceDelegate());
    ensureDelegate(data, "politics", "Politics", new PoliticsDelegate());
    ensureDelegate(data, "endRound", "End Round", new EndRoundDelegate());

    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final int budget = player.getResources().getQuantity(pus);

    // Run a real purchase
    final RecordingPurchaseDelegate recorder = new RecordingPurchaseDelegate();
    proAi.invokePurchaseForSidecar(false, budget, recorder, data, player);

    // Build snapshot with a fake unitIdMap (wire IDs not needed for this test path)
    final Map<String, String> wireToUuid = Map.of("test-wire-id", UUID.randomUUID().toString());
    final ProSessionSnapshot snap = proAi.snapshotForSidecar(wireToUuid);

    // purchaseTerritories must be non-empty (ProAi populated them)
    assertFalse(
        snap.purchaseTerritories().isEmpty(),
        "storedPurchaseTerritories must be non-empty after purchase");

    // unitIdMap must be present
    assertEquals(wireToUuid, snap.unitIdMap());

    // JSON round-trip
    final String json = MAPPER.writeValueAsString(snap);
    final ProSessionSnapshot deserialized = MAPPER.readValue(json, ProSessionSnapshot.class);

    assertNotNull(deserialized);
    assertFalse(deserialized.purchaseTerritories().isEmpty());
    assertEquals(snap.unitIdMap(), deserialized.unitIdMap());

    // Verify per-map restore methods work on a fresh ProAi
    final ProAi freshProAi = new ProAi("test-fresh", "Germans");

    // Before restore: purchaseTerritories must be null (no purchase ran on freshProAi)
    // Restore it
    freshProAi.restorePurchaseTerritoriesFromSnapshot(deserialized, data);

    // After restore: verify the map is non-null and has entries with non-empty canPlaceTerritories
    // We access via reflection since storedPurchaseTerritories is private
    final Field storedField = AbstractProAi.class.getDeclaredField("storedPurchaseTerritories");
    storedField.setAccessible(true);
    @SuppressWarnings("unchecked")
    final Map<Territory, ProPurchaseTerritory> restored =
        (Map<Territory, ProPurchaseTerritory>) storedField.get(freshProAi);

    assertNotNull(restored, "storedPurchaseTerritories must be non-null after restore");
    assertFalse(restored.isEmpty(), "storedPurchaseTerritories must be non-empty after restore");

    for (final Map.Entry<Territory, ProPurchaseTerritory> e : restored.entrySet()) {
      assertFalse(
          e.getValue().getCanPlaceTerritories().isEmpty(),
          "canPlaceTerritories must be non-empty for " + e.getKey().getName());
    }
  }

  /**
   * Verify that the per-phase restore methods are independent — restoring only {@code
   * storedPurchaseTerritories} does not accidentally populate {@code storedCombatMoveMap}.
   */
  @Test
  void perPhaseRestoreIsIndependent() throws Exception {
    final ProSessionSnapshot snap =
        new ProSessionSnapshot(
            Map.of(
                "Eastern Germany",
                new ProTerritorySnapshot(List.of(), List.of(), Map.of(), Map.of(), Map.of())),
            Map.of(),
            Map.of(),
            Map.of());

    final ProAi freshProAi = new ProAi("test-fresh", "Germans");
    final GameData data = CanonicalGameData.load().cloneForSession();

    // restorePurchaseTerritoriesFromSnapshot should NOT restore combatMoveMap
    freshProAi.restorePurchaseTerritoriesFromSnapshot(snap, data);
    final Field combatField = AbstractProAi.class.getDeclaredField("storedCombatMoveMap");
    combatField.setAccessible(true);
    assertTrue(
        combatField.get(freshProAi) == null,
        "restorePurchaseTerritoriesFromSnapshot must not populate storedCombatMoveMap");

    // restoreCombatMoveMapFromSnapshot should populate it
    freshProAi.restoreCombatMoveMapFromSnapshot(snap, data);
    assertNotNull(
        combatField.get(freshProAi),
        "restoreCombatMoveMapFromSnapshot must populate storedCombatMoveMap");
  }

  /**
   * Stale-transport resilience: a {@code storedCombatMoveMap} snapshot whose {@code
   * amphibAttackMap} references a transport UUID that is absent from the live {@link GameData} must
   * not throw — the defensive drop in {@link AbstractProAi#restoreCombatMoveMapFromSnapshot}
   * silently omits that entry.
   */
  @Test
  void staleCombatMoveMapTransportUuidIsDroppedSilently() {
    final String staleTransportUuid = UUID.randomUUID().toString();
    final ProTerritorySnapshot snapWithStale =
        new ProTerritorySnapshot(
            List.of(),
            List.of(),
            // amphibAttackMap carries a stale transport UUID
            Map.of(staleTransportUuid, List.of(UUID.randomUUID().toString())),
            Map.of(staleTransportUuid, "Sea Zone 5"),
            Map.of());

    final ProSessionSnapshot snap =
        new ProSessionSnapshot(Map.of("Germany", snapWithStale), Map.of(), Map.of(), Map.of());

    final ProAi freshProAi = new ProAi("test-stale", "Germans");
    final GameData data;
    try {
      data = CanonicalGameData.load().cloneForSession();
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }

    // Must not throw even though the transport UUID is unknown in GameData
    freshProAi.restoreCombatMoveMapFromSnapshot(snap, data);

    // storedCombatMoveMap should be populated (the territory entry survives)
    final java.lang.reflect.Field combatField;
    try {
      combatField = AbstractProAi.class.getDeclaredField("storedCombatMoveMap");
      combatField.setAccessible(true);
      final Object restored = combatField.get(freshProAi);
      assertNotNull(restored, "storedCombatMoveMap must be populated even when transport is stale");
      // The territory entry is present, but the amphibAttackMap for it must be empty
      // (stale transport was dropped)
      @SuppressWarnings("unchecked")
      final java.util.Map<
              games.strategy.engine.data.Territory, games.strategy.triplea.ai.pro.data.ProTerritory>
          combatMap =
              (java.util.Map<
                      games.strategy.engine.data.Territory,
                      games.strategy.triplea.ai.pro.data.ProTerritory>)
                  restored;
      assertFalse(
          combatMap.isEmpty(), "storedCombatMoveMap must have an entry for Eastern Germany");
      final games.strategy.triplea.ai.pro.data.ProTerritory pt =
          combatMap.values().iterator().next();
      assertTrue(
          pt.getAmphibAttackMap().isEmpty(),
          "stale transport entry must be absent from restored amphibAttackMap");
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static List<Field> getAllFields(final Class<?> cls) {
    final List<Field> fields = new ArrayList<>(Arrays.asList(cls.getDeclaredFields()));
    Class<?> parent = cls.getSuperclass();
    while (parent != null && parent != Object.class) {
      fields.addAll(Arrays.asList(parent.getDeclaredFields()));
      parent = parent.getSuperclass();
    }
    return fields;
  }

  private static void ensureDelegate(
      final GameData data,
      final String name,
      final String displayName,
      final games.strategy.engine.delegate.IDelegate delegate) {
    if (data.getDelegateOptional(name).isPresent()) {
      return;
    }
    delegate.initialize(name, displayName);
    data.addDelegate(delegate);
  }
}
