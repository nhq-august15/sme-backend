package sme.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateReviewRequest {
    @NotNull
    private UUID productId;
    
    @NotNull
    private UUID orderId;

    @NotNull
    @Min(1)
    @Max(5)
    private Integer rating;

    private String comment;

    private java.util.List<String> imageUrls;
}
