package org.shp.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.shp.notification.app.NotificationMessage;
import org.shp.notification.app.NotificationResult;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpEmailChannelTest {

  private final JavaMailSender sender = mock(JavaMailSender.class);

  private SmtpEmailChannel channel() {
    when(sender.createMimeMessage())
        .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    return new SmtpEmailChannel(sender, "noreply@example.test");
  }

  @Test
  void sendsMailAndReportsSuccess() {
    SmtpEmailChannel channel = channel();

    NotificationResult result =
        channel.send(new NotificationMessage("patient@example.test", "Reminder", "Visit tomorrow"));

    assertThat(channel.channelName()).isEqualTo("email");
    assertThat(result.successful()).isTrue();
    verify(sender).send(any(MimeMessage.class));
  }

  @Test
  void mapsSmtpFailureToProviderError() {
    SmtpEmailChannel channel = channel();
    doThrow(new MailSendException("down")).when(sender).send(any(MimeMessage.class));

    NotificationResult result = channel.send(new NotificationMessage("a@example.test", null, "c"));

    assertThat(result.successful()).isFalse();
    assertThat(result.errorCode()).isEqualTo("provider-error");
  }

  @Test
  void rejectsMissingSenderAddress() {
    assertThatThrownBy(() -> new SmtpEmailChannel(sender, " "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
