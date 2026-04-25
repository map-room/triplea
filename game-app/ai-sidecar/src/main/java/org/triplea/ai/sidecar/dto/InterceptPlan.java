package org.triplea.ai.sidecar.dto;

import java.util.List;

public record InterceptPlan(List<String> interceptorIds) implements DecisionPlan {}
