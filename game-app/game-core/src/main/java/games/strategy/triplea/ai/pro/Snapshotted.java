package games.strategy.triplea.ai.pro;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for ProAi fields that must survive a sidecar snapshot round-trip.
 *
 * <p>Fields annotated with {@code @Snapshotted} are projected into {@link
 * games.strategy.triplea.ai.pro.data.ProSessionSnapshot} DTOs by {@code
 * AbstractProAi.snapshotForSidecar()} and restored by {@code
 * AbstractProAi.restoreFromSnapshot(ProSessionSnapshot, GameData)}.
 *
 * <p>The {@code ProSessionSnapshotRoundTripTest} in the sidecar module uses reflection to verify
 * that every annotated field survives a serialize → deserialize → restore cycle.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Snapshotted {}
