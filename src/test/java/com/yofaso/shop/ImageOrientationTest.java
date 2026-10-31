package com.yofaso.shop;

import static org.junit.jupiter.api.Assertions.*;

import com.yofaso.shop.ai.ImageOrientation;
import java.awt.image.BufferedImage;
import java.nio.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Test toán xoay/lật ảnh thực, gồm metadata hỏng để không làm mất chiều ảnh điện thoại. */
class ImageOrientationTest {
  /** Corner gốc và tổng pixel phải được bảo toàn qua tám phép biến đổi. */
  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
  void allOrientations(int orientation) {
    var input = new BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB);
    int i = 0;
    for (int y = 0; y < 3; y++) for (int x = 0; x < 2; x++) input.setRGB(x, y, 0xff000000 | ++i);
    var result = ImageOrientation.apply(input, orientation);
    assertEquals(orientation >= 5 ? 3 : 2, result.getWidth());
    assertEquals(orientation >= 5 ? 2 : 3, result.getHeight());
    int sum = 0;
    for (int y = 0; y < result.getHeight(); y++)
      for (int x = 0; x < result.getWidth(); x++) sum += result.getRGB(x, y) & 255;
    assertEquals(21, sum);
    if (orientation == 6) {
      assertEquals(input.getRGB(0, 0), result.getRGB(2, 0));
      assertEquals(input.getRGB(1, 2), result.getRGB(0, 1));
    }
    if (orientation == 8) assertEquals(input.getRGB(0, 0), result.getRGB(0, 1));
  }

  /** Đọc TIFF little endian trong APP1 và trả mặc định an toàn khi metadata bị cắt. */
  @Test
  void parseExif() {
    byte[] b = new byte[40];
    var v = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
    v.putShort(0, (short) 0xffd8);
    v.putShort(2, (short) 0xffe1);
    v.putShort(4, (short) 34);
    b[6] = 'E';
    b[7] = 'x';
    b[8] = 'i';
    b[9] = 'f';
    b[12] = 'I';
    b[13] = 'I';
    v.order(ByteOrder.LITTLE_ENDIAN);
    v.putShort(14, (short) 42);
    v.putInt(16, 8);
    v.putShort(20, (short) 1);
    v.putShort(22, (short) 274);
    v.putShort(24, (short) 3);
    v.putInt(26, 1);
    v.putShort(30, (short) 6);
    assertEquals(6, ImageOrientation.read(b));
    assertEquals(1, ImageOrientation.read(new byte[] {(byte) 255, (byte) 216}));
    v.putInt(16, Integer.MAX_VALUE);
    assertEquals(1, ImageOrientation.read(b));
  }
}
