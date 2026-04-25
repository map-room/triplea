package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Sidecar's planned war declarations for a given player's politics window. */
public record PoliticsPlan(List<WarDeclaration> declarations) implements DecisionPlan {

  @JsonCreator
  public PoliticsPlan(@JsonProperty("declarations") final List<WarDeclaration> declarations) {
    this.declarations = declarations == null ? List.of() : declarations;
  }

  public String kind() {
    return "politics";
  }
}
