package com.yofaso.shop;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.yofaso.shop.ai.*;
import com.yofaso.shop.common.*;
import com.yofaso.shop.order.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** HTTP thật + MySQL test riêng. Upstream AI dùng stub có nhãn simulated, không chạy model. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackendIntegrationTest {
  @LocalServerPort int port;
  @Autowired Db db;
  @Autowired PasswordEncoder passwords;
  @Autowired JsonMapper json;
  @Autowired AiService ai;
  @Autowired MediaService media;
  static HttpServer upstream;
  static byte[] png;
  static boolean jobExists = false;
  static int aiFailures = 0;
  String admin,
      customer,
      other,
      warehouse,
      shipper,
      cashier,
      userId,
      otherId,
      address,
      otherAddress,
      category,
      product,
      variant,
      bottom,
      createdOrder,
      session,
      personAsset;
  final HttpClient http = HttpClient.newHttpClient();
  final List<Map<String, Object>> httpCases = new java.util.concurrent.CopyOnWriteArrayList<>();

  /** Stub HTTP kiểm tra key và body thật, không gọi trực tiếp service giả trong controller. */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) throws Exception {
    var image = new BufferedImage(256, 384, BufferedImage.TYPE_INT_RGB);
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    png = out.toByteArray();
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext(
        "/",
        x -> {
          String path = x.getRequestURI().getPath();
          String response = "{}";
          int status = 200;
          var mapper = JsonMapper.builder().build();
          if (!"test-ai-internal-key-with-32-bytes-minimum"
              .equals(x.getRequestHeaders().getFirst("X-Internal-API-Key"))) {
            status = 401;
          } else if (aiFailures > 0) {
            aiFailures--;
            status = 503;
          } else if (path.endsWith("/content")) {
            x.getResponseHeaders().set("Content-Type", "image/png");
            x.sendResponseHeaders(200, png.length);
            x.getResponseBody().write(png);
            x.close();
            return;
          } else if (path.equals("/internal/v1/assets")) {
            response = "{\"assetId\":\"remoteAsset\"}";
          } else if (path.equals("/internal/v1/persons/validate")) {
            response = "{\"accepted\":true,\"personFrame\":\"full_body\",\"simulated\":true}";
          } else if (path.startsWith("/internal/v1/try-on/jobs/") && !jobExists) {
            status = 404;
          } else if (path.equals("/internal/v1/try-on/jobs")) {
            var body = mapper.readValue(new String(x.getRequestBody().readAllBytes()), Map.class);
            assertEquals("upper_body", body.get("vtonCategory"));
            jobExists = true;
            response = "{\"jobId\":\"testJob\",\"status\":\"QUEUED\",\"simulated\":true}";
          } else if (path.endsWith("/cancel")) {
            response = "{\"jobId\":\"testJob\",\"status\":\"CANCELLED\",\"simulated\":true}";
          } else if (path.endsWith("/retry")) {
            response = "{\"jobId\":\"testJob\",\"status\":\"QUEUED\",\"simulated\":true}";
          } else
            response =
                "{\"jobId\":\"testJob\",\"status\":\"COMPLETED\",\"simulated\":true,\"result\":{\"resultAsset\":{\"assetId\":\"remoteResult\"},\"quality\":{\"evaluated\":false}}}";
          byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          x.getResponseHeaders().set("Content-Type", "application/json");
          x.sendResponseHeaders(status, bytes.length);
          x.getResponseBody().write(bytes);
          x.close();
        });
    upstream.start();
    r.add("app.ai.base-url", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    r.add("app.ai.key", () -> "test-ai-internal-key-with-32-bytes-minimum");
    r.add("app.ai.enabled", () -> false);
    r.add("app.rate-limit", () -> 100000);
    var registry = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"),"registry-");
    r.add("app.runtime-root", registry::toString);
    r.add("app.storage-root", () -> "./data/test-media/" + UUID.randomUUID());
  }

  /** Tạo nhân sự test bằng SQL riêng của fixture, không phát hành tài khoản mặc định. */
  @BeforeAll
  void fixtures() {
    admin = staff("admintest", "VT001", "0901000001");
    warehouse = staff("warehousetest", "VT004", "0901000002");
    shipper = staff("shippertest", "VT005", "0901000003");
    cashier = staff("cashiertest", "VT003", "0901000004");
  }

  /** Kết thúc stub sau suite; MySQL riêng do script bên ngoài dừng. */
  @AfterAll
  void cleanup() throws Exception {
    java.nio.file.Files.writeString(
        java.nio.file.Path.of("target/http-cases.json"),
        json.writerWithDefaultPrettyPrinter().writeValueAsString(httpCases));
    upstream.stop(0);
  }

  /** Tạo user nhân sự để kiểm tra đúng RBAC từng vai trò. */
  String staff(String name, String role, String phone) {
    String id = Db.id("ND");
    db.exec(
        "INSERT INTO NguoiDung(MaNguoiDung,TenDangNhap,MatKhau,HoVaTen,SoDienThoai)"
            + " VALUES(?,?,?,?,?)",
        id,
        name,
        passwords.encode("TestingPassword123"),
        name,
        phone);
    db.exec("INSERT INTO NguoiDung_VaiTro(MaNguoiDung,MaVaiTro) VALUES(?,?)", id, role);
    return token(
        call(
            "POST",
            "/auth/login",
            null,
            Map.of("username", name, "password", "TestingPassword123"),
            200));
  }

  /** Gửi HTTP JSON thật và xác nhận status, không nuốt response khi test lỗi. */
  Map<String, Object> call(String method, String path, String token, Object body, int expected) {
    return call(method, path, token, body, expected, null);
  }

  /** Gửi request có idempotency khi cần. */
  @SuppressWarnings("unchecked")
  Map<String, Object> call(
      String method, String path, String token, Object body, int expected, String key) {
    try {
      var req =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
              .header("Content-Type", "application/json");
      if (token != null) req.header("Authorization", "Bearer " + token);
      if (key != null) req.header("Idempotency-Key", key);
      if (key == null && path.equals("/staff/inventory/adjustments"))
        req.header("Idempotency-Key", "test-adjust-" + UUID.randomUUID());
      req.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      var res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
      httpCases.add(
          Map.of(
              "method",
              method,
              "path",
              path.split("\\?")[0],
              "expected",
              expected,
              "actual",
              res.statusCode()));
      assertEquals(expected, res.statusCode(), path + " " + res.body());
      assertTrue(res.headers().firstValue("X-Correlation-ID").isPresent());
      if (res.body().startsWith("["))
        return Map.of("items", json.readValue(res.body(), List.class));
      return json.readValue(res.body(), Map.class);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Lấy token từ response login/register. */
  String token(Map<String, Object> response) {
    return response.get("accessToken").toString();
  }

  /** Request fixture đăng ký tài khoản khách. */
  Map<String, Object> registration(String name, String phone) {
    return Map.of(
        "username",
        name,
        "password",
        "CustomerPassword123",
        "fullName",
        name,
        "phone",
        phone,
        "email",
        name + "@example.test");
  }

  /** Xác nhận public/private và hai vai trò khách/nhân sự. */
  @Test
  @Order(1)
  void authenticationAndOwnership() {
    call("GET", "/catalog/categories", null, null, 200);
    call("GET", "/me", null, null, 401);
    call("GET", "/admin/users", cashier, null, 403);
    var one =
        call("POST", "/auth/register", null, registration("customertest", "0902000001"), 201);
    customer = token(one);
    userId = ((Map<?, ?>) one.get("user")).get("userId").toString();
    var two = call("POST", "/auth/register", null, registration("othertest", "0902000002"), 201);
    other = token(two);
    otherId = ((Map<?, ?>) two.get("user")).get("userId").toString();
    call("POST", "/auth/register", null, registration("customertest", "0902000001"), 409);
    call(
        "POST", "/auth/login", null, Map.of("username", "customertest", "password", "WrongPassword123"), 401);
    var bad = new HashMap<>(registration("hackertest", "0902000003"));
    bad.put("roleIds", List.of("VT001"));
    call("POST", "/auth/register", null, bad, 400);
    String refresh = one.get("refreshToken").toString();
    var rotated = call("POST", "/auth/refresh", null, Map.of("refreshToken", refresh), 200);
    call("POST", "/auth/refresh", null, Map.of("refreshToken", refresh), 401);
    call(
        "POST", "/auth/logout", customer, Map.of("refreshToken", rotated.get("refreshToken")), 200);
    call("POST", "/auth/refresh", null, Map.of("refreshToken", rotated.get("refreshToken")), 401);
    address =
        call(
                "POST",
                "/me/addresses",
                customer,
                Map.of(
                    "recipientName",
                    "Khách Test",
                    "phone",
                    "0902000001",
                    "street",
                    "1 Test",
                    "ward",
                    "Phường Hải Châu",
                    "city",
                    "Đà Nẵng",
                    "defaultAddress",
                    true),
                201)
            .get("addressId")
            .toString();
    otherAddress =
        call(
                "POST",
                "/me/addresses",
                other,
                Map.of(
                    "recipientName",
                    "Other",
                    "phone",
                    "0902000002",
                    "street",
                    "2 Test",
                    "ward",
                    "Phường Hải Châu",
                    "city",
                    "Đà Nẵng",
                    "defaultAddress",
                    true),
                201)
            .get("addressId")
            .toString();
    call("DELETE", "/me/addresses/" + address, other, null, 404);
  }

  /** Catalog draft không lộ public, SKU không nhận tồn do client tự sửa. */
  @Test
  @Order(2)
  void catalog() {
    category =
        call("POST", "/admin/categories", admin, Map.of("name", "Áo", "position", 0), 201)
            .get("categoryId")
            .toString();
    var body =
        Map.ofEntries(
            Map.entry("categoryId", category),
            Map.entry("name", "Áo sơ mi"),
            Map.entry("fit", "SUONG_VUA"),
            Map.entry("waist", "KHONG_AP_DUNG"),
            Map.entry("length", "AO_CROPTOP"),
            Map.entry("pattern", "VAI_TRON"),
            Map.entry("vtonCategory", "upper_body"),
            Map.entry("subtype", "short_sleeved_shirt"));
    product = call("POST", "/admin/products", admin, body, 201).get("productId").toString();
    call("GET", "/catalog/products/" + product, null, null, 404);
    call("POST", "/admin/products/" + product + "/publish", admin, Map.of(), 409);
    variant =
        call(
                "POST",
                "/admin/products/" + product + "/variants",
                admin,
                Map.of(
                    "sku",
                    "TEST-WHITE-M",
                    "size",
                    "M",
                    "colorName",
                    "Trắng",
                    "colors",
                    "#FFFFFF",
                    "price",
                    100000,
                    "status",
                    "DANG_BAN"),
                201)
            .get("variantId")
            .toString();
    var bottomBody = new HashMap<String, Object>(body);
    bottomBody.put("name", "Quần đen cạp cao");
    bottomBody.put("waist", "CAP_CAO");
    bottomBody.put("length", "DAI_CHAM_GOT");
    bottomBody.put("vtonCategory", "lower_body");
    bottomBody.put("subtype", "trousers");
    String bp = call("POST", "/admin/products", admin, bottomBody, 201).get("productId").toString();
    bottom =
        call(
                "POST",
                "/admin/products/" + bp + "/variants",
                admin,
                Map.of(
                    "sku",
                    "TEST-BLACK-M",
                    "size",
                    "M",
                    "colorName",
                    "Đen",
                    "colors",
                    "#1A1A1A",
                    "price",
                    200000,
                    "status",
                    "DANG_BAN"),
                201)
            .get("variantId")
            .toString();
    // Fixture bán hàng đã duyệt: không giả vờ đây là kết quả AI thật.
    db.exec(
        "UPDATE SanPham SET TrangThaiSanPham='DANG_KINH_DOANH' WHERE MaSanPham IN (?,?)",
        product,
        bp);
    for (String id : List.of(variant, bottom))
      call(
          "POST",
          "/staff/inventory/adjustments",
          warehouse,
          Map.of("variantId", id, "delta", 10, "reason", "Fixture test"),
          200);
    call(
        "POST",
        "/staff/inventory/adjustments",
        customer,
        Map.of("variantId", variant, "delta", 100, "reason", "hack"),
        403);
    call(
        "POST",
        "/staff/inventory/adjustments",
        warehouse,
        Map.of("variantId", variant, "delta", -100, "reason", "âm"),
        409);
    call("GET", "/catalog/products?q=Áo", null, null, 200);
    call("GET", "/catalog/products?page=-1", null, null, 400);
  }

  /** Checkout chống giá giả, key trùng và hoàn tồn hai lần khi hủy. */
  @Test
  @Order(3)
  void checkoutAndCancel() {
    call("PUT", "/cart/items", customer, Map.of("variantId", variant, "quantity", 2), 200);
    var checkout =
        Map.of("addressId", address, "items", List.of(Map.of("variantId", variant, "quantity", 2)));
    var result = call("POST", "/orders", customer, checkout, 201, "order-cancel-test");
    String id = result.get("orderId").toString();
    assertEquals(
        id, call("POST", "/orders", customer, checkout, 201, "order-cancel-test").get("orderId"));
    assertEquals(
        8, db.count("SELECT SoLuongTonKho FROM BienTheSanPham WHERE MaBienThe=?", variant));
    call("GET", "/orders/" + id, other, null, 404);
    call(
        "POST",
        "/orders",
        customer,
        Map.of("addressId", address, "items", List.of(Map.of("variantId", variant, "quantity", 3))),
        409,
        "order-cancel-test");
    call("POST", "/orders/" + id + "/cancel", customer, Map.of(), 200);
    call("POST", "/orders/" + id + "/cancel", customer, Map.of(), 200);
    assertEquals(
        10, db.count("SELECT SoLuongTonKho FROM BienTheSanPham WHERE MaBienThe=?", variant));
    var priceHack = new HashMap<String, Object>(checkout);
    priceHack.put("total", 1);
    call("POST", "/orders", customer, priceHack, 400, "price-hack-key");
    call(
        "POST",
        "/orders",
        customer,
        Map.of(
            "addressId",
            otherAddress,
            "items",
            List.of(Map.of("variantId", variant, "quantity", 1))),
        404,
        "address-hack-key");
  }

  /** Luồng kho→shipper→review và thông báo có quyền đầy đủ. */
  @Test
  @Order(4)
  void fulfillmentAndReviews() {
    var r =
        call(
            "POST",
            "/orders",
            customer,
            Map.of(
                "addressId",
                address,
                "items",
                List.of(Map.of("variantId", variant, "quantity", 1))),
            201,
            "delivery-test-key");
    createdOrder = r.get("orderId").toString();
    String line = ((Map<?, ?>) ((List<?>) r.get("items")).getFirst()).get("orderLineId").toString();
    call(
        "POST",
        "/reviews",
        customer,
        Map.of("orderLineId", line, "stars", 5, "comment", "Test"),
        404);
    call("POST", "/staff/orders/" + createdOrder + "/pack", warehouse, Map.of(), 200);
    call("POST", "/orders/" + createdOrder + "/cancel", customer, Map.of(), 409);
    String shipperId =
        db.one("SELECT MaNguoiDung FROM NguoiDung WHERE TenDangNhap='shippertest'")
            .get("MaNguoiDung")
            .toString();
    call(
        "POST",
        "/staff/orders/" + createdOrder + "/assign",
        warehouse,
        Map.of("shipperId", shipperId),
        200);
    call("POST", "/staff/orders/" + createdOrder + "/deliver", shipper, Map.of(), 200);
    call("POST", "/staff/orders/" + createdOrder + "/deliver", shipper, Map.of(), 409);
    String review =
        call(
                "POST",
                "/reviews",
                customer,
                Map.of("orderLineId", line, "stars", 5, "comment", "Đã nhận đúng sản phẩm"),
                201)
            .get("reviewId")
            .toString();
    call(
        "POST",
        "/reviews",
        customer,
        Map.of("orderLineId", line, "stars", 5, "comment", "trùng"),
        409);
    call(
        "PATCH",
        "/admin/reviews/" + review,
        admin,
        Map.of("status", "DA_HIEN_THI", "reply", "Cảm ơn bạn"),
        200);
    call("GET", "/catalog/products/" + product + "/reviews", null, null, 200);
    call("GET", "/notifications", customer, null, 200);
  }

  /** Hai khách tranh SKU cuối: đúng một đơn thành công và tồn không âm. */
  @Test
  @Order(5)
  void concurrentLastStock() throws Exception {
    db.exec("UPDATE BienTheSanPham SET SoLuongTonKho=1 WHERE MaBienThe=?", bottom);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = new ArrayList<Future<Integer>>();
      for (int i = 0; i < 2; i++) {
        int n = i;
        futures.add(
            pool.submit(
                () -> {
                  var req =
                      HttpRequest.newBuilder(
                              URI.create("http://127.0.0.1:" + port + "/api/v1/orders"))
                          .header("Authorization", "Bearer " + (n == 0 ? customer : other))
                          .header("Content-Type", "application/json")
                          .header("Idempotency-Key", "last-stock-key-" + n)
                          .POST(
                              HttpRequest.BodyPublishers.ofString(
                                  json.writeValueAsString(
                                      Map.of(
                                          "addressId",
                                          n == 0 ? address : otherAddress,
                                          "items",
                                          List.of(Map.of("variantId", bottom, "quantity", 1))))))
                          .build();
                  return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
                }));
      }
      var codes = List.of(futures.get(0).get(), futures.get(1).get());
      assertTrue(codes.contains(201) && codes.contains(409), codes.toString());
      assertEquals(
          0, db.count("SELECT SoLuongTonKho FROM BienTheSanPham WHERE MaBienThe=?", bottom));
    }
    db.exec("UPDATE BienTheSanPham SET SoLuongTonKho=10 WHERE MaBienThe=?", bottom);
  }

  /** POS giữ đơn trong ca, tiền không đủ bị rollback, admin duyệt ca đã đóng. */
  @Test
  @Order(6)
  void posAndShift() {
    String shift =
        call("POST", "/pos/shifts", cashier, Map.of("amount", 100000), 200)
            .get("shiftId")
            .toString();
    call("POST", "/pos/shifts", cashier, Map.of("amount", 100000), 409);
    call(
        "POST",
        "/pos/orders",
        cashier,
        Map.of("items", List.of(Map.of("variantId", variant, "quantity", 1)), "cashReceived", 1),
        422,
        "pos-cash-test-key");
    var result =
        call(
            "POST",
            "/pos/orders",
            cashier,
            Map.of(
                "items",
                List.of(Map.of("variantId", variant, "quantity", 1)),
                "cashReceived",
                200000),
            201,
            "pos-cash-test-key");
    assertEquals("HOAN_TAT", result.get("status"));
    call("POST", "/pos/shifts/" + shift + "/close", cashier, Map.of("amount", 200000), 200);
    call("POST", "/admin/shifts/" + shift + "/approve", admin, Map.of(), 200);
  }

  /** Điểm tra bảng Excel, không nhận điểm do client tự gán. */
  @Test
  @Order(7)
  void scoring() {
    var result =
        call(
            "POST",
            "/scoring/evaluate",
            customer,
            Map.of("firstVariantId", variant, "secondVariantId", bottom, "tucked", true),
            200);
    assertEquals(100, result.get("total"));
    assertEquals("RECOMMENDED", result.get("tier"));
    call(
        "GET",
        "/scoring/recommendations?variantId=" + variant + "&tucked=true",
        customer,
        null,
        200);
    call(
        "POST",
        "/scoring/evaluate",
        customer,
        Map.of("firstVariantId", variant, "tucked", false),
        422);
    // Phiên thành công là fixture nghiệp vụ, không phải tuyên bố đã chạy GPU.
    String realFixture=Db.id("TD");
    db.exec("INSERT INTO PhienThuDoAo(MaPhienThuDo,MaNguoiDung,MaBienThe,LoaiKhungAnhNguoi,DuongDanAnhNguoiDung,TrangThaiPhien) VALUES(?,?,?,'FULL_BODY','fixture-only.png','THANH_CONG')",realFixture,userId,variant);
    call("POST","/scoring/sessions/"+realFixture+"/recommendations?tucked=true",customer,Map.of(),200);
    assertTrue(db.count("SELECT COUNT(*) FROM GoiYPhoiDo WHERE MaPhienThuDo=? AND DiemSauKhiPhoiKem>=90",realFixture)>0);
    long count=db.count("SELECT COUNT(*) FROM GoiYPhoiDo WHERE MaPhienThuDo=?",realFixture);
    call("POST","/scoring/sessions/"+realFixture+"/recommendations?tucked=true",customer,Map.of(),200);
    assertEquals(count,db.count("SELECT COUNT(*) FROM GoiYPhoiDo WHERE MaPhienThuDo=?",realFixture));
    String recommendation=Db.text(db.one("SELECT MaGoiY FROM GoiYPhoiDo WHERE MaPhienThuDo=? LIMIT 1",realFixture),"MaGoiY");
    call("GET","/scoring/sessions/"+realFixture+"/recommendations",customer,null,200);
    call("GET","/scoring/sessions/"+realFixture+"/recommendations",other,null,404);
    call("PATCH","/scoring/recommendations/"+recommendation+"/interaction",customer,Map.of("status","DA_THU_THEM"),200);
    call("PATCH","/scoring/recommendations/"+recommendation+"/interaction",customer,Map.of("status","DA_MUA"),409);
    call("POST","/scoring/sessions/"+realFixture,customer,Map.of("firstVariantId",variant,"secondVariantId",bottom,"tucked",true),200);
    assertEquals(100,((Number)db.one("SELECT DiemTongThe FROM PhienThuDoAo WHERE MaPhienThuDo=?",realFixture).get("DiemTongThe")).intValue());
    assertEquals("RECOMMENDED", com.yofaso.shop.scoring.ScoringService.tier(85));
    assertEquals("ACCEPTABLE", com.yofaso.shop.scoring.ScoringService.tier(70));
    assertEquals("NEEDS_ADJUSTMENT", com.yofaso.shop.scoring.ScoringService.tier(50));
  }

  /** Gateway AI lưu kết quả bền, mock không thể duyệt publish, session có ownership. */
  @Test
  @Order(8)
  void aiGateway() {
    personAsset = media.save(userId, "person", png, false).get("assetId").toString();
    String adminId =
        db.one("SELECT MaNguoiDung FROM NguoiDung WHERE TenDangNhap='admintest'")
            .get("MaNguoiDung")
            .toString();
    String garment = media.save(adminId, "garment", png, false).get("assetId").toString();
    db.exec(
        "INSERT INTO HinhAnhSanPham(MaHinhAnh,MaSanPham,MaMauSac,DuongDanAnhGoc,DuongDanAnhBocNen)"
            + " VALUES(?,?,?,?,?)",
        Db.id("HA"),
        product,
        "#FFFFFF",
        garment,
        garment);
    call("POST", "/ai/persons/validate", other, Map.of("assetId", personAsset), 404);
    var validated =
        call("POST", "/ai/persons/validate", customer, Map.of("assetId", personAsset), 200);
    assertEquals(true, validated.get("simulated"));
    var body =
        Map.of(
            "variantId",
            variant,
            "personAssetId",
            personAsset,
            "seed",
            42,
            "steps",
            20,
            "samples",
            1,
            "consent",
            true);
    session =
        call("POST", "/try-on/sessions", customer, body, 202, "try-on-test-key")
            .get("operationId")
            .toString();
    assertEquals(
        session,
        call("POST", "/try-on/sessions", customer, body, 202, "try-on-test-key")
            .get("operationId"));
    call("GET", "/try-on/sessions/" + session, other, null, 404);
    ai.processDue();
    db.runtime().patch("operations", session,"NextAttemptAt",0L);
    ai.processDue();
    var result = call("GET", "/try-on/sessions/" + session, customer, null, 200);
    assertEquals("COMPLETED", result.get("status"));
    assertEquals(true, result.get("simulated"));
    assertNotNull(((Map<?, ?>) result.get("result")).get("resultAssetId"));
    call("GET", "/assets/" + personAsset + "/content", other, null, 404);
  }

  /** Lỗi mạng giữ trạng thái đối soát, không báo GPU thất bại giả. */
  @Test
  @Order(9)
  void aiFailureAndRetry() {
    jobExists = false;
    var body =
        Map.of(
            "variantId",
            variant,
            "personAssetId",
            personAsset,
            "seed",
            43,
            "steps",
            20,
            "samples",
            1,
            "consent",
            true);
    String id =
        call("POST", "/try-on/sessions", customer, body, 202, "try-on-failure-key")
            .get("operationId")
            .toString();
    aiFailures = 1;
    ai.processDue();
    assertEquals(
        "RECONCILING", call("GET", "/try-on/sessions/" + id, customer, null, 200).get("status"));
    call("POST", "/try-on/sessions/" + id + "/cancel", customer, Map.of(), 409);
    db.runtime().patch("operations", id,"NextAttemptAt",0L);
    ai.processDue();
    call("POST", "/try-on/sessions/" + id + "/cancel", customer, Map.of(), 200);
  }

  /** Đổi mật khẩu làm access và refresh cũ mất hiệu lực. */
  @Test
  @Order(10)
  void revokeAccount() {
    call(
        "POST",
        "/me/password",
        other,
        Map.of("currentPassword", "WRONG", "newPassword", "BrandNewPassword123"),
        400);
    call(
        "POST",
        "/me/password",
        other,
        Map.of("currentPassword", "CustomerPassword123", "newPassword", "BrandNewPassword123"),
        200);
    call("GET", "/me", other, null, 401);
    call("GET", "/admin/reports/summary", admin, null, 200);

  }

  /** Export contract từ route thực; baseline chỉ cập nhật bằng script export có chủ ý. */
  @Test
  @Order(11)
  void contract() throws Exception {
    assertEquals(19,db.count("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'"));
    assertEquals(0,db.count("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME IN ('AuthVersion','Simulated','AiMetadata','MaskAssetId')"));
    var contract = call("GET", "/contract", null, null, 200);
    assertEquals("3.1.0", contract.get("openapi"));
    var paths = (Map<?, ?>) contract.get("paths");
    assertTrue(paths.containsKey("/api/v1/orders"));
    java.nio.file.Files.writeString(
        java.nio.file.Path.of("target/openapi-actual.json"),
        json.writerWithDefaultPrettyPrinter().writeValueAsString(contract));
  }

  /** Voucher consume và hủy không bị đếm trùng khi retry. */
  @Test
  @Order(12)
  void voucher() {
    String voucher =
        call(
                "POST",
                "/admin/promotions",
                admin,
                Map.of(
                    "code",
                    "TEST10",
                    "name",
                    "Test",
                    "type",
                    "PHAN_TRAM",
                    "value",
                    10,
                    "minimumTotal",
                    0,
                    "maxDiscount",
                    50000,
                    "quantity",
                    1,
                    "startsAt",
                    "2026-01-01T00:00:00Z",
                    "endsAt",
                    "2030-01-01T00:00:00Z"),
                201)
            .get("promotionId")
            .toString();
    var body =
        Map.of(
            "addressId",
            address,
            "items",
            List.of(Map.of("variantId", variant, "quantity", 1)),
            "voucher",
            "TEST10");
    var r = call("POST", "/orders", customer, body, 201, "voucher-order-key");
    assertEquals(10000, new java.math.BigDecimal(r.get("discount").toString()).intValue());
    call("POST", "/orders", customer, body, 201, "voucher-order-key");
    call("POST", "/orders", customer, body, 409, "voucher-second-key");
    call("POST", "/orders/" + r.get("orderId") + "/cancel", customer, Map.of(), 200);
    assertEquals(0, db.count("SELECT SoLuongDaDung FROM KhuyenMai WHERE MaKhuyenMai=?", voucher));
  }

  /** Ảnh sai định dạng, role giả và pixel quá nhỏ bị chặn trước AI. */
  @Test
  @Order(13)
  void imageValidation() throws Exception {
    var tiny = new BufferedImage(30, 40, BufferedImage.TYPE_INT_ARGB);
    var tinyBytes = new ByteArrayOutputStream();
    ImageIO.write(tiny, "png", tinyBytes);
    assertThrows(Problem.class, () -> media.save(userId, "person", tinyBytes.toByteArray(), false));
    String cutout =
        media.save(userId, "cutout", tinyBytes.toByteArray(), false).get("assetId").toString();
    var padded = ImageIO.read(new ByteArrayInputStream(media.garmentInput(cutout)));
    assertEquals(128, padded.getWidth());
    assertEquals(128, padded.getHeight());
    assertEquals(30, ImageIO.read(new ByteArrayInputStream(media.bytes(cutout))).getWidth());
    assertThrows(
        Problem.class, () -> media.save(userId, "person", "not an image".getBytes(), false));
    assertThrows(
        Problem.class, () -> media.save(userId, "person", new byte[21 * 1024 * 1024], false));
    call(
        "POST",
        "/try-on/sessions",
        customer,
        Map.of(
            "variantId",
            variant,
            "personAssetId",
            personAsset,
            "seed",
            42,
            "steps",
            3,
            "samples",
            1,
            "consent",
            true),
        422,
        "invalid-steps-key");
  }

  /** API multipart, consent và xóa asset riêng chưa có tham chiếu. */
  @Test
  @Order(14)
  void multipartAndDelete() throws Exception {
    String boundary = "YofasoBoundary";
    var output = new ByteArrayOutputStream();
    output.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"role\"\r\n\r\nperson\r\n--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"consent\"\r\n\r\ntrue\r\n--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"anh bat ky.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n")
            .getBytes());
    output.write(png);
    output.write(("\r\n--" + boundary + "--\r\n").getBytes());
    var req =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/assets"))
            .header("Authorization", "Bearer " + customer)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray()))
            .build();
    var res = http.send(req, HttpResponse.BodyHandlers.ofString());
    assertEquals(201, res.statusCode(), res.body());
    httpCases.add(
        Map.of("method", "POST", "path", "/assets", "expected", 201, "actual", res.statusCode()));
    String id = json.readTree(res.body()).get("assetId").asText();
    call("DELETE", "/assets/" + id, customer, null, 200);
    call("DELETE", "/assets/" + personAsset, customer, null, 409);
    call(
        "POST",
        "/try-on/sessions",
        customer,
        Map.of(
            "variantId",
            variant,
            "personAssetId",
            personAsset,
            "seed",
            42,
            "steps",
            20,
            "samples",
            1,
            "consent",
            false),
        422,
        "no-consent-key");
  }

  /** Chặn publish mock; thử approve/publish dữ liệu fixture để kiểm tra FK và mapping SKU. */
  @Test
  @Order(15)
  void approvalAndPublish() {
    String adminId =
        db.one("SELECT MaNguoiDung FROM NguoiDung WHERE TenDangNhap='admintest'")
            .get("MaNguoiDung")
            .toString();
    String source = media.save(adminId, "garment", png, false).get("assetId").toString(),
        cutout = media.save(adminId, "cutout", png, false).get("assetId").toString(),
        mask = media.save(adminId, "mask", png, false).get("assetId").toString();
    String id = Db.id("PA");
    var result =
        Map.of(
            "vtonCategory",
            "upper_body",
            "variants",
            List.of(
                Map.of(
                    "sourceAssetId",
                    source,
                    "cutoutAssetId",
                    cutout,
                    "maskAssetId",
                    mask,
                    "category",
                    "short_sleeved_shirt","originalKey","catalog/products/test/img01-original.png",
                    "cutoutKey","catalog/products/test/img01-cutout.png","maskKey","catalog/products/test/img01-mask.png")));
    db.runtime().put("operations",id,RuntimeState.row("OperationId",id,"OwnerId",adminId,
      "KindName","PRODUCT_ANALYSIS","ResourceId",product,"Payload","{}","StateName","COMPLETED",
      "ResultJson",json.writeValueAsString(result),"Simulated",true,"CreatedAt",0L));
    var body = Map.of("bindings", List.of(Map.of("variantId", variant, "sourceAssetId", source)));
    call("POST", "/admin/analyses/" + id + "/approve", admin, body, 409);
    db.runtime().patch("operations", id,"Simulated",false);
    call("POST", "/admin/analyses/" + id + "/approve", admin, body, 200);
    call("POST", "/admin/products/" + product + "/publish", admin, Map.of(), 200);
    call("GET", "/catalog/products/" + product, null, null, 200);
  }

  /** Khóa user có hiệu lực với token đã cấp, admin không được tự khóa mình. */
  @Test
  @Order(16)
  void staffAdministration() {
    String created =
        call(
                "POST",
                "/admin/users",
                admin,
                Map.of(
                    "account",
                    registration("staffnewtest", "0904000001"),
                    "roleIds",
                    List.of("VT004")),
                201)
            .get("userId")
            .toString();
    String access =
        token(
            call(
                "POST",
                "/auth/login",
                null,
                Map.of("username", "staffnewtest", "password", "CustomerPassword123"),
                200));
    call("GET", "/staff/inventory/low-stock", access, null, 200);
    call(
        "PATCH",
        "/admin/users/" + created + "/status",
        admin,
        Map.of("status", "KHOA_TAM_THOI"),
        200);
    call("GET", "/me", access, null, 401);
    String adminId =
        db.one("SELECT MaNguoiDung FROM NguoiDung WHERE TenDangNhap='admintest'")
            .get("MaNguoiDung")
            .toString();
    call(
        "PATCH",
        "/admin/users/" + adminId + "/status",
        admin,
        Map.of("status", "KHOA_TAM_THOI"),
        409);
  }

  /** Các thao tác đọc/sửa còn lại và nhánh giao thất bại→nhận hoàn không trừ/hoàn tồn hai lần. */
  @Test
  @Order(17)
  void remainingWorkflow() {
    call("GET", "/me", customer, null, 200);
    call(
        "PATCH",
        "/me",
        customer,
        Map.of(
            "fullName",
            "Khách Test Đã Sửa",
            "email",
            "updated@example.test",
            "currentPassword",
            "CustomerPassword123",
            "phone",
            "0902000001"),
        200);
    call("GET", "/me/addresses", customer, null, 200);
    call(
        "PUT",
        "/me/addresses/" + address,
        customer,
        Map.of(
            "recipientName",
            "Khách Test",
            "phone",
            "0902000001",
            "street",
            "1 Test",
            "ward",
            "Phường Hải Châu",
            "city",
            "Đà Nẵng",
            "defaultAddress",
            true),
        200);
    for (String path :
        List.of(
            "/admin/users",
            "/admin/roles",
            "/admin/reviews",
            "/admin/products",
            "/admin/products/" + product)) call("GET", path, admin, null, 200);
    for (String path :
        List.of(
            "/staff/orders",
            "/staff/orders/" + createdOrder,
            "/staff/inventory/low-stock",
            "/staff/inventory/movements?variantId=" + variant,
            "/staff/variants/by-sku?sku=TEST-WHITE-M")) call("GET", path, warehouse, null, 200);
    call("GET", "/staff/orders/" + createdOrder, cashier, null, 404);
    call("GET", "/promotions", null, null, 200);
    call(
        "POST",
        "/promotions/quote",
        customer,
        Map.of("items", List.of(Map.of("variantId", variant, "quantity", 1))),
        200);
    call("GET", "/scoring/rules", customer, null, 200);
    call(
        "POST",
        "/scoring/sessions/" + session,
        customer,
        Map.of("firstVariantId", variant, "secondVariantId", bottom, "tucked", true),
        409);
    var notices = (List<?>) call("GET", "/notifications", customer, null, 200).get("items");
    String notice = ((Map<?, ?>) notices.getFirst()).get("notificationId").toString();
    call("POST", "/notifications/" + notice + "/read", customer, Map.of(), 200);
    var order =
        call(
            "POST",
            "/orders",
            customer,
            Map.of(
                "addressId",
                address,
                "items",
                List.of(Map.of("variantId", variant, "quantity", 1))),
            201,
            "return-flow-key");
    String id = order.get("orderId").toString();
    String sid =
        db.one("SELECT MaNguoiDung FROM NguoiDung WHERE TenDangNhap='shippertest'")
            .get("MaNguoiDung")
            .toString();
    call("POST", "/staff/orders/" + id + "/pack", warehouse, Map.of(), 200);
    call("POST", "/staff/orders/" + id + "/assign", warehouse, Map.of("shipperId", sid), 200);
    call("POST", "/staff/orders/" + id + "/delivery-failed", shipper, Map.of(), 200);
    call("POST", "/staff/orders/" + id + "/assign", warehouse, Map.of("shipperId", sid), 200);
    long before = db.count("SELECT SoLuongTonKho FROM BienTheSanPham WHERE MaBienThe=?", variant);
    call("POST", "/staff/orders/" + id + "/receive-return", warehouse, Map.of(), 200);
    call("POST", "/staff/orders/" + id + "/receive-return", warehouse, Map.of(), 200);
    assertEquals(
        before + 1,
        db.count("SELECT SoLuongTonKho FROM BienTheSanPham WHERE MaBienThe=?", variant));
    String garbageCategory =
        call("POST", "/admin/categories", admin, Map.of("name", "Tạm ẩn", "position", 1), 201)
            .get("categoryId")
            .toString();
    call("DELETE", "/admin/categories/" + garbageCategory, admin, null, 200);
    String promotion =
        db.one("SELECT MaKhuyenMai FROM KhuyenMai WHERE MaPhieuGiamGia='TEST10'")
            .get("MaKhuyenMai")
            .toString();
    call("DELETE", "/admin/promotions/" + promotion, admin, null, 200);
    String analysis = db.runtime().find("operations",r -> "PRODUCT_ANALYSIS".equals(r.get("KindName"))).getFirst().get("OperationId").toString();
    call("GET", "/admin/analyses/" + analysis, admin, null, 200);
    call("GET", "/try-on/sessions", customer, null, 200);
    call(
        "POST",
        "/ai/routing/preview",
        customer,
        Map.of("vtonCategory", "upper_body", "personFrame", "full_body", "garmentLength", "SHORT"),
        200);
  }

  /** Kiểm tra các route cập nhật và binary còn lại trước khi khóa contract bản đầu. */
  @Test
  @Order(18)
  void remainingEndpoints() throws Exception {
    call("GET", "/cart", customer, null, 200);
    call("DELETE", "/cart/items/" + variant, customer, null, 200);
    call("GET", "/orders", customer, null, 200);
    call("GET", "/pos/customers?phone=0902000001", cashier, null, 200);
    String shift =
        db.one(
                "SELECT MaCaLamViec FROM CaLamViecThuNgan WHERE MaThuNgan=(SELECT MaNguoiDung FROM"
                    + " NguoiDung WHERE TenDangNhap='cashiertest') LIMIT 1")
            .get("MaCaLamViec")
            .toString();
    call("GET", "/pos/shifts/" + shift, cashier, null, 200);
    String target =
        db.one("SELECT MaNguoiDung FROM NguoiDung WHERE TenDangNhap='staffnewtest'")
            .get("MaNguoiDung")
            .toString();
    call(
        "PUT",
        "/admin/users/" + target + "/roles",
        admin,
        Map.of("roleIds", List.of("VT003")),
        200);
    String id =
        call(
                "POST",
                "/orders",
                customer,
                Map.of(
                    "addressId",
                    address,
                    "items",
                    List.of(Map.of("variantId", variant, "quantity", 1))),
                201,
                "admin-cancel-order-key")
            .get("orderId")
            .toString();
    call("POST", "/admin/orders/" + id + "/cancel", admin, Map.of(), 200);
    var body =
        Map.ofEntries(
            Map.entry("categoryId", category),
            Map.entry("name", "Áo cập nhật"),
            Map.entry("fit", "SUONG_VUA"),
            Map.entry("waist", "KHONG_AP_DUNG"),
            Map.entry("length", "AO_CROPTOP"),
            Map.entry("pattern", "VAI_TRON"),
            Map.entry("vtonCategory", "upper_body"),
            Map.entry("subtype", "short_sleeved_shirt"));
    call("PUT", "/admin/products/" + product, admin, body, 200);
    call(
        "PUT",
        "/admin/products/" + product + "/variants/" + variant,
        admin,
        Map.of(
            "sku",
            "TEST-WHITE-M",
            "size",
            "M",
            "colorName",
            "Trắng",
            "colors",
            "#FFFFFF",
            "price",
            100000,
            "status",
            "DANG_BAN"),
        200);
    call("POST", "/admin/products/" + product + "/publish", admin, Map.of(), 200);
    String source =
        db.one("SELECT h.DuongDanAnhGoc FROM HinhAnhSanPham h JOIN BienTheSanPham b ON b.MaSanPham=h.MaSanPham AND b.MaMauSac=h.MaMauSac WHERE b.MaBienThe=? AND LENGTH(h.DuongDanAnhGoc)=32 LIMIT 1", variant)
            .get("DuongDanAnhGoc")
            .toString();
    var binary =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + "/api/v1/catalog/assets/"
                            + source
                            + "/content"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, binary.statusCode());
    assertTrue(binary.headers().firstValue("Content-Type").orElse("").startsWith("image/png"));
    httpCases.add(
        Map.of(
            "method",
            "GET",
            "path",
            "/catalog/assets/" + source + "/content",
            "expected",
            200,
            "actual",
            binary.statusCode()));
    String analysis =
        call(
                "POST",
                "/admin/products/" + product + "/analysis",
                admin,
                Map.of("productAssetIds", List.of(source), "referenceAssetIds", List.of()),
                202,
                "analysis-http-test-key")
            .get("operationId")
            .toString();
    db.runtime().patch("operations", analysis,"StateName","CANCELLED");
    db.runtime().patch("operations", session,"StateName","SYNC_FAILED");
    call("POST", "/try-on/sessions/" + session + "/retry", customer, Map.of(), 200);
    db.runtime().patch("operations", session,"StateName","COMPLETED");
    call("DELETE", "/admin/products/" + product, admin, null, 200);
    call("GET", "/catalog/products/" + product, null, null, 404);
  }

  /** Retry một phiếu nhập kho không làm tăng tồn hai lần, body khác cùng key bị từ chối. */
  @Test
  @Order(19)
  void inventoryRetrySafe() {
    long before = db.count("SELECT SoLuongTonKho FROM BienTheSanPham WHERE MaBienThe=?", bottom);
    var body = Map.of("variantId", bottom, "delta", 2, "reason", "Kiểm tra retry phiếu kho");
    var first =
        call("POST", "/staff/inventory/adjustments", warehouse, body, 200, "inventory-retry-key");
    var second =
        call("POST", "/staff/inventory/adjustments", warehouse, body, 200, "inventory-retry-key");
    assertEquals(first.get("movementId"), second.get("movementId"));
    assertEquals(
        before + 2, db.count("SELECT SoLuongTonKho FROM BienTheSanPham WHERE MaBienThe=?", bottom));
    call(
        "POST",
        "/staff/inventory/adjustments",
        warehouse,
        Map.of("variantId", bottom, "delta", 3, "reason", "Kiểm tra retry phiếu kho"),
        409,
        "inventory-retry-key");
  }
  /** Bộ lọc/nhãn tiếng Việt và tra cứu staff kiểm tra trên MySQL thật. */
  @Test @Order(20) @SuppressWarnings("unchecked") void filtersAndStaffLookups() {
    String fixture=Db.id("SP"), sku=Db.id("BT");
    db.exec("INSERT INTO SanPham(MaSanPham,MaDanhMuc,TenSanPham,ChatLieu,KieuDang,LoaiHoaTiet,TrangThaiSanPham) VALUES(?,?,'FILTERFIXTURE','Cotton Test','OM_SAT','VAI_TRON','DANG_KINH_DOANH')",fixture,category);
    db.exec("INSERT INTO BienTheSanPham(MaBienThe,MaSanPham,MaQuanLyHangHoa,TenMau,MaMauSac,GiaNiemYet,SoLuongTonKho) VALUES(?,?,?,'Xanh thử','#00FF00',100000,1)",sku,fixture,sku);
    var page=call("GET","/catalog/products?q=FILTERFIXTURE&material=Cotton%20Test&fit=OM_SAT&pattern=VAI_TRON&color=%2300FF00",null,null,200);
    var item=((List<Map<String,Object>>)page.get("items")).getFirst();
    assertEquals("Ôm sát",item.get("fitLabel"));assertEquals("Vải trơn",item.get("patternLabel"));
    assertEquals("OM_SAT",item.get("fit"));
    assertEquals(100000, ((Number)item.get("minPrice")).intValue());
    assertTrue(item.containsKey("coverUrl"));
    call("GET","/catalog/filters",null,null,200);
    call("GET","/catalog/products?fit=INVALID",null,null,400);
    db.exec("UPDATE SanPham SET TrangThaiSanPham='BAN_NHAP' WHERE MaSanPham=?",fixture);
    assertEquals(0,((List<?>)call("GET","/catalog/products?q=FILTERFIXTURE",null,null,200).get("items")).size());
    call("GET","/staff/products?status=BAN_NHAP&q=FILTERFIXTURE",warehouse,null,200);
    call("GET","/staff/products/"+fixture,warehouse,null,200);
    call("GET","/staff/product-filters",warehouse,null,200);
    call("GET","/staff/products",customer,null,403);
    call("GET","/admin/users/"+otherId,admin,null,200);
    call("PATCH","/admin/users/"+otherId,admin,Map.of("fullName","Updated Test Name","email","edited-"+otherId+"@example.test"),200);
    call("GET","/admin/users/"+otherId,customer,null,403);
    call("GET","/pos/shifts",cashier,null,200);
    call("GET","/staff/orders/"+createdOrder+"/pick-list",warehouse,null,200);
    String pos=Db.text(db.one("SELECT MaDonHang FROM DonHang WHERE KenhBanHang='TAI_QUAY_POS' LIMIT 1"),"MaDonHang");
    call("GET","/pos/orders/"+pos+"/receipt",admin,null,200);
    String phone=Db.text(db.one("SELECT SoDienThoai FROM NguoiDung WHERE MaNguoiDung=?",userId),"SoDienThoai");
    call("GET","/pos/try-on/sessions/"+session+"?phone="+phone,cashier,null,200);
    call("GET","/pos/try-on/sessions/"+session+"?phone=0990000000",cashier,null,404);
  }

}
