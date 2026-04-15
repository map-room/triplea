package org.triplea.ai.sidecar.dto;

import java.util.List;
import java.util.Map;

public record ScramblePlan(Map<String, List<String>> scramblers) implements DecisionPlan {}
