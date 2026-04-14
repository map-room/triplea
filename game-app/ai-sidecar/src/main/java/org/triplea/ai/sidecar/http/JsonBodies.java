package org.triplea.ai.sidecar.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

public final class JsonBodies {
  public static final ObjectMapper MAPPER = new ObjectMapper();

  private JsonBodies() {}

  public static <T> T readValue(final String body, final Class<T> type) throws IOException {
    return MAPPER.readValue(body, type);
  }

  public static String writeValue(final Object value) throws JsonProcessingException {
    return MAPPER.writeValueAsString(value);
  }

  public static String errorBody(final String error, final String message)
      throws JsonProcessingException {
    final ObjectNode n = MAPPER.createObjectNode();
    n.put("error", error);
    n.put("message", message);
    return MAPPER.writeValueAsString(n);
  }
}
