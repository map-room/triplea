package org.triplea.ai.sidecar.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;

class HealthHandlerTest {
  @Test
  void returns200WithOkStatus() throws Exception {
    final HealthHandler h = new HealthHandler();
    final FakeHttpExchange ex = new FakeHttpExchange("GET", "/health", null);
    h.handle(ex);
    assertEquals(200, ex.responseCode());
    assertTrue(ex.responseBodyString().contains("\"status\":\"ok\""));
  }

  @Test
  void rejectsNonGet() throws Exception {
    final HealthHandler h = new HealthHandler();
    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/health", null);
    h.handle(ex);
    assertEquals(405, ex.responseCode());
  }

  @Test
  void swallowsBrokenPipeWithoutRethrow() {
    final HealthHandler h = new HealthHandler();
    final HttpExchange ex = throwingGetExchange("Broken pipe");
    assertDoesNotThrow(() -> h.handle(ex));
  }

  @Test
  void rethrowsUnexpectedIoException() {
    final HealthHandler h = new HealthHandler();
    final HttpExchange ex = throwingGetExchange("Disk full");
    assertThrows(IOException.class, () -> h.handle(ex));
  }

  /** Returns a minimal GET /health exchange whose response OutputStream throws on write. */
  private static HttpExchange throwingGetExchange(final String errorMessage) {
    return new HttpExchange() {
      private final Headers responseHeaders = new Headers();

      @Override
      public String getRequestMethod() {
        return "GET";
      }

      @Override
      public URI getRequestURI() {
        return URI.create("/health");
      }

      @Override
      public Headers getRequestHeaders() {
        return new Headers();
      }

      @Override
      public Headers getResponseHeaders() {
        return responseHeaders;
      }

      @Override
      public void sendResponseHeaders(final int code, final long length) {}

      @Override
      public OutputStream getResponseBody() {
        return new OutputStream() {
          @Override
          public void write(final int b) throws IOException {
            throw new IOException(errorMessage);
          }

          @Override
          public void write(final byte[] b) throws IOException {
            throw new IOException(errorMessage);
          }
        };
      }

      @Override
      public InputStream getRequestBody() {
        return new ByteArrayInputStream(new byte[0]);
      }

      @Override
      public HttpContext getHttpContext() {
        return null;
      }

      @Override
      public void close() {}

      @Override
      public InetSocketAddress getRemoteAddress() {
        return null;
      }

      @Override
      public int getResponseCode() {
        return -1;
      }

      @Override
      public InetSocketAddress getLocalAddress() {
        return null;
      }

      @Override
      public String getProtocol() {
        return "HTTP/1.1";
      }

      @Override
      public Object getAttribute(final String name) {
        return null;
      }

      @Override
      public void setAttribute(final String name, final Object value) {}

      @Override
      public void setStreams(final InputStream i, final OutputStream o) {}

      @Override
      public HttpPrincipal getPrincipal() {
        return null;
      }
    };
  }
}
