package org.shp.security;

/**
 * The service token could not be obtained. The message never contains the client secret, the
 * assertion or the response body of the token endpoint.
 */
public class ServiceTokenException extends RuntimeException {
  public ServiceTokenException(String message) {
    super(message);
  }

  public ServiceTokenException(String message, Throwable cause) {
    super(message, cause);
  }
}
