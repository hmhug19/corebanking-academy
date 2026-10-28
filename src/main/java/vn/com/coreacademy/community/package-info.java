/**
 * Cộng đồng tri thức nội bộ. Quan hệ dùng ID scalar;

policy kiểm quyền hiện tại trước mọi payload,
 * repository giữ truy vấn, service điều phối version/idempotency và transaction, controller chỉ xử lý HTTP.
 * Trạng thái từng use case được công bố tại docs/api/COMMUNITY.md; mapping bảng không thay thế implementation.
 */
package vn.com.coreacademy.community;
