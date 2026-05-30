package sme.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonClassDescription;

@JsonClassDescription("Yêu cầu lấy danh sách các sách bán chạy nhất hoặc gợi ý chung từ cửa hàng")
public record TopSellingProductsRequest(Integer limit) {
}
