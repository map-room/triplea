package org.triplea.ai.sidecar.dto;

import java.util.List;

public record SelectCasualtiesPlan(List<String> killed, List<String> damaged)
    implements DecisionPlan {}
