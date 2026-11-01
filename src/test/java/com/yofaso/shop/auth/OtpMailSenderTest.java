package com.yofaso.shop.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.mail.Session;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

class OtpMailSenderTest {
  private String find(Part part, String type) throws Exception {
    if (part.isMimeType(type)) return (String) part.getContent();
    if (part.getContent() instanceof Multipart children) {
      for (int i=0;i<children.getCount();i++) {
        var result=find(children.getBodyPart(i),type);
        if (result!=null) return result;
      }
    }
    return null;
  }
  @Test void sendsUtf8HtmlAndPlainTextWithoutContactingSmtp() throws Exception {
    var sender=mock(JavaMailSender.class);
    var message=new MimeMessage(Session.getInstance(new Properties()));
    when(sender.createMimeMessage()).thenReturn(message);
    new OtpMailSender(sender,"shop@example.com",true).send("customer@example.com","083914",10);
    verify(sender).send(message);
    message.saveChanges();
    assertEquals("YoFaSoShop - Mã đặt lại mật khẩu",message.getSubject());
    assertTrue(find(message,"text/plain").contains("083914"));
    var html=find(message,"text/html");
    assertTrue(html.contains("083914"));
    assertTrue(html.contains("10 phút"));
    assertTrue(html.contains("Không chia sẻ"));
    assertTrue(html.contains("#0F172A"));
    assertFalse(html.contains("<script"));
  }
  @Test void rejectsTemplateInjectionBeforeSending() {
    var sender=mock(JavaMailSender.class);
    var mail=new OtpMailSender(sender,"shop@example.com",true);
    assertThrows(com.yofaso.shop.common.Problem.class,() -> mail.send("customer@example.com","<img>",10));
    verifyNoInteractions(sender);
  }
  @Test void doesNotSendWhenDisabled() {
    var sender=mock(JavaMailSender.class);
    assertThrows(com.yofaso.shop.common.Problem.class,() -> new OtpMailSender(sender,"",false).send("customer@example.com","123456",10));
    verifyNoInteractions(sender);
  }
}
