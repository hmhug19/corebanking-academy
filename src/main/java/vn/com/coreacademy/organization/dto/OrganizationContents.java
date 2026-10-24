package vn.com.coreacademy.organization.dto;

import java.util.List;

import vn.com.coreacademy.common.dto.PageResponse;

/** Dữ liệu panel của node đang chọn; số lượng và nhóm không cộng dồn từ tổ chức con. */
public record OrganizationContents(OrganizationInfo organization,
                                    List<OrganizationDetail.Administrator> administrators,
                                    PageResponse<TeamInfo> teams) {
}
