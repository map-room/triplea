package org.triplea.ai.sidecar.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.strategy.triplea.ai.pro.Snapshotted;
import games.strategy.triplea.ai.pro.data.PlaceTerritorySnapshot;
import games.strategy.triplea.ai.pro.data.ProPurchaseTerritory;
import games.strategy.triplea.ai.pro.data.ProSessionSnapshot;
import games.strategy.triplea.ai.pro.data.ProTerritorySnapshot;
import games.strategy.triplea.ai.pro.data.ProPlaceTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.PurchaseTerritorySnapshot;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every {@link Snapshotted}-annotated field in the ProAi data classes is present as
 * an accessor in the corresponding snapshot DTO, and that a JSON round-trip via Jackson preserves
 * all snapshot values.
 *
 * <p>The test is intentionally reflection-based: it will fail at compile time if a field is
 * removed or renamed, prompting the developer to update the snapshot DTO.
 */
class ProSessionSnapshotRoundTripTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

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
    assertEquals(8, snapshottedFields.size(),
        "Expected exactly 8 @Snapshotted fields; found: " + snapshottedFields.stream()
            .map(f -> f.getDeclaringClass().getSimpleName() + "." + f.getName())
            .toList());
  }

  @Test
  void snapshotDtosRoundTripThroughJackson() throws Exception {
    // Build a non-trivial snapshot with non-empty maps
    final ProTerritorySnapshot territorySnap = new ProTerritorySnapshot(
        List.of("aaaa-bbbb-cccc-dddd"),
        List.of("eeee-ffff-0000-1111"),
        Map.of("transport-uuid", List.of("carried-uuid-1", "carried-uuid-2")),
        Map.of("transport-uuid", "Sea Zone 5"),
        Map.of("bombard-uuid", "Western Germany"));

    final PlaceTerritorySnapshot placeSnap =
        new PlaceTerritorySnapshot("Western Germany", List.of("infantry", "artillery"));

    final PurchaseTerritorySnapshot purchaseSnap =
        new PurchaseTerritorySnapshot(List.of(placeSnap));

    final ProSessionSnapshot original = new ProSessionSnapshot(
        Map.of("Eastern Germany", territorySnap),
        Map.of("France", territorySnap),
        Map.of("Germany", purchaseSnap));

    final String json = MAPPER.writeValueAsString(original);
    final ProSessionSnapshot restored = MAPPER.readValue(json, ProSessionSnapshot.class);

    assertNotNull(restored);

    // combatMoveMap
    assertEquals(1, restored.combatMoveMap().size());
    final ProTerritorySnapshot restoredTerrSnap =
        restored.combatMoveMap().get("Eastern Germany");
    assertNotNull(restoredTerrSnap);
    assertEquals(List.of("aaaa-bbbb-cccc-dddd"), restoredTerrSnap.unitIds());
    assertEquals(List.of("eeee-ffff-0000-1111"), restoredTerrSnap.bomberIds());
    assertEquals(
        List.of("carried-uuid-1", "carried-uuid-2"),
        restoredTerrSnap.amphibAttackMap().get("transport-uuid"));
    assertEquals("Sea Zone 5", restoredTerrSnap.transportTerritoryMap().get("transport-uuid"));
    assertEquals("Western Germany", restoredTerrSnap.bombardTerritoryMap().get("bombard-uuid"));

    // factoryMoveMap
    assertEquals(1, restored.factoryMoveMap().size());

    // purchaseTerritories
    assertEquals(1, restored.purchaseTerritories().size());
    final PurchaseTerritorySnapshot restoredPurchSnap =
        restored.purchaseTerritories().get("Germany");
    assertNotNull(restoredPurchSnap);
    assertEquals(1, restoredPurchSnap.canPlaceTerritories().size());
    final PlaceTerritorySnapshot restoredPlace =
        restoredPurchSnap.canPlaceTerritories().get(0);
    assertEquals("Western Germany", restoredPlace.territoryName());
    assertEquals(List.of("infantry", "artillery"), restoredPlace.placeUnitTypes());
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
}
