package org.triplea.ai.sidecar.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

public final class FakeHttpExchange extends HttpExchange {
  private final String method;
  private final URI uri;
  private final Headers requestHeaders = new Headers();
  private final Headers responseHeaders = new Headers();
  private final ByteArrayInputStream requestBody;
  private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
  private int responseCode = -1;

  public FakeHttpExchange(final String method, final String path, final String body) {
    this.method = method;
    this.uri = URI.create(path);
    this.requestBody = new ByteArrayInputStream(body == null ? new byte[0] : body.getBytes());
  }

  public int responseCode() {
    return responseCode;
  }

  public String responseBodyString() {
    return responseBody.toString();
  }

  @Override
  public Headers getRequestHeaders() {
    return requestHeaders;
  }

  @Override
  public Headers getResponseHeaders() {
    return responseHeaders;
  }

  @Override
  public URI getRequestURI() {
    return uri;
  }

  @Override
  public String getRequestMethod() {
    return method;
  }

  @Override
  public HttpContext getHttpContext() {
    return null;
  }

  @Override
  public void close() {}

  @Override
  public InputStream getRequestBody() {
    return requestBody;
  }

  @Override
  public OutputStream getResponseBody() {
    return responseBody;
  }

  @Override
  public void sendResponseHeaders(final int code, final long length) {
    this.responseCode = code;
  }

  @Override
  public InetSocketAddress getRemoteAddress() {
    return null;
  }

  @Override
  public int getResponseCode() {
    return responseCode;
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
}
