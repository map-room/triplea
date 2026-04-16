package org.triplea.ai.sidecar.dto;

import java.util.List;

/**
 * A single unit-movement order projected from a TripleA {@code MoveDescription}.
 *
 * <p>{@code unitIds} are Map Room wire IDs (the string keys from the wire state), not Java UUIDs.
 * {@code from} and {@code to} are territory names as they appear in the canonical map.
 */
public record CombatMoveOrder(List<String> unitIds, String from, String to) {}
