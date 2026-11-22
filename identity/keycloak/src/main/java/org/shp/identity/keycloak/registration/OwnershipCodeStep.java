package org.shp.identity.keycloak.registration;

import jakarta.ws.rs.core.MultivaluedMap;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.shp.identity.keycloak.otp.OtpChallenge;
import org.shp.identity.keycloak.otp.OtpProvider;
import org.shp.identity.keycloak.otp.OtpPurpose;
import org.shp.identity.keycloak.otp.OtpRateLimitedException;
import org.shp.identity.keycloak.otp.OtpRequest;
import org.shp.identity.keycloak.otp.OtpUnavailableException;
import org.shp.identity.keycloak.otp.OtpVerification;

/**
 * Sign-up step 2 (IAM-08): proves that the person owns the phone number or email address of step 1
 * with a one-time code sent through the {@code shp-otp} provider (notification N8/N9, IAM-04). The
 * page and its answers are the same whether or not an account already uses that identifier.
 */
public class OwnershipCodeStep extends StepAuthenticator {
  private static final Logger LOG = Logger.getLogger(OwnershipCodeStep.class);
  static final String TEMPLATE = "shp-register-code.ftl";
  static final String FIELD_CODE = "code";
  static final String ACTION_RESEND = "resend";
  static final String ACTION_CHANGE = "change";
  private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");

  @Override
  public void authenticate(AuthenticationFlowContext context) {
    AuthenticationSessionModel session = context.getAuthenticationSession();
    if (RegistrationNotes.verified(session).isPresent()) {
      context.success();
      return;
    }
    send(context, null);
  }

  @Override
  public void action(AuthenticationFlowContext context) {
    AuthenticationSessionModel session = context.getAuthenticationSession();
    MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
    if (form.containsKey(ACTION_CHANGE)) {
      RegistrationNotes.clear(session);
      context.resetFlow();
      return;
    }
    if (form.containsKey(ACTION_RESEND)) {
      send(context, "shpCodeResent");
      return;
    }
    String challengeId = session.getAuthNote(RegistrationNotes.CHALLENGE);
    String code = form.getFirst(FIELD_CODE) == null ? "" : form.getFirst(FIELD_CODE).strip();
    if (challengeId == null || !CODE.matcher(code).matches()) {
      context.challenge(page(context).setError("shpCodeInvalid").createForm(TEMPLATE));
      return;
    }
    OtpVerification result;
    try {
      result = provider(context).verify(challengeId, code);
    } catch (OtpUnavailableException ignored) {
      // A provider exception can contain an upstream response. Do not risk logging a destination
      // or code from that response; the provider owns operational metrics without personal data.
      LOG.warn("one-time code service unavailable while verifying");
      context.challenge(page(context).setError("shpCodeUnavailable").createForm(TEMPLATE));
      return;
    }
    switch (result) {
      case VALID -> {
        session.removeAuthNote(RegistrationNotes.CHALLENGE);
        session.setAuthNote(
            RegistrationNotes.VERIFIED, session.getAuthNote(RegistrationNotes.DESTINATION));
        context.success();
      }
      case LOCKED ->
          context.challenge(page(context).setError("shpCodeLocked").createForm(TEMPLATE));
      case INVALID ->
          context.challenge(page(context).setError("shpCodeInvalid").createForm(TEMPLATE));
    }
  }

  /** Sends a code to the identifier of step 1 and shows the code page. */
  private static void send(AuthenticationFlowContext context, String info) {
    AuthenticationSessionModel session = context.getAuthenticationSession();
    Identifier id = RegistrationNotes.identifier(session).orElse(null);
    if (id == null) {
      // the flow lost its state (for example a stale tab): start again from step 1
      context.resetFlow();
      return;
    }
    LoginFormsProvider page = page(context);
    try {
      String locale = context.getSession().getContext().resolveLocale(null).toLanguageTag();
      OtpChallenge challenge =
          provider(context)
              .send(new OtpRequest(OtpPurpose.REGISTER, id.channel(), id.value(), locale));
      session.setAuthNote(RegistrationNotes.CHALLENGE, challenge.challengeId());
      if (info != null) {
        page.setInfo(info);
      }
    } catch (OtpRateLimitedException ignored) {
      page.setError("shpCodeRateLimited");
    } catch (OtpUnavailableException ignored) {
      LOG.warn("one-time code service unavailable while sending");
      page.setError("shpCodeUnavailable");
    }
    context.challenge(page.createForm(TEMPLATE));
  }

  private static LoginFormsProvider page(AuthenticationFlowContext context) {
    Identifier id = RegistrationNotes.identifier(context.getAuthenticationSession()).orElse(null);
    return context
        .form()
        .setAttribute("shpChannel", id == null ? "" : id.channel().wire())
        .setAttribute("shpDestination", id == null ? "" : id.value());
  }

  private static OtpProvider provider(AuthenticationFlowContext context) {
    OtpProvider provider = context.getSession().getProvider(OtpProvider.class);
    if (provider == null) {
      throw new OtpUnavailableException("no shp-otp provider is installed", null);
    }
    return provider;
  }

  /** Registers {@link OwnershipCodeStep} as {@value #ID}. */
  public static class Factory extends StepFactory {
    public static final String ID = "shp-ownership-code";
    private static final OwnershipCodeStep INSTANCE = new OwnershipCodeStep();

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
      return "SHP sign-up: prove ownership with a one-time code";
    }

    @Override
    public String getHelpText() {
      return "Sends a code to the phone number or email of the previous step (shp-otp provider) and checks it.";
    }
  }
}
