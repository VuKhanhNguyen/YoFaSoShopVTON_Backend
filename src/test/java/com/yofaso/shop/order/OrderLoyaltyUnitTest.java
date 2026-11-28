package com.yofaso.shop.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OrderLoyaltyUnitTest {

  @Test
  void computesOneLoyaltyPointPerTenThousandVnd() {
    assertEquals(0, OrderService.loyaltyPointsForAmount(null));
    assertEquals(0, OrderService.loyaltyPointsForAmount(BigDecimal.ZERO));
    assertEquals(0, OrderService.loyaltyPointsForAmount(BigDecimal.valueOf(9_999)));
    assertEquals(1, OrderService.loyaltyPointsForAmount(BigDecimal.valueOf(10_000)));
    assertEquals(45, OrderService.loyaltyPointsForAmount(BigDecimal.valueOf(459_000)));
    assertEquals(115, OrderService.loyaltyPointsForAmount(BigDecimal.valueOf(1_150_000)));
  }

  @Test
  void promotesMembershipAcrossFourTiersWithoutDowngradingHigherTier() {
    assertEquals("DONG", OrderService.tierForPoints(0, "DONG"));
    assertEquals("DONG", OrderService.tierForPoints(99, "DONG"));
    assertEquals("BAC", OrderService.tierForPoints(100, "DONG"));
    assertEquals("BAC", OrderService.tierForPoints(299, "BAC"));
    assertEquals("VANG", OrderService.tierForPoints(300, "BAC"));
    assertEquals("VANG", OrderService.tierForPoints(499, "DONG"));
    assertEquals("KIM_CUONG", OrderService.tierForPoints(500, "VANG"));
    // Không bao giờ hạ hạng đặc cách sẵn có (ví dụ tài khoản VIP/Admin có điểm thấp nhưng đã ở KIM_CUONG)
    assertEquals("KIM_CUONG", OrderService.tierForPoints(0, "KIM_CUONG"));
    assertEquals("VANG", OrderService.tierForPoints(120, "VANG"));
  }
}
