package org.shp.identity.keycloak.registration;

import java.util.Optional;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.shp.identity.keycloak.otp.Channel;

/**
 * State of one sign-up, kept in Keycloak's authentication session (server side, never in the
 * browser) between the steps of the {@code shp registration} flow.
 */
final class RegistrationNotes {
  static final String CHANNEL = "shp.reg.channel";
  static final String DESTINATION = "shp.reg.destination";
  static final String TERMS_ACCEPTED_AT = "shp.reg.terms-accepted-at";
  static final String CHALLENGE = "shp.reg.challenge";
  static final String VERIFIED = "shp.reg.verified";

  private RegistrationNotes() {}

  static void start(AuthenticationSessionModel session, Identifier id, long termsAcceptedAt) {
    clear(session);
    session.setAuthNote(CHANNEL, id.channel().name());
    session.setAuthNote(DESTINATION, id.value());
    session.setAuthNote(TERMS_ACCEPTED_AT, Long.toString(termsAcceptedAt));
  }

  static Optional<Identifier> identifier(AuthenticationSessionModel session) {
    String channel = session.getAuthNote(CHANNEL);
    String destination = session.getAuthNote(DESTINATION);
    return channel == null || destination == null
        ? Optional.empty()
        : Optional.of(new Identifier(Channel.valueOf(channel), destination));
  }

  /** The identifier whose ownership was proven with a code in this sign-up, if any. */
  static Optional<Identifier> verified(AuthenticationSessionModel session) {
    return identifier(session).filter(id -> id.value().equals(session.getAuthNote(VERIFIED)));
  }

  static void clear(AuthenticationSessionModel session) {
    for (String note :
        new String[] {CHANNEL, DESTINATION, TERMS_ACCEPTED_AT, CHALLENGE, VERIFIED}) {
      session.removeAuthNote(note);
    }
  }
}
