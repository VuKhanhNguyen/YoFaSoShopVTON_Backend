package com.yofaso.shop.auth;

import com.yofaso.shop.common.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tài khoản thật trong MySQL, BCrypt, access JWT 15 phút và refresh xoay vòng. */
@Service
public class AuthService {
  private final Db db;
  private final PasswordEncoder passwords;
  private final JwtEncoder encoder;
  private final String catalogBase;

  /** Nhận các thành phần mã hóa do Spring cung cấp. */
  public AuthService(Db db, PasswordEncoder passwords, JwtEncoder encoder,
      @org.springframework.beans.factory.annotation.Value("${app.catalog.public-base-url:}") String catalogBase) {
    this.db = db;
    this.passwords = passwords;
    this.encoder = encoder;
    this.catalogBase = catalogBase == null ? "" : catalogBase.replaceAll("/+$", "");
  }

  /** Đăng ký luôn là CUSTOMER; client không được tự truyền role. */
  @Transactional
  public Map<String, Object> register(AuthController.Register body) {
    validatePassword(body.password());
    if (body.fullName() != null && !body.fullName().isBlank()) {
      Problem.require(
          body.fullName().trim().matches("^[\\p{L}\\s]+$"),
          422,
          "FULL_NAME_INVALID",
          "Họ và tên không được chứa số hoặc ký tự đặc biệt.");
    }
    String email=AccountEmail.reserve(db,body.email(),"");
    String id = Db.id("ND");
    db.exec(
        "INSERT INTO NguoiDung(MaNguoiDung,TenDangNhap,MatKhau,HoVaTen,SoDienThoai,Email,NgaySinh)"
            + " VALUES(?,?,?,?,?,?,?)",
        id,
        body.username(),
        passwords.encode(body.password()),
        body.fullName()==null || body.fullName().isBlank() ? body.username() : body.fullName().trim(),
        body.phone(),
        email,
        body.dateOfBirth());
    Problem.require(db.exec("INSERT INTO NguoiDung_VaiTro(MaNguoiDung,MaVaiTro)"
        + " SELECT ?,MaVaiTro FROM VaiTro WHERE TenVaiTro='ROLE_CUSTOMER'", id) == 1,
        503, "ROLE_CONFIG_INVALID", "Database chưa có vai trò khách hàng chuẩn.");
    return tokens(id);
  }

  /** Xác thực mà không tiết lộ tên đăng nhập có tồn tại hay không; chấp nhận tên đăng nhập, email hoặc SĐT. */
  @Transactional
  public Map<String, Object> login(String username, String password) {
    Problem.require(
        password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72,
        401,
        "LOGIN_FAILED",
        "Tên đăng nhập hoặc mật khẩu không đúng.");
    String input = username.trim();
    var users = db.rows(
        "SELECT * FROM NguoiDung WHERE TenDangNhap=? OR LOWER(TRIM(Email))=? OR SoDienThoai=?",
        input, input.toLowerCase(Locale.ROOT), input);
    // Hash giả đảm bảo nhánh tài khoản không tồn tại vẫn thực hiện BCrypt.
    String hash =
        users.isEmpty()
            ? "$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW"
            : Db.text(users.getFirst(), "MatKhau");
    boolean match = passwords.matches(password, hash);
    Problem.require(
        !users.isEmpty() && match && "HOAT_DONG".equals(users.getFirst().get("TrangThaiHoatDong")),
        401,
        "LOGIN_FAILED",
        "Tên đăng nhập hoặc mật khẩu không đúng.");
    return tokens(Db.text(users.getFirst(), "MaNguoiDung"));
  }

  /** Refresh chỉ dùng một lần; khóa bản ghi để tránh hai lần xoay đồng thời. */
  @Transactional
  public synchronized Map<String, Object> refresh(String token) {
    Map<String,Object> row;
    try { row = db.runtime().get("refresh", Idempotency.hash(token)); }
    catch (Problem e) { throw new Problem(401, "REFRESH_INVALID", "Refresh token không hợp lệ."); }
    Problem.require(((Number)row.get("ExpiresAt")).longValue() > System.currentTimeMillis(),
        401, "REFRESH_INVALID", "Refresh token đã hết hạn.");
    String id = Db.text(row, "MaNguoiDung");
    var user = db.one("SELECT TrangThaiHoatDong,MatKhau FROM NguoiDung WHERE MaNguoiDung=?", id);
    Problem.require(
        "HOAT_DONG".equals(user.get("TrangThaiHoatDong")),
        401,
        "ACCOUNT_LOCKED",
        "Tài khoản đang khóa.");
    Problem.require(Objects.equals(row.get("credentialStamp"), Idempotency.hash(Db.text(user,"MatKhau")))
        && ((Number)row.get("authVersion")).longValue() == db.runtime().version(id),
        401, "REFRESH_INVALID", "Thông tin xác thực đã thay đổi.");
    db.runtime().remove("refresh", Idempotency.hash(token));
    return tokens(id);
  }

  /** Logout thu hồi refresh được cấp cho đúng người dùng hiện tại. */
  public void logout(String id, String token) {
    synchronized (db.runtime()) {
      var rows = db.runtime().find("refresh", r -> id.equals(r.get("MaNguoiDung")));
      if (rows.stream().anyMatch(r -> Idempotency.hash(token).equals(r.get("TokenHash"))))
        db.runtime().remove("refresh", Idempotency.hash(token));
    }
  }

  /** Chỉ trả hồ sơ an toàn, không có password hash hay AuthVersion. */
  public Map<String, Object> me(String id) {
    var user =
        db.one(
            "SELECT MaNguoiDung AS userId,TenDangNhap AS username,HoVaTen AS fullName,SoDienThoai"
                + " AS phone,Email AS email,NgaySinh AS dateOfBirth,DiemTichLuy AS loyaltyPoints,HangThanhVien AS"
                + " membership,AnhDaiDien AS avatarKey FROM NguoiDung WHERE MaNguoiDung=?",
            id);
    user.put("roles", roles(id));
    String avatarKey = Db.text(user, "avatarKey");
    if (!avatarKey.isEmpty()) {
      user.put("avatarUrl", "/api/v1/users/" + id + "/avatar?v=" + Idempotency.hash(avatarKey).substring(0, 8));
      if (!catalogBase.isEmpty()) {
        user.put("avatarDirectUrl", catalogBase + "/" + avatarKey);
      }
    } else {
      user.put("avatarUrl", null);
      user.put("avatarDirectUrl", null);
    }
    return user;
  }

  /** Lấy role hiện hành từ quan hệ n-n ERD. */
  public List<String> roles(String id) {
    return db
        .rows(
            "SELECT v.TenVaiTro FROM VaiTro v JOIN NguoiDung_VaiTro r ON r.MaVaiTro=v.MaVaiTro"
                + " WHERE r.MaNguoiDung=?",
            id)
        .stream()
        .map(r -> Db.text(r, "TenVaiTro"))
        .toList();
  }

  /** Xác nhận mật khẩu hiện tại trước thao tác nhạy cảm đổi email. */
  public void confirmPassword(String id,String input) {
    Problem.require(input!=null && input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=72,
      400,"PASSWORD_CONFIRMATION_REQUIRED","Đổi email cần currentPassword đúng.");
    var user=db.one("SELECT MatKhau FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE",id);
    Problem.require(passwords.matches(input,Db.text(user,"MatKhau")),400,"PASSWORD_CONFIRMATION_REQUIRED","Đổi email cần currentPassword đúng.");
  }

  /** Đổi mật khẩu, thu hồi mọi refresh và làm access token cũ vô hiệu. */
  @Transactional
  public void changePassword(String id, String oldPassword, String next) {
    validatePassword(next);
    Problem.require(
        oldPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72,
        400,
        "PASSWORD_MISMATCH",
        "Mật khẩu hiện tại không đúng.");
    var user = db.one("SELECT MatKhau FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE", id);
    Problem.require(
        passwords.matches(oldPassword, Db.text(user, "MatKhau")),
        400,
        "PASSWORD_MISMATCH",
        "Mật khẩu hiện tại không đúng.");
    db.exec(
        "UPDATE NguoiDung SET MatKhau=? WHERE MaNguoiDung=?",
        passwords.encode(next),
        id);
    db.runtime().bump(id);
  }

  /** Cấp access và refresh; refresh chỉ lưu SHA256 trong manifest riêng. */
  private Map<String, Object> tokens(String id) {
    var now = Instant.now();
    var user = db.one("SELECT MatKhau FROM NguoiDung WHERE MaNguoiDung=?", id);
    var claims =
        JwtClaimsSet.builder()
            .issuer("yofasoshop")
            .audience(List.of("yofasoshop-api"))
            .subject(id)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(900))
            .claim("authVersion", db.runtime().version(id))
            .claim("credentialStamp", Idempotency.hash(Db.text(user, "MatKhau")))
            .claim("roles", roles(id))
            .build();
    String access =
        encoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    byte[] random = new byte[48];
    new SecureRandom().nextBytes(random);
    String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    db.runtime().put("refresh", Idempotency.hash(refresh), Map.of(
        "TokenHash", Idempotency.hash(refresh), "MaNguoiDung", id,
        "ExpiresAt", now.plusSeconds(7L*86400).toEpochMilli(),
        "authVersion", db.runtime().version(id), "credentialStamp", Idempotency.hash(Db.text(user,"MatKhau"))));
    return Map.of(
        "accessToken",
        access,
        "refreshToken",
        refresh,
        "tokenType",
        "Bearer",
        "expiresIn",
        900,
        "user",
        me(id));
  }

  /** BCrypt giới hạn 72 byte, yêu cầu có chữ và số thay vì chỉ kiểm tra số ký tự. */
  public static void validatePassword(String value) {
    Problem.require(
        value.length() >= 10
            && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72
            && value.chars().anyMatch(Character::isLetter)
            && value.chars().anyMatch(Character::isDigit),
        422,
        "PASSWORD_WEAK",
        "Mật khẩu cần ít nhất 10 ký tự, có chữ/số và tối đa 72 byte UTF-8.");
  }
}
