---
name: User Story
about: Issue cha của một US; các task là sub-issue (docs/00-workflow.md §2)
title: "US-xxx: <tên story>"
labels: user-story
---
**Câu chuyện:** Là <vai trò>, tôi muốn <mong muốn> để <lợi ích>.
**Mức ưu tiên:** <!-- Must / Should / Could; gắn label priority:* tương ứng -->
**Use case, API, yêu cầu liên quan:** <!-- vd. UC-07; S10; FR-07 -->

**Tiêu chí nghiệm thu (AC):**
- [ ] <!-- AC-xx.y — test case trong docs/06-testing/catalog/<file>.yaml -->

**Cờ tính năng:** <!-- SHP_FEATURE_<TÊN> nếu task merge trước khi US xong; không cần thì ghi "không" -->

**Đóng khi:** mọi sub-issue đóng, mọi AC có test tự động xanh trên main, đã chạy thử luồng trên cụm với dữ liệu giả (ghi kết quả ở comment).

> Repo public: chỉ dữ liệu giả. Không dán dữ liệu bệnh nhân thật, token, mật khẩu hay log có PII.
