package sme.backend.dto.request;

import lombok.Data;

@Data
public class UpdateArticleRequest {
    private String title;
    private String slug;
    private String content;
    private String coverImage;
    private String authorName;
    private String type;
    private Boolean isActive;
}
