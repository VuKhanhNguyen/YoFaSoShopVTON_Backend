package com.yofaso.shop.common;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL tham số hóa, dùng trực tiếp tên bảng ERD; không ghép tên cột từ request. */
@Repository
public class Db {
  private static final Logger log = LoggerFactory.getLogger(Db.class);
  private static volatile Db instance;

  public final JdbcTemplate jdbc;
  public final RuntimeState runtime;

  public record PrefixMeta(String table, String column, int digits) {}

  /** Cấu hình tiền tố và độ dài số tự tăng cho toàn bộ 17 bảng có khóa chính đơn theo ERD và dữ liệu mẫu. */
  public static final Map<String, PrefixMeta> PREFIX_MAP = Map.ofEntries(
      Map.entry("ND", new PrefixMeta("NguoiDung", "MaNguoiDung", 8)),
      Map.entry("DC", new PrefixMeta("SoDiaChi", "MaDiaChi", 8)),
      Map.entry("DM", new PrefixMeta("DanhMuc", "MaDanhMuc", 6)),
      Map.entry("SP", new PrefixMeta("SanPham", "MaSanPham", 8)),
      Map.entry("BT", new PrefixMeta("BienTheSanPham", "MaBienThe", 8)),
      Map.entry("HA", new PrefixMeta("HinhAnhSanPham", "MaHinhAnh", 8)),
      Map.entry("TD", new PrefixMeta("PhienThuDoAo", "MaPhienThuDo", 8)),
      Map.entry("GY", new PrefixMeta("GoiYPhoiDo", "MaGoiY", 8)),
      Map.entry("KM", new PrefixMeta("KhuyenMai", "MaKhuyenMai", 6)),
      Map.entry("GH", new PrefixMeta("GioHang", "MaGioHang", 8)),
      Map.entry("CTGH", new PrefixMeta("ChiTietGioHang", "MaChiTietGioHang", 6)),
      Map.entry("DH", new PrefixMeta("DonHang", "MaDonHang", 8)),
      Map.entry("CTDH", new PrefixMeta("ChiTietDonHang", "MaChiTietDonHang", 6)),
      Map.entry("BDK", new PrefixMeta("BienDongKho", "MaBienDong", 8)),
      Map.entry("CA", new PrefixMeta("CaLamViecThuNgan", "MaCaLamViec", 6)),
      Map.entry("DG", new PrefixMeta("DanhGiaSanPham", "MaDanhGia", 8)),
      Map.entry("TB", new PrefixMeta("ThongBao", "MaThongBao", 8))
  );

  private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

  /** Nhận JdbcTemplate dùng chung transaction do Spring quản lý. */
  public Db(JdbcTemplate jdbc, RuntimeState runtime) {
    this.jdbc = jdbc;
    this.runtime = runtime;
    Db.instance = this;
  }

  public RuntimeState runtime() { return runtime; }

  /** Danh sách bản ghi; SQL chỉ do code nội bộ định nghĩa. */
  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.queryForList(sql, args);
  }

  /** Lấy đúng một tài nguyên hoặc trả 404. */
  public Map<String, Object> one(String sql, Object... args) {
    var rows = rows(sql, args);
    if (rows.isEmpty()) throw new Problem(404, "NOT_FOUND", "Không tìm thấy tài nguyên.");
    return rows.getFirst();
  }

  /** Đọc scalar số nguyên, ví dụ COUNT, không nhận SQL từ client. */
  public long count(String sql, Object... args) {
    return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
  }

  /** Ghi dữ liệu bằng bind parameters. */
  public int exec(String sql, Object... args) {
    return jdbc.update(sql, args);
  }

  /**
   * Sinh ID tự tăng tuần tự theo tiền tố và độ dài chuẩn ERD (VD: DC00000016, DH00000013).
   * Lấy max ID số hiện có từ database và đồng bộ atomic counter để chống trùng lặp đa luồng.
   */
  public String nextId(String prefix) {
    PrefixMeta meta = PREFIX_MAP.get(prefix);
    if (meta == null) {
      return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, Math.min(32, 20 - prefix.length()));
    }
    long currentDbMax = queryMaxId(meta.table(), meta.column(), prefix);
    var counter = counters.computeIfAbsent(prefix, p -> new AtomicLong(currentDbMax));
    long next = counter.updateAndGet(curr -> Math.max(curr, currentDbMax) + 1);
    return String.format("%s%0" + meta.digits() + "d", prefix, next);
  }

  private long queryMaxId(String table, String column, String prefix) {
    try {
      // Chỉ tìm các mã đúng định dạng prefix + số nguyên, loại trừ các mã UUID cũ nếu có
      String sql = String.format(
          "SELECT %s FROM %s WHERE %s REGEXP '^%s[0-9]+$' ORDER BY LENGTH(%s) DESC, %s DESC LIMIT 1",
          column, table, column, prefix, column, column);
      List<String> list = jdbc.queryForList(sql, String.class);
      if (list.isEmpty() || list.getFirst() == null) {
        return 0L;
      }
      String val = list.getFirst().trim();
      if (val.startsWith(prefix)) {
        return Long.parseLong(val.substring(prefix.length()));
      }
      return 0L;
    } catch (Exception e) {
      log.warn("Không thể đọc max ID cho {}.{}: {}", table, column, e.getMessage());
      return 0L;
    }
  }

  /** ID tuần tự theo quy tắc ERD và dữ liệu mẫu yofaso_insert_data.sql. */
  public static String id(String prefix) {
    if (instance != null) {
      return instance.nextId(prefix);
    }
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, Math.min(32, 20 - prefix.length()));
  }

  /** Đọc text NULL an toàn. */
  public static String text(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), "");
  }

  /** Đọc giá tiền chính xác, không dùng double. */
  public static BigDecimal money(Map<String, Object> row, String key) {
    return new BigDecimal(Objects.toString(row.get(key), "0"));
  }

  /** Chặn phân trang quá lớn trước khi tính OFFSET. */
  public static int offset(int page, int size) {
    Problem.require(
        page >= 0 && page <= 10000 && size >= 1 && size <= 100,
        400,
        "PAGE_INVALID",
        "page 0..10000, size 1..100.");
    return page * size;
  }

  /** Ghi sự kiện kiểm toán, không chứa mật khẩu hoặc toàn bộ request. */
  public void audit(String actor, String action, String resource) {
    log.info("actor={} action={} resource={}", actor, action, resource);
  }
}
