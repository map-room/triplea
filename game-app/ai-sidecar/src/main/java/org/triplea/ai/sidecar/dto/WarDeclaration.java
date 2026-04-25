package org.triplea.ai.sidecar.dto;

/**
 * A single war-declaration intent extracted from a politics step. The acting player declares war on
 * {@code target}; the engine's declareWar move handler runs all cascade rules downstream.
 */
public record WarDeclaration(String target) {}
