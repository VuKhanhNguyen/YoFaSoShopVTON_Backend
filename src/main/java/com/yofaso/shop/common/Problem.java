package com.yofaso.shop.common;

/** Lỗi nghiệp vụ có mã ổn định, không gửi SQL/secret ra response. */
public class Problem extends RuntimeException {
  public final int status;
  public final String code;

  /** Tạo lỗi nghiệp vụ để handler đổi thành HTTP tương ứng. */
  public Problem(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  /** Dừng thao tác khi điều kiện nghiệp vụ không đạt. */
  public static void require(boolean condition, int status, String code, String message) {
    if (!condition) throw new Problem(status, code, message);
  }
}
