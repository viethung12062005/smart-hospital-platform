package org.shp.notification.infra;

import jakarta.mail.internet.MimeMessage;
import java.util.Objects;
import org.shp.notification.app.NotificationChannel;
import org.shp.notification.app.NotificationMessage;
import org.shp.notification.app.NotificationResult;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Email channel that sends through Spring Mail (Gmail SMTP in the real mode). */
public class SmtpEmailChannel implements NotificationChannel {

  private static final String DEFAULT_SUBJECT = "Smart Hospital notification";

  private final JavaMailSender mailSender;
  private final String from;

  public SmtpEmailChannel(JavaMailSender mailSender, String from) {
    this.mailSender = Objects.requireNonNull(mailSender, "Mail sender is required");
    if (from == null || from.isBlank()) {
      throw new IllegalArgumentException("Email sender address is required");
    }
    this.from = from;
  }

  @Override
  public String channelName() {
    return "email";
  }

  @Override
  public NotificationResult send(NotificationMessage message) {
    Objects.requireNonNull(message, "Notification message is required");
    try {
      MimeMessage mime = mailSender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
      helper.setFrom(from);
      helper.setTo(message.recipient());
      helper.setSubject(
          message.subject() == null || message.subject().isBlank()
              ? DEFAULT_SUBJECT
              : message.subject());
      helper.setText(message.content(), false);
      mailSender.send(mime);
      return NotificationResult.success(null);
    } catch (MailParseException | jakarta.mail.MessagingException e) {
      return NotificationResult.failure("invalid-recipient");
    } catch (MailException | IllegalArgumentException e) {
      return NotificationResult.failure("provider-error");
    }
  }
}
