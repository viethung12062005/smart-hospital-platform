package org.shp.fhir.search;

import java.util.List;

/**
 * Truy vấn tìm kiếm đã parse.
 *
 * @param criteria các tiêu chí (AND)
 * @param count số kết quả mỗi trang ({@code _count}: mặc định 20, tối đa 100)
 * @param pageToken {@code _page_token} mờ của trang tiếp, null ở trang đầu (giải mã ở SHR-04)
 */
public record SearchQuery(
    String resourceType, List<Criterion> criteria, int count, String pageToken) {

  /** Các tiêu chí của một tham số, theo thứ tự xuất hiện. */
  public List<Criterion> criteria(String param) {
    return criteria.stream().filter(c -> c.param().equals(param)).toList();
  }
}
