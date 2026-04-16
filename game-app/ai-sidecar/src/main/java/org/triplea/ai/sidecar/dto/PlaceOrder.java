package org.triplea.ai.sidecar.dto;

import java.util.List;

/**
 * A single placement instruction: place the given unit types in the given territory.
 *
 * <p>Unit types are strings (e.g. {@code "infantry"}, {@code "armour"}) — one entry per unit,
 * matching the TripleA {@link games.strategy.engine.data.UnitType#getName()} of each placed unit.
 */
public record PlaceOrder(String territoryName, List<String> unitTypes) {}
