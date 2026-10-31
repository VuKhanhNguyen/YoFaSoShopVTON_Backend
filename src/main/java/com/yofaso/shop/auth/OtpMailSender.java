package com.yofaso.shop.auth;

import com.yofaso.shop.common.Problem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** SMTP thật; test thay bean bằng mailbox giả, không có chế độ trả OTP ra API/log. */
@Service
public class OtpMailSender {
  private final JavaMailSender sender;
  private final String from;
  private final boolean enabled;
  public OtpMailSender(JavaMailSender sender,@Value("${app.mail.from:}") String from,
      @Value("${app.mail.enabled:false}") boolean enabled) {
    this.sender=sender; this.from=from; this.enabled=enabled;
  }
  public void requireConfigured() {
    Problem.require(enabled && !from.isBlank(),503,"MAIL_NOT_CONFIGURED","Chưa cấu hình SMTP gửi OTP; xem setupGuide.md.");
  }
  @org.springframework.scheduling.annotation.Async("otpExecutor")
  public void send(String recipient,String otp,int minutes) {
    requireConfigured();
    var message=new SimpleMailMessage();
    message.setFrom(from); message.setTo(recipient);
    message.setSubject("YoFaSoShop - Mã đặt lại mật khẩu");
    message.setText("Mã OTP của bạn: "+otp+"\nCó hiệu lực "+minutes+" phút, chỉ sử dụng một lần.\nNếu không yêu cầu, hãy bỏ qua email này. Không chia sẻ mã với người khác.");
    try { sender.send(message); }
    catch(org.springframework.mail.MailException e) {
      // Không log exception SMTP vì có thể kèm nội dung email/OTP hoặc địa chỉ người nhận.
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("password_reset_delivery_failed: kiểm tra cấu hình SMTP, yêu cầu mã mới sau cooldown");
    }
  }
}
