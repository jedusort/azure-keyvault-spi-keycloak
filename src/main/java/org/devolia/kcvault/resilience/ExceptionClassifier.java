package org.devolia.kcvault.resilience;

import com.azure.core.exception.ClientAuthenticationException;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.exception.ResourceNotFoundException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for classifying exceptions into categories for resilience patterns.
 *
 * <p>This class helps determine whether an exception should trigger:
 *
 * <ul>
 *   <li>Retry logic (transient failures)
 *   <li>Circuit breaker response (repeated failures)
 *   <li>Immediate failure (permanent errors)
 * </ul>
 *
 * @author Devolia
 * @since 1.0.0
 */
public class ExceptionClassifier {

  private static final Logger logger = LoggerFactory.getLogger(ExceptionClassifier.class);

  /**
   * Determines if an exception represents a transient failure that should be retried.
   *
   * <p>Transient failures include:
   *
   * <ul>
   *   <li>Network timeouts and connection issues
   *   <li>HTTP 429 (Too Many Requests) - rate limiting
   *   <li>HTTP 503 (Service Unavailable) - temporary service issues
   *   <li>SSL handshake failures
   * </ul>
   *
   * @param exception the exception to classify
   * @return true if the exception represents a transient failure
   */
  public static boolean isTransientFailure(Throwable exception) {
    if (exception == null) {
      return false;
    }

    // Network and timeout issues
    if (exception instanceof SocketTimeoutException
        || exception instanceof TimeoutException
        || exception instanceof UnknownHostException
        || exception instanceof SSLException) {
      logger.debug("Classified as transient failure: {}", exception.getClass().getSimpleName());
      return true;
    }

    // Azure HTTP response exceptions
    if (exception instanceof HttpResponseException httpException) {
      int statusCode = httpException.getResponse().getStatusCode();
      boolean isTransient = statusCode == 429 || statusCode == 503 || statusCode >= 500;
      logger.debug(
          "HTTP exception classified as {}: status={}",
          isTransient ? "transient" : "permanent",
          statusCode);
      return isTransient;
    }

    // Check nested causes
    Throwable cause = exception.getCause();
    if (cause != null && cause != exception) {
      return isTransientFailure(cause);
    }

    logger.debug("Classified as permanent failure: {}", exception.getClass().getSimpleName());
    return false;
  }

  /**
   * Determines if an exception represents a permanent failure that should not be retried.
   *
   * <p>Permanent failures include:
   *
   * <ul>
   *   <li>Authentication and authorization failures (401, 403)
   *   <li>Resource not found (404)
   *   <li>Client configuration errors (400, invalid vault name, etc.)
   *   <li>Authentication credential issues
   * </ul>
   *
   * @param exception the exception to classify
   * @return true if the exception represents a permanent failure
   */
  public static boolean isPermanentFailure(Throwable exception) {
    if (exception == null) {
      return false;
    }

    // Authentication failures
    if (exception instanceof ClientAuthenticationException) {
      logger.debug("Classified as permanent failure: authentication error");
      return true;
    }

    // Resource not found - permanent for secret retrieval context
    if (exception instanceof ResourceNotFoundException) {
      logger.debug("Classified as permanent failure: resource not found");
      return true;
    }

    // Azure HTTP response exceptions
    if (exception instanceof HttpResponseException httpException) {
      int statusCode = httpException.getResponse().getStatusCode();
      boolean isPermanent =
          statusCode == 400 || statusCode == 401 || statusCode == 403 || statusCode == 404;
      logger.debug(
          "HTTP exception classified as {}: status={}",
          isPermanent ? "permanent" : "transient",
          statusCode);
      return isPermanent;
    }

    // Check nested causes
    Throwable cause = exception.getCause();
    if (cause != null && cause != exception) {
      return isPermanentFailure(cause);
    }

    // Default to non-permanent for unknown exceptions (may be retryable)
    return false;
  }

  /**
   * Determines if an exception should be recorded by the circuit breaker as a failure.
   *
   * <p>Circuit breaker failures include both transient and some permanent failures, but exclude:
   *
   * <ul>
   *   <li>404 Not Found (expected behavior for missing secrets)
   *   <li>Authentication errors (configuration issue, not service issue)
   * </ul>
   *
   * @param exception the exception to classify
   * @return true if the exception should count as a circuit breaker failure
   */
  public static boolean isCircuitBreakerFailure(Throwable exception) {
    if (exception == null) {
      return false;
    }

    // Don't count 404 as circuit breaker failure - missing secrets are expected
    if (exception instanceof ResourceNotFoundException) {
      return false;
    }

    if (exception instanceof HttpResponseException httpException) {
      int statusCode = httpException.getResponse().getStatusCode();
      // Don't count 404 or auth errors as circuit breaker failures
      if (statusCode == 404 || statusCode == 401 || statusCode == 403) {
        return false;
      }
      return true; // All other HTTP errors count as failures
    }

    // Authentication errors are configuration issues, not service failures
    if (exception instanceof ClientAuthenticationException) {
      return false;
    }

    // All other exceptions (timeouts, network issues, etc.) count as failures
    return true;
  }

  /**
   * Gets a human-readable error category for logging and metrics.
   *
   * @param exception the exception to categorize
   * @return error category string
   */
  public static String getErrorCategory(Throwable exception) {
    if (exception == null) {
      return "unknown";
    }

    if (exception instanceof ResourceNotFoundException) {
      return "not_found";
    }

    if (exception instanceof ClientAuthenticationException) {
      return "authentication";
    }

    if (exception instanceof HttpResponseException httpException) {
      int statusCode = httpException.getResponse().getStatusCode();
      return switch (statusCode) {
        case 400 -> "bad_request";
        case 401 -> "unauthorized";
        case 403 -> "forbidden";
        case 404 -> "not_found";
        case 429 -> "rate_limited";
        case 503 -> "service_unavailable";
        default -> statusCode >= 500 ? "server_error" : "client_error";
      };
    }

    if (exception instanceof SocketTimeoutException || exception instanceof TimeoutException) {
      return "timeout";
    }

    if (exception instanceof UnknownHostException) {
      return "network";
    }

    if (exception instanceof SSLException) {
      return "ssl";
    }

    return "unknown";
  }
}
