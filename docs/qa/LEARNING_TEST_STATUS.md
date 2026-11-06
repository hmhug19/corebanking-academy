# Trạng thái kiểm thử đào tạo

Tài liệu này giúp nhóm theo dõi phạm vi đã kiểm, các lỗi và phần cần kiểm tiếp. Đây là tài liệu chia sẻ, không lưu mật khẩu, token, cookie, dữ liệu cá nhân hay URL ký truy cập media.

## Phạm vi và cách đọc

- Baseline `2026-11-06.1`: 103 case về danh mục đào tạo, biên soạn khóa, media, giao khóa, tiến độ, dashboard, báo cáo và phân quyền liên quan.
- `PASS`: các bước và kết quả được kiểm trong lần chạy đó đạt; `FAIL`: sai lệch được quan sát; `PARTIAL`: chỉ kiểm được một phần; `NOT_RUN`: chưa chạy. Không tính `PARTIAL` và `NOT_RUN` là đạt.
- Đây là kết quả của từng thời điểm, không suy ra hệ thống hiện tại đang chạy cùng bản build. Retest local phía dưới được ghi riêng, không ghi đè kết quả đợt đầu.
- Kết quả từng lần phải ghi môi trường, ngày, commit/build BE và FE nếu xác định được, dữ liệu/scope đã dùng, trạng thái, kết quả thực tế, giới hạn và liên kết ticket/bằng chứng phù hợp.
- Các case API, kiểm thử tải, bảo mật và xử lý sự cố chỉ được tính đạt khi được kiểm đúng điều kiện; build hoặc UI hoạt động không thay thế các phép kiểm đó.

## Đợt RUN-20261106-01 — trước khi sửa

- Ngày: 06/11/2026. Môi trường: web demo. Kênh: thao tác UI bằng các tài khoản demo đã được cho phép.
- BE: source baseline được khảo sát ở commit `c43a13fa3b90dde54171ecad4ca2c756902bc938`; không có bằng chứng xác nhận bản triển khai web trùng commit đó. FE: chưa xác định được commit/build của bản chạy.
- Kết quả trên 103 case: **30 PASS, 5 FAIL, 31 PARTIAL, 37 NOT_RUN**. Năm case FAIL tương ứng bốn vấn đề; ASN-004 và DSH-003 cùng phản ánh vấn đề giao tương lai.
- Tài khoản được kiểm theo role và scope thực tế: quản trị gốc, quản trị toàn hệ thống, quản trị đơn vị, giảng viên/học viên, học viên, học viên tích hợp và người xem báo cáo.

### Phát hiện và tiến độ xử lý

| Mã | Phát hiện trước sửa | Trạng thái source / retest local |
| --- | --- | --- |
| MED-010 | Học viên đã đăng ký không tải được attachment; block lại hiện hoàn thành. | Source đã chỉnh tải bằng media cookie và gửi completion sau tải thành công. Local tải được file trùng hash nguồn; chưa retest tải lỗi với block chưa hoàn thành. |
| CRS-012 | Lọc khóa tạm dừng trả lỗi giá trị bộ lọc; vùng lọc biến mất. | Source đã chỉnh state/lifecycle và giữ filter khi lỗi. Local chọn Tạm dừng trả kết quả rỗng hợp lệ; chưa kiểm bộ lọc có dữ liệu, Lưu trữ chưa đối chiếu đầy đủ. |
| ASN-004, DSH-003 | Lượt giao tương lai bị chặn học nhưng xuất hiện trong tập/KPI được giao hiện hành. | Source đã chỉnh xét ngày bắt đầu. Retest local: khóa test không nằm trong tập được giao hiện hành, KPI giảm và truy cập khóa bị chặn; chưa kiểm thời điểm tại/sau start và chưa đọc lại ngày của đợt giao trong runtime. Cần xác nhận định nghĩa KPI với nghiệp vụ. |
| CAT-001 | Quản trị gốc và quản trị toàn hệ thống bị 403 tại danh mục lĩnh vực/chương trình. | Retest local: quản trị gốc vẫn 403. Cần xác minh grant và việc áp dụng SQL nâng cấp trên đúng database, sau đó đăng nhập lại; không tự cấp quyền để ép test đạt. |
| DSH-011 | Dashboard quản trị và giảng viên trước sửa chỉ hiện placeholder. | Source có dashboard đọc summary theo scope. Retest local: cả hai trang có số liệu; chưa đối soát toàn bộ KPI, xử lý lỗi và quyền trên các scope khác. |

Quy trình nâng cấp function danh mục đã có trong [hướng dẫn SQL](../sql/README.md); contract media, tiến độ và báo cáo ở [Learning API](../api/LEARNING.md). Không chạy SQL trên môi trường dùng chung chỉ để làm test pass.

### Các luồng chính đã kiểm

- Tạo, sao chép, xuất bản, tạm dừng/mở lại khóa và giữ tiến độ; hai phiên sửa nháp báo xung đột và không ghi đè dữ liệu phiên lưu trước.
- Giao cá nhân và nhóm, khử trùng người nhận, snapshot thành viên; giao tiếp tục giữ tiến độ, học lại tạo chu kỳ mới và giữ chứng nhận cũ. Một số nhánh thu hồi/reminder mới kiểm một phần.
- Upload và xem video/PDF/ảnh; kiểm giới hạn PDF và attachment; video tua cuối không được tính là đã xem, xem lặp không cộng đôi; PDF nhiều trang giữ vị trí và hoàn thành tại trang cuối.
- Tiến độ từ các block đã đối chiếu qua UI, báo cáo và CSV. CSV báo cáo xuất từ trang 2 chứa đủ 29 dòng thuộc tập dữ liệu đã lọc tại thời điểm thử.
- Chuyển phiên giữa hai tab chặn phiên học cũ; đổi tài khoản không thấy lẫn dashboard trong phạm vi đã kiểm. Các kết quả này không thay thế kiểm thử bảo mật API.

### Kiểm chứng source và giới hạn

- BE Maven compile thành công. 10 integration test BE được chọn đã **skip** vì thiếu Docker/PostgreSQL test; không tính là pass.
- FE Angular compiler, Sass và production build thành công; 34 test hiện có cho interceptor/player pass. ESLint không có lỗi ở các file được kiểm, nhưng file HTML bị cấu hình lint bỏ qua.
- Sau sửa chưa retest toàn bộ trên môi trường demo. Retest local chưa xác nhận backend/proxy/build thực tế chỉ từ URL `localhost`.
- Chưa chạy hoặc mới kiểm một phần các nhánh API trực tiếp, idempotency/race, mạng gián đoạn, worker lỗi, tải lớn, dữ liệu liên đơn vị, quiz và nhiều case danh mục bị chặn. Không dùng báo cáo này làm chứng nhận bảo mật hoặc hiệu năng.
- Dữ liệu tổng hợp và ảnh/CSV của đợt chạy được giữ ở vùng local bị Git ignore; tài liệu này không liên kết tới đường dẫn máy cá nhân. Khi cần chia sẻ, đính kèm bằng chứng đã rà nội dung vào ticket hoặc hệ thống kiểm thử của nhóm.

## Danh sách case — kết quả đợt trước sửa

Đây là chỉ mục để biết mỗi case đã được thực hiện đến mức nào. Các trạng thái giữ nguyên theo đợt RUN-20261106-01; retest một phần ở trên không tự biến cả case thành PASS.

| Case | Nội dung | Ưu tiên | Kênh dự kiến | Kết quả |
| --- | --- | --- | --- | --- |
| CAT-001 | Mở cây đào tạo | P0 | UI | FAIL |
| CAT-002 | Tạo lĩnh vực mới | P1 | UI+API | NOT_RUN |
| CAT-003 | Tạo chương trình trong lĩnh vực | P0 | UI+API | NOT_RUN |
| CAT-004 | Mã trùng không phân biệt hoa thường | P1 | UI+API | NOT_RUN |
| CAT-005 | Không đổi lĩnh vực chương trình đã tạo | P1 | UI+API | NOT_RUN |
| CAT-006 | Không tham chiếu cha khác org | P1 | API | NOT_RUN |
| CAT-007 | Tạm dừng danh mục | P0 | UI | NOT_RUN |
| CAT-008 | Version cũ khi sửa chương trình | P1 | UI+API | NOT_RUN |
| CAT-009 | Phân hệ và chương trình cùng lĩnh vực | P1 | UI+API | NOT_RUN |
| CAT-010 | Sao chép chương trình | P1 | UI+API | NOT_RUN |
| CAT-011 | Xóa danh mục đang được dùng | P1 | UI+API | NOT_RUN |
| CAT-012 | Tìm kiếm và thứ tự cây | P2 | UI | NOT_RUN |
| CRS-001 | Tạo nháp và lưu cây hoàn chỉnh | P0 | UI | NOT_RUN |
| CRS-002 | Tạo khóa trùng mã | P1 | UI+API | PARTIAL |
| CRS-003 | Validation trường và thoát chưa lưu | P1 | UI | PASS |
| CRS-004 | Nội dung đa loại giữ đúng sau reload | P0 | UI | PARTIAL |
| CRS-005 | Quy trình nhiều bước | P1 | UI | PASS |
| CRS-006 | Publish khóa đủ điều kiện | P0 | UI | PASS |
| CRS-007 | Chặn publish nội dung chưa sẵn sàng | P0 | UI+API | PARTIAL |
| CRS-008 | Hai người lưu cùng bản nháp | P1 | UI+API | PASS |
| CRS-009 | Bản mới không đổi lần học cũ | P0 | UI | PASS |
| CRS-010 | Sao chép khóa | P1 | UI | PASS |
| CRS-011 | Pause và mở lại khóa | P1 | UI | PASS |
| CRS-012 | Lọc và phân trang danh sách khóa | P1 | UI | FAIL |
| MED-001 | Upload video nguồn thật và chờ READY | P0 | UI | PASS |
| MED-002 | Upload PDF nhỏ | P0 | UI | PASS |
| MED-003 | Tên file Unicode | P1 | UI | PARTIAL |
| MED-004 | PDF quá giới hạn byte | P1 | UI | PASS |
| MED-005 | Attachment có giới hạn khác PDF | P1 | UI | PARTIAL |
| MED-006 | Hủy upload rồi chọn file khác | P1 | UI | NOT_RUN |
| MED-007 | Mạng gián đoạn và tiếp tục upload | P1 | UI | NOT_RUN |
| MED-008 | Retry part và complete idempotent | P1 | API | NOT_RUN |
| MED-009 | Xem video/PDF có quyền | P0 | UI | PASS |
| MED-010 | Tải tệp đính kèm | P0 | UI | FAIL |
| MED-011 | Range và HEAD | P1 | API | NOT_RUN |
| MED-012 | Không mở media của người khác | P1 | UI+API | PARTIAL |
| MED-013 | Worker lỗi không thành công giả | P1 | CONTROLLED | NOT_RUN |
| ASN-001 | Giao khóa published cho cá nhân | P0 | UI | PARTIAL |
| ASN-002 | Chặn giao bản draft | P1 | UI+API | NOT_RUN |
| ASN-003 | Ngày hạn sai | P1 | UI | PASS |
| ASN-004 | Giao tương lai | P1 | UI | FAIL |
| ASN-005 | Giao qua nhóm chuyên môn | P1 | UI | NOT_RUN |
| ASN-006 | Nhóm học tập nhiều thành viên | P1 | UI | PASS |
| ASN-007 | Nhiều nguồn trùng một người | P1 | UI | PASS |
| ASN-008 | Đổi thành viên sau khi giao | P1 | UI | PARTIAL |
| ASN-009 | Retry giao cùng idempotency key | P1 | UI+API | NOT_RUN |
| ASN-010 | Continue giữ tiến độ | P0 | UI | PASS |
| ASN-011 | Retake tạo cycle mới | P1 | UI | PASS |
| ASN-012 | Thu hồi một trong nhiều nghĩa vụ | P1 | UI | PARTIAL |
| ASN-013 | Thu hồi nguồn cuối | P1 | UI | PARTIAL |
| ASN-014 | Chia sẻ cho đơn vị khác | P1 | UI+API | NOT_RUN |
| ASN-015 | Tự đăng ký khóa tự chọn | P1 | UI | PASS |
| PRG-001 | Khởi đầu khóa và block manual | P0 | UI | PASS |
| PRG-002 | Ảnh hoặc attachment completion OPEN | P0 | UI | PASS |
| PRG-003 | PDF đến trang cuối | P0 | UI | PASS |
| PRG-004 | Video xem thật đạt ngưỡng | P0 | UI | PASS |
| PRG-005 | Tua cuối không hoàn thành | P1 | UI | PASS |
| PRG-006 | Xem lặp không cộng đôi | P1 | UI | PASS |
| PRG-007 | Pause và chạy nền không ghi xem giả | P1 | UI | PARTIAL |
| PRG-008 | Resume sau tải lại/đăng nhập lại | P0 | UI | PASS |
| PRG-009 | Chỉ một phiên học đang hiệu lực | P1 | UI | PASS |
| PRG-010 | Retry event và sequence sai | P1 | API | NOT_RUN |
| PRG-011 | Block/asset/session không khớp | P1 | API | NOT_RUN |
| PRG-012 | Hoàn thành khóa và bền vững | P0 | UI | PARTIAL |
| PRG-013 | Khối/bài tùy chọn không cản hoàn thành | P1 | UI+API | NOT_RUN |
| PRG-014 | Lọc/sort/URL tiến độ | P1 | UI | PARTIAL |
| PRG-015 | Xuất CSV tiến độ cá nhân | P1 | UI | PASS |
| PRG-016 | Quiz gắn bài/khóa và completion | P1 | UI+API | NOT_RUN |
| DSH-001 | KPI đối chiếu tập khóa hiện hành | P0 | UI+API | PARTIAL |
| DSH-002 | Phân biệt completionRate/averageProgress | P0 | UI+API | PARTIAL |
| DSH-003 | Tương lai không tính assigned hiện hành | P1 | UI+API | FAIL |
| DSH-004 | Tiếp tục học đúng nội dung chưa xong | P1 | UI | PARTIAL |
| DSH-005 | Hạn gần nhất tối đa 5 | P1 | UI+API | PARTIAL |
| DSH-006 | Tiến độ theo chương trình | P1 | UI+API | NOT_RUN |
| DSH-007 | Điểm thi gần nhất và cao nhất | P1 | UI+API | NOT_RUN |
| DSH-008 | Năng lực BEST/LAST | P1 | UI+API | NOT_RUN |
| DSH-009 | Một khu vực dashboard lỗi | P1 | CONTROLLED | NOT_RUN |
| DSH-010 | Chuyển tài khoản không rò cache | P1 | UI | PASS |
| DSH-011 | Tổng quan quản trị và giảng viên | P1 | UI | PARTIAL |
| RPT-001 | Mở báo cáo thật | P0 | UI | PASS |
| RPT-002 | Summary khớp chi tiết | P0 | UI+API | PARTIAL |
| RPT-003 | Drill-down khóa sang học viên | P0 | UI | PASS |
| RPT-004 | Drill-down đơn vị bỏ team cũ | P1 | UI | NOT_RUN |
| RPT-005 | Bộ lọc trạng thái và overdue | P1 | UI+API | PARTIAL |
| RPT-006 | Khoảng ngày theo enrolledAt | P1 | UI+API | PARTIAL |
| RPT-007 | Lọc user/program/field/q | P1 | API | NOT_RUN |
| RPT-008 | Group theo chương trình/nhóm/user | P1 | UI+API | PARTIAL |
| RPT-009 | Phân trang và sort báo cáo | P1 | UI | PARTIAL |
| RPT-010 | Export cùng filter/scope/sort | P0 | UI+API | PARTIAL |
| RPT-011 | Export hơn 10000 dòng | P1 | CONTROLLED | NOT_RUN |
| RPT-012 | Snapshot đơn vị lịch sử | P1 | UI+API | NOT_RUN |
| RPT-013 | Đối soát sau học/retry/retake | P1 | UI+API | PARTIAL |
| RPT-014 | Phân biệt báo cáo thi và tiến độ | P1 | UI+API | PARTIAL |
| SEC-001 | Ghi role/scope thật trước kiểm thử | P0 | UI | PASS |
| SEC-002 | Learner không quản trị/báo cáo | P0 | UI+API | PARTIAL |
| SEC-003 | Admin đơn vị không vượt scope | P1 | UI+API | NOT_RUN |
| SEC-004 | Giảng viên có quyền đúng grant | P1 | UI+API | PARTIAL |
| SEC-005 | Người báo cáo chỉ đọc | P1 | UI+API | PARTIAL |
| SEC-006 | Menu không dẫn tới chức năng không có quyền | P1 | UI | NOT_RUN |
| SEC-007 | Hết phiên và logout | P1 | UI | PARTIAL |
| SEC-008 | HTTP 200 nhưng nghiệp vụ ERROR | P1 | UI+API | NOT_RUN |
| SEC-009 | Loading, empty và error độc lập | P1 | UI | NOT_RUN |
| SEC-010 | Responsive thao tác chính | P1 | UI | PARTIAL |

## Cập nhật cho các lần kiểm tiếp theo

1. Giữ nguyên lịch sử đợt trước. Tạo mục đợt chạy mới với ngày, môi trường, commit/build, dữ liệu và người/vai trò kiểm.
2. Ghi từng case đã chạy cùng kết quả thực tế và mức độ kiểm đầy đủ. Nếu bị chặn hoặc chưa chạy, ghi rõ lý do; không sao chép PASS từ lần khác.
3. Liên kết lỗi và bằng chứng đã được phép chia sẻ. Không commit thông tin đăng nhập, token, cookie hoặc dữ liệu cá nhân thật.
4. Sau khi sửa, retest case lỗi và các case liên quan trên đúng bản triển khai; cập nhật trạng thái của đợt mới, không thay thế bằng chứng trước sửa.
