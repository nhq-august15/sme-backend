package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.envers.Audited;
import org.hibernate.envers.AuditTable;

@Entity
@Table(name = "articles")
@Audited
@AuditTable("articles_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Article extends BaseEntity {

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, unique = true, length = 300)
    private String slug;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "cover_image", columnDefinition = "TEXT")
    private String coverImage;

    @Column(name = "author_name", length = 100)
    private String authorName;

    @Column(length = 50, nullable = false)
    @Builder.Default
    private String type = "TIN_TUC"; // TIN_TUC, REVIEW_SACH, GIOI_THIEU

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

}
