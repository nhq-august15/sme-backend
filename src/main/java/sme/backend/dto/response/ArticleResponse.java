package sme.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class ArticleResponse {
    private UUID id;
    private String title;
    private String slug;
    private String content;
    private String coverImage;
    private String authorName;
    private String type;
    private Boolean isActive;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
