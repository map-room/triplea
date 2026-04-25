package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record RetreatPlan(@JsonInclude(JsonInclude.Include.ALWAYS) String retreatTo)
    implements DecisionPlan {}
