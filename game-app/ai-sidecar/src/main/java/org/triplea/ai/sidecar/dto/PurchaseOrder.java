package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One entry in a purchase plan: {@code count} units of {@code unitType} to place at
 * {@code placeTerritory} (nullable in Phase 3 — Phase 5 will populate it). */
public record PurchaseOrder(String unitType, int count, @JsonInclude(JsonInclude.Include.NON_NULL) String placeTerritory) {}
