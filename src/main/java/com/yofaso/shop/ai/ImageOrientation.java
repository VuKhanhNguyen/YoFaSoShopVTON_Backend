package com.yofaso.shop.ai;

import java.awt.image.BufferedImage;
import java.nio.*;

/** Sửa chiều ảnh JPEG điện thoại trước khi bỏ EXIF; không giữ GPS/metadata cá nhân. */
public final class ImageOrientation {
  private ImageOrientation() {}

  /** Đọc riêng EXIF orientation với kiểm tra biên; metadata hỏng không được đọc vượt mảng. */
  public static int read(byte[] bytes) {
    if (bytes.length < 4 || (bytes[0] & 255) != 255 || (bytes[1] & 255) != 216) return 1;
    try {
      int offset = 2;
      while (offset + 4 < bytes.length && (bytes[offset] & 255) == 255) {
        int marker = bytes[offset + 1] & 255;
        if (marker == 218 || marker == 217) break;
        int length = ((bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
        int end = offset + 2 + length;
        if (length < 2 || end > bytes.length) break;
        int data = offset + 4;
        if (marker == 225
            && data + 14 <= end
            && bytes[data] == 'E'
            && bytes[data + 1] == 'x'
            && bytes[data + 2] == 'i'
            && bytes[data + 3] == 'f'
            && bytes[data + 4] == 0
            && bytes[data + 5] == 0) {
          int tiff = data + 6;
          var buffer =
              ByteBuffer.wrap(bytes)
                  .order(bytes[tiff] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
          if (buffer.getShort(tiff + 2) != 42) return 1;
          long location = tiff + (buffer.getInt(tiff + 4) & 0xffffffffL);
          if (location < tiff || location + 2 > end) return 1;
          int directory = (int) location;
          int count = buffer.getShort(directory) & 65535;
          for (int i = 0; i < Math.min(count, 512); i++) {
            int entry = directory + 2 + i * 12;
            if (entry + 12 > end) break;
            if ((buffer.getShort(entry) & 65535) == 274
                && buffer.getShort(entry + 2) == 3
                && buffer.getInt(entry + 4) == 1) {
              int value = buffer.getShort(entry + 8) & 65535;
              return value >= 1 && value <= 8 ? value : 1;
            }
          }
        }
        offset = end;
      }
    } catch (IndexOutOfBoundsException ignored) {
      return 1;
    }
    return 1;
  }

  /** Áp dụng đủ 8 phép xoay/lật EXIF, giữ alpha cho ảnh RGBA. */
  public static BufferedImage apply(BufferedImage input, int orientation) {
    if (orientation <= 1 || orientation > 8) return input;
    int w = input.getWidth(), h = input.getHeight();
    var output =
        new BufferedImage(
            orientation >= 5 ? h : w,
            orientation >= 5 ? w : h,
            input.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB
                : BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int dx = x, dy = y;
        switch (orientation) {
          case 2 -> dx = w - 1 - x;
          case 3 -> {
            dx = w - 1 - x;
            dy = h - 1 - y;
          }
          case 4 -> dy = h - 1 - y;
          case 5 -> {
            dx = y;
            dy = x;
          }
          case 6 -> {
            dx = h - 1 - y;
            dy = x;
          }
          case 7 -> {
            dx = h - 1 - y;
            dy = w - 1 - x;
          }
          case 8 -> {
            dx = y;
            dy = w - 1 - x;
          }
          default -> {}
        }
        output.setRGB(dx, dy, input.getRGB(x, y));
      }
    return output;
  }
}
