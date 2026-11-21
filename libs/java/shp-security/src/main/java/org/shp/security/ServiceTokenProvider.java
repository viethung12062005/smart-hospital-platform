package org.shp.security;

/**
 * Source of the workload-identity token a service presents when it calls another service's {@code
 * /internal/*} API (15 §7, 07 §8).
 */
public interface ServiceTokenProvider {
  /** A currently valid access token. Never log it. */
  String token();

  /** Forget the cached token, for example after the callee answered 401. */
  void invalidate();
}
