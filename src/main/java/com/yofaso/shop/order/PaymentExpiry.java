package com.yofaso.shop.order;

import com.yofaso.shop.common.Db;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentExpiry {
  private final Db db;
  private final OrderService orders;

  public PaymentExpiry(Db db, OrderService orders) {
    this.db = db;
    this.orders = orders;
  }

  @Scheduled(fixedDelay = 30000)
  public void releaseExpiredOrders() {
    for (var row : db.rows("SELECT MaDonHang FROM DonHang WHERE " + OrderService.EXPIRED_PAYMENT
        + " ORDER BY NgayTao LIMIT 100")) {
      String id = Db.text(row, "MaDonHang");
      try {
        orders.expirePayment(id);
      } catch (RuntimeException error) {
        LoggerFactory.getLogger(PaymentExpiry.class).warn("Không thể giải phóng tồn đơn {}", id, error);
      }
    }
  }
}
