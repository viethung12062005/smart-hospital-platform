package org.shp.identity.keycloak.otp;

import java.time.Instant;

/** A code that was sent (the answer of N8): the id to verify it with and when it expires. */
public record OtpChallenge(String challengeId, Instant expiresAt) {}
