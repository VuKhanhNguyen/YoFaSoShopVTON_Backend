package com.yofaso.shop;

import static org.junit.jupiter.api.Assertions.*;

import com.yofaso.shop.ai.*;
import com.yofaso.shop.auth.*;
import com.yofaso.shop.common.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Test BE→FastAPI→mock worker→ảnh trên storage thật, không dùng CUDA hoặc model weights. */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActualAiIntegrationTest {
  static Process process;
  static int aiPort;
  static final String KEY = "actual-fastapi-test-only-secret-minimum-32-bytes";
  @Autowired AiService service;
  @Autowired AiClient client;
  @Autowired MediaService media;
  @Autowired Db db;
  @Autowired AuthService auth;

  /** Chạy FastAPI trong process Python riêng với key test, MySQL schema test và runtime ảnh riêng. */
  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) throws Exception {
    try (var socket = new ServerSocket(0)) {
      aiPort = socket.getLocalPort();
    }
    Path be = Path.of("").toAbsolutePath();
    Path ai = be.resolveSibling("YoFaSoShop_AIService");
    Path runtime = be.resolve("data/actual-ai/" + UUID.randomUUID());
    Files.createDirectories(runtime);
    var pb =
        new ProcessBuilder(
            ai.resolve(".venv-api/Scripts/python.exe").toString(),
            "-X",
            "utf8",
            be.resolve("scripts/start_test_ai.py").toString());
    pb.directory(ai.toFile());
    pb.environment()
        .putAll(
            Map.of(
                "PYTHONPATH",
                ai.toString(),
                "TEST_AI_KEY",
                KEY,
                "TEST_AI_PORT",
                "" + aiPort,
                "TEST_AI_RUNTIME",
                runtime.toString()));
    pb.redirectErrorStream(true);
    pb.redirectOutput(runtime.resolve("fastapi.log").toFile());
    process = pb.start();
    var http = HttpClient.newHttpClient();
    boolean ready = false;
    for (int i = 0; i < 100; i++) {
      try {
        if (http.send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + aiPort + "/health/live"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode()
            == 200) {
          ready = true;
          break;
        }
      } catch (Exception ignored) {
      }
      Thread.sleep(100);
    }
    if (!ready) {
      process.destroyForcibly();
      throw new IllegalStateException("FastAPI mock không khởi động: " + runtime);
    }
    r.add("app.ai.base-url", () -> "http://127.0.0.1:" + aiPort);
    r.add("app.ai.key", () -> KEY);
    r.add("app.ai.enabled", () -> false);
    var registry = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"),"registry-");
    r.add("app.runtime-root", registry::toString);
    r.add("app.storage-root", () -> runtime.resolve("media").toString());
  }

  /** Dừng đúng process test do suite tạo, không dừng FastAPI người dùng đang chạy. */
  @AfterAll
  void stop() throws Exception {
    process.destroy();
    if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
  }

  /** Kiểm tra kết quả mock được giữ cờ simulated và bytes đã tồn tại ở Backend. */
  @Test
  void vtonRoundTrip() throws Exception {
    String id = Db.id("ND");
    db.exec(
        "INSERT INTO NguoiDung(MaNguoiDung,TenDangNhap,MatKhau,HoVaTen,SoDienThoai)"
            + " VALUES(?,?,'not-a-login-password','AI fixture','0903000001')",
        id,
        "actualaifixture");
    db.exec("INSERT INTO NguoiDung_VaiTro(MaNguoiDung,MaVaiTro) VALUES(?,'VT002')", id);
    var image = new BufferedImage(256, 384, BufferedImage.TYPE_INT_RGB);
    var output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    byte[] png = output.toByteArray();
    String person = media.save(id, "person", png, false).get("assetId").toString(),
        garment = media.save(id, "garment", png, false).get("assetId").toString();
    var validated =
        client.post("/internal/v1/persons/validate", Map.of("assetId", media.remote(person)));
    assertEquals(true, validated.get("simulated"));
    String category = Db.id("DM"), product = Db.id("SP"), variant = Db.id("BT");
    db.exec("INSERT INTO DanhMuc(MaDanhMuc,TenDanhMuc) VALUES(?,'Test AI')", category);
    db.exec(
        "INSERT INTO SanPham(MaSanPham,MaDanhMuc,TenSanPham,TrangThaiSanPham) VALUES(?,?,'Fixture"
            + " only','DANG_KINH_DOANH')",
        product,
        category);
    db.exec(
        "INSERT INTO"
            + " BienTheSanPham(MaBienThe,MaSanPham,MaQuanLyHangHoa,TenMau,MaMauSac,GiaNiemYet,SoLuongTonKho)"
            + " VALUES(?,?,?,'White','#FFFFFF',100000,5)",
        variant,
        product,
        "AI-TEST-SKU");
    db.exec(
        "INSERT INTO HinhAnhSanPham(MaHinhAnh,MaSanPham,MaMauSac,DuongDanAnhGoc,DuongDanAnhBocNen)"
            + " VALUES(?,?,?,?,?)",
        Db.id("HA"),
        product,
        "#FFFFFF",
        garment,
        garment);
    String session =
        service
            .create(
                id,
                "actual-vton-test-key",
                new AiController.TryOn(variant, person, 42, 20, 1, true))
            .get("operationId")
            .toString();
    Map<String, Object> result = await(session, id);
    assertEquals(true, result.get("simulated"));
    var data = (Map<?, ?>) result.get("result");
    assertTrue(data.get("processingSeconds") instanceof Number);
    var stored=db.one("SELECT TrangThaiPhien,ThongBaoLoi,ThoiGianXuLy FROM PhienThuDoAo WHERE MaPhienThuDo=?",session);
    assertEquals("THAT_BAI",stored.get("TrangThaiPhien"));
    assertEquals("MOCK_RESULT_ONLY",stored.get("ThongBaoLoi"));
    assertNotNull(stored.get("ThoiGianXuLy"));
    assertTrue(media.bytes(data.get("resultAssetId").toString()).length > 100);
    var op =
        service.analyze(
            id,
            "actual-analysis-key",
            product,
            new AiController.Analysis(List.of(garment), List.of()));
    Map<String, Object> analysis = await(op.get("operationId").toString(), id);
    assertEquals(true, analysis.get("simulated"));
    var variants = (List<?>) ((Map<?, ?>) analysis.get("result")).get("variants");
    var first = (Map<?, ?>) variants.getFirst();
    assertEquals(garment, first.get("sourceAssetId"));
    assertTrue(media.bytes(first.get("cutoutAssetId").toString()).length > 0);
    assertTrue(media.bytes(first.get("maskAssetId").toString()).length > 0);
  }

  /** Nhịp poll điều khiển bởi test để không phải đợi lịch scheduler. */
  Map<String, Object> await(String op, String owner) throws Exception {
    Map<String, Object> result = null;
    for (int i = 0; i < 60; i++) {
      db.runtime().patch("operations", op,"NextAttemptAt",0L);
      service.processDue();
      result = service.operation(op, owner);
      if (Set.of("COMPLETED", "FAILED", "SYNC_FAILED").contains(result.get("status"))) break;
      Thread.sleep(100);
    }
    assertNotNull(result);
    assertEquals("COMPLETED", result.get("status"), result.toString());
    return result;
  }
}
