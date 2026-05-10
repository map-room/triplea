package org.triplea.ai.sidecar.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.triplea.ai.sidecar.dto.DecisionError;
import org.triplea.ai.sidecar.dto.DecisionPlan;
import org.triplea.ai.sidecar.dto.DecisionReady;

public final class JsonBodies {
  /**
   * Wire-surface ObjectMapper. Configured with {@code FAIL_ON_UNKNOWN_PROPERTIES=false} so additive
   * TS-side wire-schema changes (new optional fields on {@code WirePlayer}, {@code WireUnit}, etc.)
   * deserialize cleanly even before the Java POJO catches up.
   *
   * <p>This addresses the class of bug from map-room#2301 / map-room#2305: a TS-side wire emit of a
   * previously-unknown field caused Jackson to throw {@code UnrecognizedPropertyException}, which
   * surfaced as opaque {@code 400 bad-request} on the decision endpoint and the bot mishandled by
   * falling back to {@code skip*} moves with empty plans — silently burning AI turns.
   */
  public static final ObjectMapper MAPPER =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private JsonBodies() {}

  public static <T> T readValue(final String body, final Class<T> type) throws IOException {
    return MAPPER.readValue(body, type);
  }

  public static String writeValue(final Object value) throws JsonProcessingException {
    return MAPPER.writeValueAsString(value);
  }

  /** Wraps a successful plan in the {@code {"status":"ready","plan":{...}}} envelope. */
  public static String readyBody(final DecisionPlan plan) throws JsonProcessingException {
    return MAPPER.writeValueAsString(new DecisionReady(plan));
  }

  /**
   * Emits the error envelope {@code {"status":"error","error":"<code>"}}.
   *
   * <p>An optional {@code kind} field is included when non-null (diagnostic aid for 501
   * offensive-kind responses).
   */
  public static String errorBody(final String errorCode) throws JsonProcessingException {
    return MAPPER.writeValueAsString(new DecisionError(errorCode));
  }

  /**
   * Emits the error envelope with an additional {@code kind} field for 501 offensive responses.
   *
   * <p>Wire shape: {@code {"status":"error","error":"not-implemented","kind":"<kind>"}}
   */
  public static String errorBodyWithKind(final String errorCode, final String kind)
      throws JsonProcessingException {
    final com.fasterxml.jackson.databind.node.ObjectNode n = MAPPER.createObjectNode();
    n.put("status", "error");
    n.put("error", errorCode);
    n.put("kind", kind);
    return MAPPER.writeValueAsString(n);
  }
}
