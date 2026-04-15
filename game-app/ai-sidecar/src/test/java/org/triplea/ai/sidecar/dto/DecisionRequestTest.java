package org.triplea.ai.sidecar.dto;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DecisionRequestTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void deserializesSelectCasualtiesVariant() throws Exception {
    String json = """
      {
        "kind": "select-casualties",
        "state": { "territories": [], "players": [], "round": 1, "phase": "combat", "currentPlayer": "Germans" },
        "battle": {
          "battleId": "b1", "territory": "Egypt",
          "attackerNation": "British", "defenderNation": "Germans",
          "hitCount": 2, "selectFrom": [], "friendlyUnits": [], "enemyUnits": [],
          "isAmphibious": false, "amphibiousLandAttackers": [],
          "defaultCasualties": ["u1","u2"], "allowMultipleHitsPerUnit": false
        }
      }""";
    DecisionRequest req = mapper.readValue(json, DecisionRequest.class);
    assertThat(req).isInstanceOf(SelectCasualtiesRequest.class);
    assertThat(((SelectCasualtiesRequest) req).battle().defaultCasualties()).containsExactly("u1", "u2");
  }

  @Test
  void deserializesRetreatVariant() throws Exception {
    String json = """
      {
        "kind": "retreat-or-press",
        "state": { "territories": [], "players": [], "round": 1, "phase": "combat", "currentPlayer": "Germans" },
        "battle": {
          "battleId": "b1", "battleTerritory": "Egypt",
          "canSubmerge": false, "possibleRetreatTerritories": ["Libya"]
        }
      }""";
    DecisionRequest req = mapper.readValue(json, DecisionRequest.class);
    assertThat(req).isInstanceOf(RetreatQueryRequest.class);
  }

  @Test
  void deserializesScrambleVariant() throws Exception {
    String json = """
      {
        "kind": "scramble",
        "state": { "territories": [], "players": [], "round": 1, "phase": "combat", "currentPlayer": "Germans" },
        "battle": {
          "defendingTerritory": "110 Sea Zone",
          "possibleScramblers": {}
        }
      }""";
    DecisionRequest req = mapper.readValue(json, DecisionRequest.class);
    assertThat(req).isInstanceOf(ScrambleRequest.class);
  }
}
