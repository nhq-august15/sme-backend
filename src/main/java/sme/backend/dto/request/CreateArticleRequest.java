package sme.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateArticleRequest {
    @NotBlank(message = "Tiêu đề không được để trống")
    private String title;

    private String slug;

    @NotBlank(message = "Nội dung không được để trống")
    private String content;

    private String coverImage;
    private String authorName;
    private String type; // TIN_TUC, REVIEW_SACH, GIOI_THIEU
    private Boolean isActive;
}
