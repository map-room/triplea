package org.triplea.ai.sidecar.dto;

/**
 * One repair order: fix {@code repairCount} damage on a factory (or other damageable infra) of
 * {@code unitType} in {@code territory}.
 */
public record RepairOrder(String territory, String unitType, int repairCount) {}
