package sme.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.CreatePromotionRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.PageResponse;
import sme.backend.dto.response.PromotionResponse;
import sme.backend.exception.BusinessException;
import sme.backend.service.PromotionService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/promotions")
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class PromotionController {

    private final PromotionService promotionService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PromotionResponse>> create(@Valid @RequestBody CreatePromotionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok("Tạo khuyến mãi thành công", promotionService.create(req)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PromotionResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody CreatePromotionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok("Cập nhật thành công", promotionService.update(id, req)));
    }

    @PatchMapping("/{id}/toggle")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> toggle(@PathVariable UUID id) {
        promotionService.toggleActive(id);
        return ResponseEntity.ok(ApiResponse.ok("Đã thay đổi trạng thái", null));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> delete(@PathVariable UUID id) {
        promotionService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Đã xóa khuyến mãi", null));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN','CASHIER')")
    public ResponseEntity<ApiResponse<PromotionResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(promotionService.getById(id)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN','CASHIER')")
    public ResponseEntity<ApiResponse<PageResponse<PromotionResponse>>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.of(promotionService.search(keyword, pageable))));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<PromotionResponse>>> getActive() {
        return ResponseEntity.ok(ApiResponse.ok(promotionService.getActivePromotions()));
    }

    @PostMapping("/validate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validate(@RequestBody Map<String, Object> body) {
        log.info("[Controller] Promotion validate request body: {}", body);
        if (body == null || body.isEmpty()) {
            throw new BusinessException("EMPTY_BODY", "Request body is empty");
        }

        try {
            String code = body.get("code") != null ? body.get("code").toString() : null;
            Object codesObj = body.get("codes");
            String channel = body.get("channel") != null ? body.get("channel").toString() : "ONLINE"; // Default ONLINE
            
            List<String> codesToValidate = new java.util.ArrayList<>();
            if (code != null && !code.isBlank()) {
                codesToValidate.add(code);
            }
            if (codesObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) codesObj;
                for (String c : list) {
                    if (c != null && !c.isBlank() && !codesToValidate.contains(c)) {
                        codesToValidate.add(c);
                    }
                }
            }

            Object totalObj = body.get("orderTotal");
            BigDecimal orderTotal = BigDecimal.ZERO;

            if (totalObj != null) {
                if (totalObj instanceof Number) {
                    orderTotal = new BigDecimal(totalObj.toString());
                } else if (totalObj instanceof String) {
                    orderTotal = new BigDecimal((String) totalObj);
                }
            }

            log.info("[Controller] Validation: codes='{}', orderTotal={}, channel={}", codesToValidate, orderTotal, channel);
            BigDecimal discount = promotionService.applyPromotions(codesToValidate, orderTotal, channel);

            Map<String, Object> data = new java.util.HashMap<>();
            data.put("codes", codesToValidate);
            data.put("discountAmount", discount != null ? discount : BigDecimal.ZERO);
            data.put("finalAmount",
                    orderTotal != null ? orderTotal.subtract(discount != null ? discount : BigDecimal.ZERO)
                            : BigDecimal.ZERO);

            return ResponseEntity.ok(ApiResponse.ok(data));
        } catch (BusinessException e) {
            log.warn("[Controller] Business error: {} - {}", e.getCode(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[Controller] Unexpected error during validation: ", e);
            throw e;
        }
    }
}
