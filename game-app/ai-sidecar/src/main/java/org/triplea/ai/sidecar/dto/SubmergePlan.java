package org.triplea.ai.sidecar.dto;

import java.util.List;

public record SubmergePlan(List<String> submerge) implements DecisionPlan {}
