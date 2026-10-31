package com.yofaso.shop;

import static org.junit.jupiter.api.Assertions.*;
import com.yofaso.shop.auth.*;
import com.yofaso.shop.common.Db;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** Regression: mã role trong seed SQL khác mã V1, đăng ký vẫn chỉ được CUSTOMER. */
@SpringBootTest
class RoleResolutionTest {
  @Autowired Db db;
  @Autowired AuthService auth;

  @Test @Transactional void registrationResolvesRoleByNameEvenWhenIdsAreSwapped() {
    String customer = Db.text(db.one("SELECT MaVaiTro FROM VaiTro WHERE TenVaiTro='ROLE_CUSTOMER'"), "MaVaiTro");
    String admin = Db.text(db.one("SELECT MaVaiTro FROM VaiTro WHERE TenVaiTro='ROLE_SHOP_ADMIN'"), "MaVaiTro");
    db.exec("UPDATE VaiTro SET TenVaiTro='ROLE_TEMP_TEST' WHERE MaVaiTro=?", customer);
    db.exec("UPDATE VaiTro SET TenVaiTro='ROLE_CUSTOMER' WHERE MaVaiTro=?", admin);
    db.exec("UPDATE VaiTro SET TenVaiTro='ROLE_SHOP_ADMIN' WHERE MaVaiTro=?", customer);
    var result = auth.register(new AuthController.Register("role_" + UUID.randomUUID().toString().replace("-", ""),
        "TestRoleOnly2026!", "Role Test", "0991112223", null));
    @SuppressWarnings("unchecked") var user = (Map<String, Object>) result.get("user");
    assertEquals(List.of("ROLE_CUSTOMER"), user.get("roles"));
    String role = Db.text(db.one("SELECT MaVaiTro FROM NguoiDung_VaiTro WHERE MaNguoiDung=?", user.get("userId")), "MaVaiTro");
    assertEquals(admin, role);
  }
}
