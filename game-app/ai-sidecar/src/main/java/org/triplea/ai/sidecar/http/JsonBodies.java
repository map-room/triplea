package org.triplea.ai.sidecar.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.triplea.ai.sidecar.dto.DecisionError;
import org.triplea.ai.sidecar.dto.DecisionPlan;
import org.triplea.ai.sidecar.dto.DecisionReady;

public final class JsonBodies {
  public static final ObjectMapper MAPPER = new ObjectMapper();

  private JsonBodies() {}

  public static <T> T readValue(final String body, final Class<T> type) throws IOException {
    return MAPPER.readValue(body, type);
  }

  public static String writeValue(final Object value) throws JsonProcessingException {
    return MAPPER.writeValueAsString(value);
  }

  /**
   * Legacy error body used by non-decision handlers (SessionCreateHandler,
   * SessionLifecycleHandler). Emits {@code {"error":"<code>","message":"<msg>"}}.
   *
   * <p>For the decision endpoint use {@link #errorBody(String)} or {@link
   * #errorBodyWithKind(String, String)} which emit the TS-contract status envelope.
   */
  public static String errorBody(final String error, final String message)
      throws JsonProcessingException {
    final com.fasterxml.jackson.databind.node.ObjectNode n = MAPPER.createObjectNode();
    n.put("error", error);
    n.put("message", message);
    return MAPPER.writeValueAsString(n);
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
