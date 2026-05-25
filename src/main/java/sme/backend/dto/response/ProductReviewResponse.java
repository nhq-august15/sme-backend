package sme.backend.dto.response;

import lombok.Builder;
import lombok.Data;
import sme.backend.entity.ProductReview;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class ProductReviewResponse {
    private UUID id;
    private UUID productId;
    private UUID customerId;
    private String customerName;
    private Integer rating;
    private String comment;
    private Boolean isVerifiedPurchase;
    private Boolean isApproved;
    private Instant createdAt;
    private java.util.List<String> imageUrls;
}
