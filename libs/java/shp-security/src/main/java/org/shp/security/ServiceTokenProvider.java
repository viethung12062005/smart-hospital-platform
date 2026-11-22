package org.shp.security;

/**
 * Source of the workload-identity token a service presents when it calls another service's {@code
 * /internal/*} API (15 §7, 07 §8).
 */
public interface ServiceTokenProvider {
  /** A currently valid access token for the named target service. Never log it. */
  String token(String target);

  /** Forget the cached token for the named target, for example after the callee answered 401. */
  void invalidate(String target);
}
