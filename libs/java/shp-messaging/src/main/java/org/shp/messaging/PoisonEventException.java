package org.shp.messaging;

import java.util.List;

/**
 * Event không đọc được hoặc sai schema: không bao giờ xử lý được, đi vào DLQ sau số lần giao tối
 * đa. Thông điệp chỉ có id, type và vị trí trường lỗi, không có giá trị của {@code data}.
 */
public final class PoisonEventException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public PoisonEventException(String id, String type, List<String> locations) {
    super("Event không hợp lệ id=" + id + " type=" + type + " tại " + locations);
  }

  public PoisonEventException(Throwable cause) {
    super("Event không đọc được (không phải CloudEvent JSON)", cause);
  }
}
