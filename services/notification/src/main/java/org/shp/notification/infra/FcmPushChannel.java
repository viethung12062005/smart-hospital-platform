package org.shp.notification.infra;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import java.util.Objects;
import org.shp.notification.app.NotificationChannel;
import org.shp.notification.app.NotificationMessage;
import org.shp.notification.app.NotificationResult;

/**
 * Push channel backed by Firebase Cloud Messaging. The recipient of the message is the device
 * token. Neither the token nor the content is ever logged.
 */
public class FcmPushChannel implements NotificationChannel {

  /** Minimal transport so tests do not need Firebase. */
  @FunctionalInterface
  public interface Transport {
    String send(String token, String title, String body) throws FirebaseMessagingException;
  }

  private final Transport transport;

  public FcmPushChannel(Transport transport) {
    this.transport = Objects.requireNonNull(transport, "FCM transport is required");
  }

  /**
   * Creates the channel on top of a real Firebase messaging client. firebase-admin 9.12 deprecates
   * {@code setToken} in favour of {@code setFid} (installation id), but clients still register
   * device tokens; moving to installation ids is a client change, tracked as a follow-up.
   */
  @SuppressWarnings("deprecation")
  public static FcmPushChannel of(FirebaseMessaging messaging) {
    return new FcmPushChannel((token, title, body) ->
        messaging.send(
            Message.builder()
                .setToken(token)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .build()));
  }

  @Override
  public String channelName() {
    return "push";
  }

  @Override
  public NotificationResult send(NotificationMessage message) {
    Objects.requireNonNull(message, "Notification message is required");
    try {
      return NotificationResult.success(
          transport.send(message.recipient(), message.subject(), message.content()));
    } catch (FirebaseMessagingException e) {
      MessagingErrorCode code = e.getMessagingErrorCode();
      boolean badToken =
          code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT;
      return NotificationResult.failure(badToken ? "invalid-recipient" : "provider-error");
    } catch (RuntimeException e) {
      return NotificationResult.failure("provider-error");
    }
  }
}
