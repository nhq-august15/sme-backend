package sme.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonClassDescription;

@JsonClassDescription("Yêu cầu lấy danh sách các thể loại sách đang có trong cửa hàng")
public record CategorySearchRequest() {
}
