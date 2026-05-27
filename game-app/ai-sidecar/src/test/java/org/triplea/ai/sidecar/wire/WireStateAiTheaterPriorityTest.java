package org.triplea.ai.sidecar.wire;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Wire round-trip coverage for the SVF PR-B {@code aiTheaterPriority} field (map-room#2755) and for
 * the {@code setupVariant} pass-through that was half-shipped in Phase 0 and closed here.
 */
class WireStateAiTheaterPriorityTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private static final String BASE_FIELDS =
      "\"territories\":[],\"players\":[],\"round\":1,\"phase\":\"purchase\","
          + "\"currentPlayer\":\"Americans\",\"relationships\":[]";

  @Test
  void aiTheaterPriority_absent_deserializesToNull() throws Exception {
    final String json = "{" + BASE_FIELDS + "}";
    final WireState w = mapper.readValue(json, WireState.class);
    assertThat(w.aiTheaterPriority()).isNull();
  }

  @Test
  void aiTheaterPriority_kgf_roundTrips() throws Exception {
    final String json = "{" + BASE_FIELDS + ",\"aiTheaterPriority\":\"KGF\"}";
    final WireState w = mapper.readValue(json, WireState.class);
    assertThat(w.aiTheaterPriority()).isEqualTo("KGF");
  }

  @Test
  void aiTheaterPriority_kjf_roundTrips() throws Exception {
    final String json = "{" + BASE_FIELDS + ",\"aiTheaterPriority\":\"KJF\"}";
    final WireState w = mapper.readValue(json, WireState.class);
    assertThat(w.aiTheaterPriority()).isEqualTo("KJF");
  }

  @Test
  void setupVariant_gapClosed_absent_deserializesToNull() throws Exception {
    // Phase 0 commit 9643bdb5 added setupVariant to the TS wire but left Java incomplete.
    // PR-B closes the gap: the field is now on the Java record and absent-on-read = null.
    final String json = "{" + BASE_FIELDS + "}";
    final WireState w = mapper.readValue(json, WireState.class);
    assertThat(w.setupVariant()).isNull();
  }

  @Test
  void setupVariant_1942_roundTrips() throws Exception {
    final String json = "{" + BASE_FIELDS + ",\"setupVariant\":\"1942\"}";
    final WireState w = mapper.readValue(json, WireState.class);
    assertThat(w.setupVariant()).isEqualTo("1942");
  }

  @Test
  void bothNewFields_present_independent() throws Exception {
    final String json =
        "{" + BASE_FIELDS + ",\"setupVariant\":\"1940\",\"aiTheaterPriority\":\"KJF\"}";
    final WireState w = mapper.readValue(json, WireState.class);
    assertThat(w.setupVariant()).isEqualTo("1940");
    assertThat(w.aiTheaterPriority()).isEqualTo("KJF");
  }

  @Test
  void backwardsCompat_relationshipsAbsent_yieldsEmptyList_existingBehaviorPreserved()
      throws Exception {
    // Sanity check that adding the two new fields didn't regress the existing
    // "relationships absent = empty list" guarantee.
    final String json =
        "{\"territories\":[],\"players\":[],\"round\":1,\"phase\":\"purchase\","
            + "\"currentPlayer\":\"Americans\"}";
    final WireState w = mapper.readValue(json, WireState.class);
    assertThat(w.relationships()).isEmpty();
    assertThat(w.setupVariant()).isNull();
    assertThat(w.aiTheaterPriority()).isNull();
  }
}
