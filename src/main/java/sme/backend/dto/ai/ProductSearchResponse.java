package sme.backend.dto.ai;

import java.math.BigDecimal;
import java.util.List;

public record ProductSearchResponse(List<ProductDto> products) {
    public record ProductDto(String name, BigDecimal price, String description) {}
}
