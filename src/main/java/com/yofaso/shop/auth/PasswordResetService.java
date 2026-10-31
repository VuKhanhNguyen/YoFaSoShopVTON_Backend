package com.yofaso.shop.auth;

import com.yofaso.shop.common.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** OTP ngoài schema ERD: không lưu mã rõ, không tiết lộ tài khoản qua response forgot. */
@Service
public class PasswordResetService {
  private final Db db;
  private final OtpMailSender mail;
  private final PasswordEncoder passwords;
  private final byte[] pepper;
  private final int minutes;
  public PasswordResetService(Db db,OtpMailSender mail,PasswordEncoder passwords,
      @Value("${app.jwt.secret}") String secret,@Value("${app.password-reset.ttl-minutes:10}") int minutes) {
    this.db=db;this.mail=mail;this.passwords=passwords;pepper=secret.getBytes(StandardCharsets.UTF_8);
    if(minutes<1 || minutes>30) throw new IllegalArgumentException("OTP TTL cần 1..30 phút");
    this.minutes=minutes;
  }
  public static String email(String value) { return value.trim().toLowerCase(Locale.ROOT); }
  private String digest(String value) {
    try {
      var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(pepper,"HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(("password-reset:"+value).getBytes(StandardCharsets.UTF_8)));
    } catch(GeneralSecurityException e) {throw new IllegalStateException(e);}
  }
  /** Cả email lạ/trùng/khóa đều trả 202 như nhau; cooldown theo đúng email, không bỏ dấu +. */
  public synchronized Map<String,Object> request(String input) {
    mail.requireConfigured();
    String email=email(input), key=digest(email), id=UUID.randomUUID().toString().replace("-","");
    long now=System.currentTimeMillis();
    var state=db.runtime();
    var cooldown=state.find("resetcooldown",r -> key.equals(r.get("key")));
    Problem.require(cooldown.isEmpty() || now-((Number)cooldown.getFirst().get("at")).longValue()>=60000,
      429,"OTP_COOLDOWN","Chờ 60 giây trước khi yêu cầu mã mới.");
    state.put("resetcooldown",key,Map.of("key",key,"at",now));
    for(var old:state.find("passwordreset",r -> key.equals(r.get("emailHash")) || ((Number)r.get("expires")).longValue()<now))
      state.remove("passwordreset",old.get("requestId").toString());
    var users=db.rows("SELECT MaNguoiDung,MatKhau,Email,TrangThaiHoatDong FROM NguoiDung WHERE LOWER(TRIM(Email))=?",email);
    if(users.size()==1 && "HOAT_DONG".equals(users.getFirst().get("TrangThaiHoatDong"))) {
      var user=users.getFirst();
      String owner=Db.text(user,"MaNguoiDung");
      String otp=String.format(Locale.ROOT,"%06d",new SecureRandom().nextInt(1000000));
      state.put("passwordreset",id,RuntimeState.row("requestId",id,"emailHash",key,"owner",owner,
        "hash",digest(id+":"+otp),"expires",now+minutes*60000L,"attempts",0,
        "credentialStamp",Idempotency.hash(Db.text(user,"MatKhau")),"authVersion",state.version(owner)));
      try { mail.send(email,otp,minutes); }
      catch(RuntimeException e) {
        state.remove("passwordreset",id);
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("password_reset_delivery_failed requestId={}",id);
      }
    }
    return Map.of("requestId",id,"message","Nếu email khớp một tài khoản hợp lệ, mã OTP sẽ được gửi. Kiểm tra cả thư rác.","expiresIn",minutes*60);
  }
  /** Mã dùng một lần; sai tối đa 5 lượt. Password/email/role đổi làm mã cũ vô hiệu. */
  @Transactional
  public synchronized Map<String,Object> reset(PasswordResetController.Reset b) {
    AuthService.validatePassword(b.newPassword());
    var state=db.runtime();
    var records=state.find("passwordreset",r -> b.requestId().equals(r.get("requestId")));
    Problem.require(!records.isEmpty(),400,"OTP_INVALID","Mã không hợp lệ hoặc đã hết hạn.");
    var row=records.getFirst();
    int attempts=((Number)row.get("attempts")).intValue()+1;
    boolean valid=((Number)row.get("expires")).longValue()>System.currentTimeMillis() && attempts<=5
      && Objects.equals(row.get("emailHash"),digest(email(b.email())))
      && MessageDigest.isEqual(Db.text(row,"hash").getBytes(StandardCharsets.US_ASCII),digest(b.requestId()+":"+b.otp()).getBytes(StandardCharsets.US_ASCII));
    if(!valid) {
      if(attempts>=5 || ((Number)row.get("expires")).longValue()<=System.currentTimeMillis()) state.remove("passwordreset",b.requestId());
      else state.patch("passwordreset",b.requestId(),"attempts",attempts);
      throw new Problem(400,"OTP_INVALID","Mã không hợp lệ hoặc đã hết hạn.");
    }
    String owner=Db.text(row,"owner");
    var user=db.one("SELECT MatKhau,Email,TrangThaiHoatDong FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE",owner);
    Problem.require("HOAT_DONG".equals(user.get("TrangThaiHoatDong")) && Objects.equals(row.get("emailHash"),digest(email(Db.text(user,"Email"))))
      && Objects.equals(row.get("credentialStamp"),Idempotency.hash(Db.text(user,"MatKhau")))
      && ((Number)row.get("authVersion")).longValue()==state.version(owner),400,"OTP_INVALID","Thông tin xác thực đã thay đổi; yêu cầu mã mới.");
    state.remove("passwordreset",b.requestId());
    db.exec("UPDATE NguoiDung SET MatKhau=? WHERE MaNguoiDung=?",passwords.encode(b.newPassword()),owner);
    state.bump(owner);
    return Map.of("loginAgain",true,"message","Đã đổi mật khẩu. Hãy đăng nhập lại.");
  }
}
