package org.devolia.kcvault.resilience;

import static org.junit.jupiter.api.Assertions.*;

import com.azure.core.exception.ClientAuthenticationException;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.exception.ResourceNotFoundException;
import com.azure.core.http.HttpResponse;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for ExceptionClassifier.
 *
 * @author Devolia
 * @since 1.0.0
 */
class ExceptionClassifierTest {

  @Test
  void testTransientFailures() {
    // Network and timeout issues
    assertTrue(ExceptionClassifier.isTransientFailure(new SocketTimeoutException("Timeout")));
    assertTrue(ExceptionClassifier.isTransientFailure(new TimeoutException("Timeout")));
    assertTrue(ExceptionClassifier.isTransientFailure(new UnknownHostException("Host not found")));
    assertTrue(ExceptionClassifier.isTransientFailure(new SSLException("SSL error")));

    // HTTP transient errors
    assertTrue(
        ExceptionClassifier.isTransientFailure(createHttpException(429))); // Too Many Requests
    assertTrue(
        ExceptionClassifier.isTransientFailure(createHttpException(503))); // Service Unavailable
    assertTrue(
        ExceptionClassifier.isTransientFailure(createHttpException(500))); // Internal Server Error
    assertTrue(ExceptionClassifier.isTransientFailure(createHttpException(502))); // Bad Gateway

    // Non-transient should return false
    assertFalse(ExceptionClassifier.isTransientFailure(createHttpException(400))); // Bad Request
    assertFalse(ExceptionClassifier.isTransientFailure(createHttpException(401))); // Unauthorized
    assertFalse(ExceptionClassifier.isTransientFailure(createHttpException(403))); // Forbidden
    assertFalse(ExceptionClassifier.isTransientFailure(createHttpException(404))); // Not Found
    assertFalse(ExceptionClassifier.isTransientFailure(createResourceNotFoundException()));

    // Create a proper ClientAuthenticationException with response
    HttpResponse authResponse = Mockito.mock(HttpResponse.class);
    Mockito.when(authResponse.getStatusCode()).thenReturn(401);
    assertFalse(
        ExceptionClassifier.isTransientFailure(
            new ClientAuthenticationException("Auth failed", authResponse)));
  }

  @Test
  void testPermanentFailures() {
    // Authentication and authorization failures
    HttpResponse authResponse2 = Mockito.mock(HttpResponse.class);
    Mockito.when(authResponse2.getStatusCode()).thenReturn(401);
    assertTrue(
        ExceptionClassifier.isPermanentFailure(
            new ClientAuthenticationException("Auth failed", authResponse2)));
    assertTrue(ExceptionClassifier.isPermanentFailure(createResourceNotFoundException()));

    // HTTP permanent errors
    assertTrue(ExceptionClassifier.isPermanentFailure(createHttpException(400))); // Bad Request
    assertTrue(ExceptionClassifier.isPermanentFailure(createHttpException(401))); // Unauthorized
    assertTrue(ExceptionClassifier.isPermanentFailure(createHttpException(403))); // Forbidden
    assertTrue(ExceptionClassifier.isPermanentFailure(createHttpException(404))); // Not Found

    // Non-permanent should return false
    assertFalse(
        ExceptionClassifier.isPermanentFailure(createHttpException(429))); // Too Many Requests
    assertFalse(
        ExceptionClassifier.isPermanentFailure(createHttpException(503))); // Service Unavailable
    assertFalse(
        ExceptionClassifier.isPermanentFailure(createHttpException(500))); // Internal Server Error
    assertFalse(ExceptionClassifier.isPermanentFailure(new SocketTimeoutException("Timeout")));
    assertFalse(ExceptionClassifier.isPermanentFailure(new RuntimeException("Generic error")));
  }

  @Test
  void testCircuitBreakerFailures() {
    // Should count as circuit breaker failures
    assertTrue(ExceptionClassifier.isCircuitBreakerFailure(createHttpException(500)));
    assertTrue(ExceptionClassifier.isCircuitBreakerFailure(createHttpException(503)));
    assertTrue(ExceptionClassifier.isCircuitBreakerFailure(createHttpException(429)));
    assertTrue(ExceptionClassifier.isCircuitBreakerFailure(createHttpException(400)));
    assertTrue(ExceptionClassifier.isCircuitBreakerFailure(new RuntimeException("Generic error")));
    assertTrue(ExceptionClassifier.isCircuitBreakerFailure(new SocketTimeoutException("Timeout")));

    // Should NOT count as circuit breaker failures
    assertFalse(ExceptionClassifier.isCircuitBreakerFailure(createResourceNotFoundException()));
    assertFalse(ExceptionClassifier.isCircuitBreakerFailure(createHttpException(404)));
    assertFalse(ExceptionClassifier.isCircuitBreakerFailure(createHttpException(401)));
    assertFalse(ExceptionClassifier.isCircuitBreakerFailure(createHttpException(403)));

    // Create a proper ClientAuthenticationException with response
    HttpResponse authResponse = Mockito.mock(HttpResponse.class);
    Mockito.when(authResponse.getStatusCode()).thenReturn(401);
    assertFalse(
        ExceptionClassifier.isCircuitBreakerFailure(
            new ClientAuthenticationException("Auth failed", authResponse)));
  }

  @Test
  void testErrorCategories() {
    assertEquals(
        "not_found", ExceptionClassifier.getErrorCategory(createResourceNotFoundException()));

    HttpResponse authResponse3 = Mockito.mock(HttpResponse.class);
    Mockito.when(authResponse3.getStatusCode()).thenReturn(401);
    assertEquals(
        "authentication",
        ExceptionClassifier.getErrorCategory(
            new ClientAuthenticationException("Auth failed", authResponse3)));
    assertEquals(
        "timeout", ExceptionClassifier.getErrorCategory(new SocketTimeoutException("Timeout")));
    assertEquals("timeout", ExceptionClassifier.getErrorCategory(new TimeoutException("Timeout")));
    assertEquals(
        "network",
        ExceptionClassifier.getErrorCategory(new UnknownHostException("Host not found")));
    assertEquals("ssl", ExceptionClassifier.getErrorCategory(new SSLException("SSL error")));
    assertEquals(
        "unknown", ExceptionClassifier.getErrorCategory(new RuntimeException("Generic error")));

    // HTTP status code categories
    assertEquals("bad_request", ExceptionClassifier.getErrorCategory(createHttpException(400)));
    assertEquals("unauthorized", ExceptionClassifier.getErrorCategory(createHttpException(401)));
    assertEquals("forbidden", ExceptionClassifier.getErrorCategory(createHttpException(403)));
    assertEquals("not_found", ExceptionClassifier.getErrorCategory(createHttpException(404)));
    assertEquals("rate_limited", ExceptionClassifier.getErrorCategory(createHttpException(429)));
    assertEquals(
        "service_unavailable", ExceptionClassifier.getErrorCategory(createHttpException(503)));
    assertEquals("server_error", ExceptionClassifier.getErrorCategory(createHttpException(500)));
    assertEquals(
        "client_error",
        ExceptionClassifier.getErrorCategory(createHttpException(418))); // I'm a teapot
  }

  @Test
  void testNullExceptions() {
    assertFalse(ExceptionClassifier.isTransientFailure(null));
    assertFalse(ExceptionClassifier.isPermanentFailure(null));
    assertFalse(ExceptionClassifier.isCircuitBreakerFailure(null));
    assertEquals("unknown", ExceptionClassifier.getErrorCategory(null));
  }

  @Test
  void testNestedExceptions() {
    // Test that nested causes are checked
    RuntimeException wrapper =
        new RuntimeException("Wrapper", new SocketTimeoutException("Nested timeout"));
    assertTrue(ExceptionClassifier.isTransientFailure(wrapper));
    assertEquals("timeout", ExceptionClassifier.getErrorCategory(wrapper.getCause()));

    HttpResponse authResponse4 = Mockito.mock(HttpResponse.class);
    Mockito.when(authResponse4.getStatusCode()).thenReturn(401);
    RuntimeException permanentWrapper =
        new RuntimeException(
            "Wrapper", new ClientAuthenticationException("Nested auth", authResponse4));
    assertTrue(ExceptionClassifier.isPermanentFailure(permanentWrapper));
  }

  private HttpResponseException createHttpException(int statusCode) {
    HttpResponse httpResponse = Mockito.mock(HttpResponse.class);
    Mockito.when(httpResponse.getStatusCode()).thenReturn(statusCode);
    return new HttpResponseException("HTTP error", httpResponse);
  }

  private ResourceNotFoundException createResourceNotFoundException() {
    HttpResponse httpResponse = Mockito.mock(HttpResponse.class);
    Mockito.when(httpResponse.getStatusCode()).thenReturn(404);
    return new ResourceNotFoundException("Not found", httpResponse);
  }
}
