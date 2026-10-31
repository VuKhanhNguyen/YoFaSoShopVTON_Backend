package com.yofaso.shop.common;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL tham số hóa, dùng trực tiếp tên bảng ERD; không ghép tên cột từ request. */
@Repository
public class Db {
  public final JdbcTemplate jdbc;
  public final RuntimeState runtime;

  /** Nhận JdbcTemplate dùng chung transaction do Spring quản lý. */
  public Db(JdbcTemplate jdbc, RuntimeState runtime) {
    this.jdbc = jdbc;
    this.runtime = runtime;
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

  /** ID ngẫu nhiên có tiền tố, tối đa 20 ký tự theo ERD, không dùng MAX+1. */
  public static String id(String prefix) {
    return prefix
        + UUID.randomUUID().toString().replace("-", "").substring(0, 20 - prefix.length());
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
    org.slf4j.LoggerFactory.getLogger(Db.class).info("actor={} action={} resource={}", actor, action, resource);
  }
}
