package sme.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.CreateArticleRequest;
import sme.backend.dto.request.UpdateArticleRequest;
import sme.backend.dto.response.ArticleResponse;
import sme.backend.entity.Article;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.ArticleRepository;

import java.text.Normalizer;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ArticleService {

    private final ArticleRepository articleRepository;

    private String generateSlug(String name) {
        String slug = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug;
    }

    @Transactional(readOnly = true)
    public Page<ArticleResponse> searchArticles(String keyword, String type, Boolean isActive, Pageable pageable) {
        return articleRepository.searchArticles(keyword, type, isActive, pageable)
                .map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public ArticleResponse getArticleById(UUID id) {
        Article article = articleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Article", id));
        return mapToResponse(article);
    }

    @Transactional(readOnly = true)
    public ArticleResponse getArticleBySlug(String slug) {
        Article article = articleRepository.findBySlugAndIsActiveTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Article with slug", slug));
        return mapToResponse(article);
    }

    @Transactional
    public ArticleResponse createArticle(CreateArticleRequest req) {
        String slug = req.getSlug() != null && !req.getSlug().isBlank() ? req.getSlug() : generateSlug(req.getTitle());
        
        // Ensure slug is unique
        if (articleRepository.existsBySlug(slug)) {
            slug = slug + "-" + System.currentTimeMillis();
        }

        Article article = Article.builder()
                .title(req.getTitle())
                .slug(slug)
                .content(req.getContent())
                .coverImage(req.getCoverImage())
                .authorName(req.getAuthorName())
                .type(req.getType() != null ? req.getType() : "TIN_TUC")
                .isActive(req.getIsActive() != null ? req.getIsActive() : true)
                .build();

        return mapToResponse(articleRepository.save(article));
    }

    @Transactional
    public ArticleResponse updateArticle(UUID id, UpdateArticleRequest req) {
        Article article = articleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Article", id));

        if (req.getTitle() != null) article.setTitle(req.getTitle());
        if (req.getSlug() != null && !req.getSlug().isBlank() && !req.getSlug().equals(article.getSlug())) {
            if (articleRepository.existsBySlug(req.getSlug())) {
                throw new BusinessException("DUPLICATE_SLUG", "Slug đã tồn tại");
            }
            article.setSlug(req.getSlug());
        }
        if (req.getContent() != null) article.setContent(req.getContent());
        if (req.getCoverImage() != null) article.setCoverImage(req.getCoverImage());
        if (req.getAuthorName() != null) article.setAuthorName(req.getAuthorName());
        if (req.getType() != null) article.setType(req.getType());
        if (req.getIsActive() != null) article.setIsActive(req.getIsActive());

        return mapToResponse(articleRepository.save(article));
    }

    @Transactional
    public void deleteArticle(UUID id) {
        if (!articleRepository.existsById(id)) {
            throw new ResourceNotFoundException("Article", id);
        }
        articleRepository.deleteById(id);
    }

    private ArticleResponse mapToResponse(Article a) {
        return ArticleResponse.builder()
                .id(a.getId())
                .title(a.getTitle())
                .slug(a.getSlug())
                .content(a.getContent())
                .coverImage(a.getCoverImage())
                .authorName(a.getAuthorName())
                .type(a.getType())
                .isActive(a.getIsActive())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .createdBy(a.getCreatedBy())
                .updatedBy(a.getUpdatedBy())
                .build();
    }
}
