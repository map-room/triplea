package org.triplea.ai.sidecar.wire;

import java.util.List;

public record WireTerritory(String territoryId, String owner, List<WireUnit> units) {}
