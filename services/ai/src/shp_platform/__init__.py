"""Thư viện dùng chung cho service Python (ai, ml). Chủ sở hữu: SA.

Nguồn duy nhất nằm ở libs/python/shp_platform; tools/pylib/sync.py chép vào
services/<svc>/src/shp_platform (build context của mỗi service chỉ là thư mục service).
Không sửa bản chép trong service: CI kiểm bằng `sync.py --check`.
Quy tắc khớp bản Java (libs/java): roles, MFA nhân viên, danh mục lỗi, audit, CloudEvents.
"""

__version__ = "0.1.0"
