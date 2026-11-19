package org.shp.notification.app;

/** Raised when a notification template cannot be loaded or rendered. */
public class NotificationTemplateException extends RuntimeException {

  public NotificationTemplateException(String message) {
    super(message);
  }

  public NotificationTemplateException(String message, Throwable cause) {
    super(message, cause);
  }
}
