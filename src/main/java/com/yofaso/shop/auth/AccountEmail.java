package com.yofaso.shop.auth;

import com.yofaso.shop.common.*;

/** Ràng buộc nghiệp vụ email ngoài schema: các writer dùng chung một khóa hàng role. */
public final class AccountEmail {
  private AccountEmail() {}
  public static String reserve(Db db,String value,String owner) {
    if(value==null || value.isBlank()) return null;
    String normalized=PasswordResetService.email(value);
    db.one("SELECT MaVaiTro FROM VaiTro WHERE TenVaiTro='ROLE_CUSTOMER' FOR UPDATE");
    // Current read FOR UPDATE, không dùng snapshot cũ của REPEATABLE READ.
    var duplicates=db.rows("SELECT MaNguoiDung FROM NguoiDung WHERE LOWER(TRIM(Email))=? AND MaNguoiDung<>? FOR UPDATE",normalized,owner);
    Problem.require(duplicates.isEmpty(),409,"EMAIL_IN_USE","Email đã được dùng. Mỗi tài khoản test cần một alias +tag riêng.");
    return normalized;
  }
}
