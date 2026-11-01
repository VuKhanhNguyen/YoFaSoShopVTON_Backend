package com.yofaso.shop.auth;

import com.yofaso.shop.common.Problem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
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
    // Chỉ mã số do server sinh được đưa vào HTML, không chèn dữ liệu nhập từ client.
    Problem.require(otp != null && otp.matches("[0-9]{6}") && minutes > 0 && minutes <= 60,
        500,"MAIL_TEMPLATE_INVALID","Không thể tạo email xác minh.");
    try {
      var message=sender.createMimeMessage();
      var helper=new MimeMessageHelper(message,true,"UTF-8");
      helper.setFrom(from); helper.setTo(recipient);
      helper.setSubject("YoFaSoShop - Mã đặt lại mật khẩu");
      helper.setText("Mã OTP của bạn: "+otp+"\nCó hiệu lực "+minutes+" phút, chỉ sử dụng một lần.\nNếu không yêu cầu, hãy bỏ qua email này. Không chia sẻ mã với người khác.", html(otp,minutes));
      sender.send(message);
    }
    catch(org.springframework.mail.MailException | jakarta.mail.MessagingException e) {
      // Không log exception SMTP vì có thể kèm nội dung email/OTP hoặc địa chỉ người nhận.
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("password_reset_delivery_failed: kiểm tra cấu hình SMTP, yêu cầu mã mới sau cooldown");
    }
  }
  /** Bố cục bảng và CSS inline tương thích email; có bản text thay thế khi không đọc HTML. */
  static String html(String otp,int minutes) {
    return """
      <!doctype html><html lang="vi"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
      <body style="margin:0;background:#FAFAF9;font-family:Arial,Helvetica,sans-serif;color:#0F172A">
      <div style="display:none;max-height:0;overflow:hidden">Mã xác minh đặt lại mật khẩu YoFaSo Shop. Có hiệu lực %d phút.</div>
      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#FAFAF9;padding:32px 16px"><tr><td align="center">
      <table role="presentation" width="560" cellpadding="0" cellspacing="0" style="width:100%%;max-width:560px;background:#FFFFFF;border:1px solid #E2E8F0;border-radius:16px;overflow:hidden">
      <tr><td style="padding:28px 32px;background:#0F172A;color:#FFFFFF"><div style="font-size:27px;letter-spacing:3px;font-weight:bold">YoFaSo<span style="color:#C5A880"> Shop</span></div><div style="font-size:12px;color:#E2E8F0;margin-top:8px;letter-spacing:2px">PHONG CÁCH CỦA RIÊNG BẠN</div></td></tr>
      <tr><td style="padding:32px"><div style="font-size:12px;letter-spacing:2px;color:#475569">BẢO MẬT TÀI KHOẢN</div>
      <h1 style="font-size:24px;line-height:1.4;margin:12px 0 16px">Đặt lại mật khẩu của bạn</h1>
      <p style="font-size:15px;color:#475569;line-height:1.7;margin:0 0 24px">Nhập mã xác minh dưới đây vào màn hình khôi phục tài khoản YoFaSo để tạo mật khẩu mới.</p>
      <div style="background:#F7F3EE;border:1px solid #C5A880;border-radius:10px;padding:22px 12px;text-align:center;font-size:36px;font-weight:bold;letter-spacing:9px;font-family:monospace;color:#0F172A">%s</div>
      <p style="font-size:14px;text-align:center;color:#475569;margin:16px 0 28px">Mã có hiệu lực <strong>%d phút</strong> và chỉ sử dụng một lần.</p>
      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"><tr><td style="border-top:1px solid #E2E8F0;padding-top:20px;font-size:13px;color:#475569;line-height:1.8"><strong>Giữ tài khoản của bạn an toàn</strong><br>Không chia sẻ mã này, kể cả với người tự xưng là nhân viên YoFaSo.<br>Nếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này. Mật khẩu hiện tại vẫn được giữ nguyên.</td></tr></table>
      </td></tr></table>
      <p style="font-size:12px;color:#64748B;line-height:1.7;margin:22px 0">Email được gửi tự động từ YoFaSo Shop.<br>Vui lòng không trả lời email này.</p>
      </td></tr></table></body></html>
      """.formatted(minutes,otp,minutes);
  }
}
