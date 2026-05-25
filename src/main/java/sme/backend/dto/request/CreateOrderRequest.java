package sme.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class CreateOrderRequest {

    private UUID customerId; // Có thể null, Controller sẽ fill nếu auth
    @NotBlank private String shippingName;
    @NotBlank private String shippingPhone;
    @NotBlank private String shippingAddress;
    @NotBlank private String provinceCode;

    @NotEmpty @Valid
    private List<OrderItemRequest> items;

    @NotBlank private String paymentMethod;
    private String type;     // DELIVERY (default) | BOPIS
    private String note;
    // Thêm shippingFee và discount
    private BigDecimal shippingFee;
    private BigDecimal discountAmount;
    private List<String> couponCodes;

    // KHÔNG CÓ assignedWarehouseId (hệ thống tự phân luồng)

    @Data
    public static class OrderItemRequest {
        @NotNull private UUID productId;
        @NotNull @Min(1) private Integer quantity;
    }
}
