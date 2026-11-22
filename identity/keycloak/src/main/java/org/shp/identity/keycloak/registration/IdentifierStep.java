package org.shp.identity.keycloak.registration;

import jakarta.ws.rs.core.MultivaluedMap;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.utils.FormMessage;

/**
 * Sign-up step 1 (IAM-08): the person enters a phone number or an email address and accepts the
 * terms of use. Nothing is looked up here, so the answer never depends on whether an account
 * exists; the next step proves ownership with a code before anything about an account is shown.
 */
public class IdentifierStep extends StepAuthenticator {
  static final String TEMPLATE = "shp-register-identifier.ftl";
  static final String FIELD_IDENTIFIER = "identifier";
  static final String FIELD_TERMS = "termsAccepted";

  @Override
  public void authenticate(AuthenticationFlowContext context) {
    context.challenge(context.form().createForm(TEMPLATE));
  }

  @Override
  public void action(AuthenticationFlowContext context) {
    MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
    String raw = form.getFirst(FIELD_IDENTIFIER);
    Optional<Identifier> id = Identifier.parse(raw);
    List<FormMessage> errors = new ArrayList<>();
    if (id.isEmpty()) {
      errors.add(new FormMessage(FIELD_IDENTIFIER, "shpIdentifierInvalid"));
    }
    if (!"on".equals(form.getFirst(FIELD_TERMS))) {
      errors.add(new FormMessage(FIELD_TERMS, "shpTermsRequired"));
    }
    if (!errors.isEmpty()) {
      context.challenge(
          context
              .form()
              .setAttribute(FIELD_IDENTIFIER, raw == null ? "" : raw)
              .setErrors(errors)
              .createForm(TEMPLATE));
      return;
    }
    RegistrationNotes.start(
        context.getAuthenticationSession(), id.get(), Instant.now().getEpochSecond());
    context.success();
  }

  /** Registers {@link IdentifierStep} as {@value #ID}. */
  public static class Factory extends StepFactory {
    public static final String ID = "shp-registration-identifier";
    private static final IdentifierStep INSTANCE = new IdentifierStep();

    @Override
    Authenticator authenticator() {
      return INSTANCE;
    }

    @Override
    public String getId() {
      return ID;
    }

    @Override
    public String getDisplayType() {
      return "SHP sign-up: phone number or email and terms";
    }

    @Override
    public String getHelpText() {
      return "Asks for a Vietnamese mobile number or an email address and the acceptance of the terms of use.";
    }
  }
}
