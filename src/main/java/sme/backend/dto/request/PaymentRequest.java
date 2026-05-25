package sme.backend.dto.request;

import lombok.Data;

@Data
public class PaymentRequest {
    private String returnUrl;
    private String cancelUrl;
}
