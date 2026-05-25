package sme.backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PosCheckoutDraft implements Serializable {
    private CheckoutRequest checkoutRequest;
    private UUID cashierId;
    private UUID warehouseId;
    private Long amount;
}
