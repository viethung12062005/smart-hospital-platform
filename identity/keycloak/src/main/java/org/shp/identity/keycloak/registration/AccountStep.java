package org.shp.identity.keycloak.registration;

import jakarta.ws.rs.core.MultivaluedMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.requiredactions.TermsAndConditions;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventType;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.policy.PasswordPolicyManagerProvider;
import org.keycloak.policy.PolicyError;
import org.keycloak.services.messages.Messages;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.userprofile.UserProfile;
import org.keycloak.userprofile.UserProfileContext;
import org.keycloak.userprofile.UserProfileProvider;
import org.keycloak.userprofile.ValidationException;
import org.shp.identity.keycloak.otp.Channel;

/**
 * Sign-up step 3 (IAM-08), reached only after the code of step 2 proved ownership. Only now does
 * the flow look for an account with that phone number or email: the owner either learns that an
 * account exists (and is sent to sign in or reset the password) or chooses a password, and the
 * patient account is created with the identifier as its verified username.
 */
public class AccountStep extends StepAuthenticator {
  static final String TEMPLATE_PASSWORD = "shp-register-password.ftl";
  static final String TEMPLATE_EXISTS = "shp-register-exists.ftl";
  static final String FIELD_PASSWORD = "password";
  static final String FIELD_CONFIRM = "password-confirm";

  /** Verified phone number: the attributes the stock {@code phone} client scope maps to claims. */
  static final String PHONE_NUMBER = "phoneNumber";

  static final String PHONE_NUMBER_VERIFIED = "phoneNumberVerified";

  /** Self-registered accounts are patients (detailed-design 15 §8). */
  static final String PATIENT_ROLE = "patient";

  @Override
  public void authenticate(AuthenticationFlowContext context) {
    Optional<Identifier> id = RegistrationNotes.verified(context.getAuthenticationSession());
    if (id.isEmpty()) {
      context.failure(AuthenticationFlowError.INTERNAL_ERROR);
      return;
    }
    if (existing(context.getSession(), context.getRealm(), id.get()) != null) {
      showExists(context);
      return;
    }
    context.challenge(passwordPage(context).createForm(TEMPLATE_PASSWORD));
  }

  @Override
  public void action(AuthenticationFlowContext context) {
    AuthenticationSessionModel session = context.getAuthenticationSession();
    Optional<Identifier> verified = RegistrationNotes.verified(session);
    if (verified.isEmpty()) {
      context.failure(AuthenticationFlowError.INTERNAL_ERROR);
      return;
    }
    Identifier id = verified.get();
    KeycloakSession kc = context.getSession();
    RealmModel realm = context.getRealm();
    if (existing(kc, realm, id) != null) { // created by someone else since the page was shown
      showExists(context);
      return;
    }
    MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
    String password = form.getFirst(FIELD_PASSWORD);
    String confirm = form.getFirst(FIELD_CONFIRM);
    if (password == null || password.isEmpty()) {
      showPasswordError(context, new FormMessage(FIELD_PASSWORD, Messages.MISSING_PASSWORD));
      return;
    }
    if (!password.equals(confirm)) {
      showPasswordError(context, new FormMessage(FIELD_CONFIRM, Messages.INVALID_PASSWORD_CONFIRM));
      return;
    }
    PolicyError policy =
        kc.getProvider(PasswordPolicyManagerProvider.class).validate(id.value(), password);
    if (policy != null) {
      showPasswordError(
          context, new FormMessage(FIELD_PASSWORD, policy.getMessage(), policy.getParameters()));
      return;
    }
    UserModel user;
    try {
      user = create(kc, realm, id);
    } catch (ModelDuplicateException ignored) {
      // Another request created the same verified identifier after the lookup above.
      showExists(context);
      return;
    } catch (ValidationException e) {
      showPasswordError(context, new FormMessage(null, "shpRegistrationFailed"));
      return;
    }
    user.setSingleAttribute(
        TermsAndConditions.USER_ATTRIBUTE,
        session.getAuthNote(RegistrationNotes.TERMS_ACCEPTED_AT));
    RoleModel patient = realm.getRole(PATIENT_ROLE);
    if (patient != null) {
      user.grantRole(patient);
    }
    user.credentialManager().updateCredential(UserCredentialModel.password(password, false));
    RegistrationNotes.clear(session);
    context.setUser(user);
    // Same events as Keycloak's own registration form: REGISTER now, LOGIN when the flow completes.
    // No username or email detail: both are personal data.
    context
        .getEvent()
        .user(user)
        .detail(Details.REGISTER_METHOD, "shp-" + id.channel().wire())
        .success();
    context.newEvent().event(EventType.LOGIN);
    context
        .getEvent()
        .client(session.getClient().getClientId())
        .detail(Details.REDIRECT_URI, session.getRedirectUri())
        .detail(Details.AUTH_METHOD, session.getProtocol());
    context.success();
  }

  /** The account that already uses this identifier, by username, email or verified phone. */
  static UserModel existing(KeycloakSession kc, RealmModel realm, Identifier id) {
    UserModel user = kc.users().getUserByUsername(realm, id.value());
    if (user == null && id.channel() == Channel.EMAIL) {
      user = kc.users().getUserByEmail(realm, id.value());
    }
    if (user == null && id.channel() == Channel.PHONE) {
      user =
          kc.users()
              .searchForUserByUserAttributeStream(realm, PHONE_NUMBER, id.value())
              .findFirst()
              .orElse(null);
    }
    return user;
  }

  /**
   * Creates the account through the realm's user profile, so its username validators apply. The
   * verified email or phone number is set afterwards: users may view but not edit them (the realm's
   * user profile), and the code of step 2 has just verified them.
   */
  private static UserModel create(KeycloakSession kc, RealmModel realm, Identifier id) {
    UserProfile profile =
        kc.getProvider(UserProfileProvider.class)
            .create(
                UserProfileContext.REGISTRATION, Map.of(UserModel.USERNAME, List.of(id.value())));
    profile.validate();
    UserModel user = profile.create();
    user.setEnabled(true);
    if (id.channel() == Channel.EMAIL) {
      user.setEmail(id.value());
      user.setEmailVerified(true);
    } else {
      user.setSingleAttribute(PHONE_NUMBER, id.value());
      user.setSingleAttribute(PHONE_NUMBER_VERIFIED, "true");
    }
    return user;
  }

  private static void showExists(AuthenticationFlowContext context) {
    RegistrationNotes.clear(context.getAuthenticationSession());
    context.getEvent().error(Errors.USERNAME_IN_USE);
    context.challenge(context.form().createForm(TEMPLATE_EXISTS));
  }

  private static void showPasswordError(AuthenticationFlowContext context, FormMessage error) {
    context.challenge(
        passwordPage(context).setErrors(List.of(error)).createForm(TEMPLATE_PASSWORD));
  }

  private static LoginFormsProvider passwordPage(AuthenticationFlowContext context) {
    Identifier id = RegistrationNotes.verified(context.getAuthenticationSession()).orElseThrow();
    return context.form().setAttribute("shpDestination", id.value());
  }

  /** Registers {@link AccountStep} as {@value #ID}. */
  public static class Factory extends StepFactory {
    public static final String ID = "shp-registration-account";
    private static final AccountStep INSTANCE = new AccountStep();

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
      return "SHP sign-up: create the patient account";
    }

    @Override
    public String getHelpText() {
      return "After a verified code: tells the owner that an account exists, or sets the password and creates the patient account.";
    }
  }
}
