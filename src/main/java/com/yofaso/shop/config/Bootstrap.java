package com.yofaso.shop.config;

import com.yofaso.shop.common.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Bootstrap admin tùy chọn, chỉ lần đầu database trống; không có mật khẩu mặc định. */
@Component
public class Bootstrap implements CommandLineRunner {
  private final Db db;
  private final PasswordEncoder encoder;
  private final String username, password, phone;

  /** Nhận credential bootstrap từ .env, không log hoặc trả ra API. */
  public Bootstrap(
      Db db,
      PasswordEncoder encoder,
      @Value("${app.bootstrap.username}") String username,
      @Value("${app.bootstrap.password}") String password,
      @Value("${app.bootstrap.phone}") String phone) {
    this.db = db;
    this.encoder = encoder;
    this.username = username;
    this.password = password;
    this.phone = phone;
  }

  /** Bỏ qua khi không cấu hình; không nâng tài khoản đã tồn tại lên admin. */
  @Override
  @Transactional
  public void run(String... args) {
    if (username.isBlank()) return;
    Problem.require(
        username.matches("[A-Za-z][A-Za-z0-9]{3,15}")
            && password.length() >= 14
            && password.length() <= 72
            && phone.matches("[0-9+]{9,15}"),
        500,
        "BOOTSTRAP_INVALID",
        "Kiểm tra thông tin bootstrap admin.");
    com.yofaso.shop.auth.AuthService.validatePassword(password);
    if (db.count(
            "SELECT COUNT(*) FROM NguoiDung n JOIN NguoiDung_VaiTro r ON"
                + " r.MaNguoiDung=n.MaNguoiDung JOIN VaiTro v ON v.MaVaiTro=r.MaVaiTro WHERE v.TenVaiTro='ROLE_SHOP_ADMIN'")
        > 0) return;
    Problem.require(
        db.count(
                "SELECT COUNT(*) FROM NguoiDung WHERE TenDangNhap=? OR SoDienThoai=?",
                username,
                phone)
            == 0,
        409,
        "BOOTSTRAP_CONFLICT",
        "Tên/điện thoại bootstrap đã tồn tại, không tự nâng quyền.");
    String id = Db.id("ND");
    db.exec(
        "INSERT INTO NguoiDung(MaNguoiDung,TenDangNhap,MatKhau,HoVaTen,SoDienThoai)"
            + " VALUES(?,?,?,?,?)",
        id,
        username,
        encoder.encode(password),
        "Chủ shop",
        phone);
    Problem.require(db.exec("INSERT INTO NguoiDung_VaiTro(MaNguoiDung,MaVaiTro)"
        + " SELECT ?,MaVaiTro FROM VaiTro WHERE TenVaiTro='ROLE_SHOP_ADMIN'", id) == 1,
        503, "ROLE_CONFIG_INVALID", "Database chưa có vai trò quản trị chuẩn.");
    db.audit(id, "BOOTSTRAP_ADMIN", id);
  }
}
