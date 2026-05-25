package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.CreatePromotionRequest;
import sme.backend.dto.response.PromotionResponse;
import sme.backend.entity.Promotion;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.PromotionRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromotionService {

    private final PromotionRepository promotionRepository;

    @Transactional
    public PromotionResponse create(CreatePromotionRequest req) {
        if (promotionRepository.existsByCode(req.getCode().toUpperCase())) {
            throw new BusinessException("DUPLICATE_CODE", "Mã khuyến mãi đã tồn tại: " + req.getCode());
        }

        Promotion.DiscountType type;
        try {
            type = Promotion.DiscountType.valueOf(req.getDiscountType());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_TYPE", "Loại giảm giá không hợp lệ: " + req.getDiscountType());
        }

        Promotion.PromotionSlot slot = req.getPromotionSlot() != null ? Promotion.PromotionSlot.valueOf(req.getPromotionSlot()) : Promotion.PromotionSlot.ORDER;
        Promotion.ApplicableChannel channel = req.getApplicableChannel() != null ? Promotion.ApplicableChannel.valueOf(req.getApplicableChannel()) : Promotion.ApplicableChannel.ALL;
        Promotion.TriggerType trigger = req.getTriggerType() != null ? Promotion.TriggerType.valueOf(req.getTriggerType()) : Promotion.TriggerType.MANUAL;
        Promotion.ConditionType condition = null;
        if (req.getConditionType() != null && !"NULL".equalsIgnoreCase(req.getConditionType())) {
            condition = Promotion.ConditionType.valueOf(req.getConditionType());
        }

        Promotion promo = Promotion.builder()
                .code(req.getCode().toUpperCase())
                .name(req.getName())
                .description(req.getDescription())
                .discountType(type)
                .discountValue(req.getDiscountValue())
                .minOrderValue(req.getMinOrderValue())
                .maxDiscountAmount(req.getMaxDiscountAmount())
                .maxUsage(req.getMaxUsage())
                .usedCount(0)
                .applicableProductId(req.getApplicableProductId())
                .applicableCategoryId(req.getApplicableCategoryId())
                .buyQuantity(req.getBuyQuantity())
                .getQuantity(req.getGetQuantity())
                .startDate(req.getStartDate())
                .endDate(req.getEndDate())
                .promotionSlot(slot)
                .applicableChannel(channel)
                .triggerType(trigger)
                .conditionType(condition)
                .conditionValue(req.getConditionValue())
                .isActive(true)
                .build();

        promo = promotionRepository.save(promo);
        log.info("Created promotion: {} ({})", promo.getCode(), promo.getDiscountType());
        return toResponse(promo);
    }

    @Transactional
    public PromotionResponse update(UUID id, CreatePromotionRequest req) {
        Promotion promo = promotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion", id));

        // Check duplicate code if changed
        if (!promo.getCode().equals(req.getCode().toUpperCase())) {
            if (promotionRepository.existsByCode(req.getCode().toUpperCase())) {
                throw new BusinessException("DUPLICATE_CODE", "Mã khuyến mãi đã tồn tại: " + req.getCode());
            }
        }

        Promotion.DiscountType type;
        try {
            type = Promotion.DiscountType.valueOf(req.getDiscountType());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_TYPE", "Loại giảm giá không hợp lệ");
        }

        promo.setCode(req.getCode().toUpperCase());
        promo.setName(req.getName());
        promo.setDescription(req.getDescription());
        promo.setDiscountType(type);
        promo.setDiscountValue(req.getDiscountValue());
        promo.setMinOrderValue(req.getMinOrderValue());
        promo.setMaxDiscountAmount(req.getMaxDiscountAmount());
        promo.setMaxUsage(req.getMaxUsage());
        promo.setApplicableProductId(req.getApplicableProductId());
        promo.setApplicableCategoryId(req.getApplicableCategoryId());
        promo.setBuyQuantity(req.getBuyQuantity());
        promo.setGetQuantity(req.getGetQuantity());
        promo.setStartDate(req.getStartDate());
        promo.setEndDate(req.getEndDate());
        promo.setPromotionSlot(req.getPromotionSlot() != null ? Promotion.PromotionSlot.valueOf(req.getPromotionSlot()) : Promotion.PromotionSlot.ORDER);
        promo.setApplicableChannel(req.getApplicableChannel() != null ? Promotion.ApplicableChannel.valueOf(req.getApplicableChannel()) : Promotion.ApplicableChannel.ALL);
        promo.setTriggerType(req.getTriggerType() != null ? Promotion.TriggerType.valueOf(req.getTriggerType()) : Promotion.TriggerType.MANUAL);
        
        Promotion.ConditionType cType = null;
        if (req.getConditionType() != null && !"NULL".equalsIgnoreCase(req.getConditionType())) {
            cType = Promotion.ConditionType.valueOf(req.getConditionType());
        }
        promo.setConditionType(cType);
        promo.setConditionValue(req.getConditionValue());

        promo = promotionRepository.save(promo);
        return toResponse(promo);
    }

    @Transactional
    public void toggleActive(UUID id) {
        Promotion promo = promotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion", id));
        promo.setIsActive(!promo.getIsActive());
        promotionRepository.save(promo);
        log.info("Toggled promotion {} active={}", promo.getCode(), promo.getIsActive());
    }

    @Transactional
    public void delete(UUID id) {
        if (!promotionRepository.existsById(id)) {
            throw new ResourceNotFoundException("Promotion", id);
        }
        promotionRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public PromotionResponse getById(UUID id) {
        return toResponse(promotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion", id)));
    }

    @Transactional(readOnly = true)
    public Page<PromotionResponse> search(String keyword, Pageable pageable) {
        String kw = (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null;
        return promotionRepository.searchPromotions(kw, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<PromotionResponse> getActivePromotions() {
        return promotionRepository.findActivePromotions(Instant.now())
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public BigDecimal applyPromotions(List<String> codes, BigDecimal orderTotal, String channel) {
        BigDecimal totalDiscount = BigDecimal.ZERO;
        boolean hasOrderSlot = false;
        boolean hasShippingSlot = false;

        // 1. ÁP DỤNG MÃ MANUAL (Người dùng nhập)
        if (codes != null && !codes.isEmpty()) {
            for (String code : codes) {
                String cleanCode = code.trim().toUpperCase();
                log.info("[PromotionService] === START VALIDATE MANUAL === code='{}', orderTotal={}, channel={}", cleanCode, orderTotal, channel);

                Promotion promo = promotionRepository.findByCode(cleanCode)
                        .orElseThrow(() -> new BusinessException("PROMO_NOT_FOUND", "Mã khuyến mãi không tồn tại: " + cleanCode));

                if (!promo.isValid(channel)) {
                    throw new BusinessException("PROMO_INVALID", "Mã khuyến mãi không hợp lệ hoặc không áp dụng cho kênh này: " + cleanCode);
                }

                if ("POS".equalsIgnoreCase(channel) && promo.getPromotionSlot() == Promotion.PromotionSlot.SHIPPING) {
                    throw new BusinessException("PROMO_NOT_APPLICABLE", "Tại quầy (POS) không thể áp dụng mã giảm giá vận chuyển: " + cleanCode);
                }

                if (promo.getPromotionSlot() == Promotion.PromotionSlot.ORDER) {
                    if (hasOrderSlot) throw new BusinessException("PROMO_STACK_ERROR", "Chỉ được phép sử dụng 1 mã giảm giá Đơn hàng.");
                    hasOrderSlot = true;
                } else if (promo.getPromotionSlot() == Promotion.PromotionSlot.SHIPPING) {
                    if (hasShippingSlot) throw new BusinessException("PROMO_STACK_ERROR", "Chỉ được phép sử dụng 1 mã giảm giá Vận chuyển.");
                    hasShippingSlot = true;
                }

                if (promo.getMinOrderValue() != null && orderTotal.compareTo(promo.getMinOrderValue()) < 0) {
                    throw new BusinessException("PROMO_NOT_APPLICABLE",
                            "Đơn hàng chưa đạt giá trị tối thiểu " + promo.getMinOrderValue().toBigInteger() + "đ để áp dụng mã: " + cleanCode);
                }

                BigDecimal discount = calculateSinglePromoDiscount(promo, orderTotal);
                totalDiscount = totalDiscount.add(discount);
            }
        }

        // 2. TỰ ĐỘNG QUÉT MÃ AUTO (Hệ thống tự nhận diện nếu chưa dùng Slot)
        List<Promotion> activePromos = promotionRepository.findActivePromotions(Instant.now());
        for (Promotion promo : activePromos) {
            // Chỉ lấy mã AUTO
            if (promo.getTriggerType() != Promotion.TriggerType.AUTO) continue;
            if (!promo.isValid(channel)) continue;

            // Kiểm tra Kênh POS -> Bỏ qua SHIPPING
            if ("POS".equalsIgnoreCase(channel) && promo.getPromotionSlot() == Promotion.PromotionSlot.SHIPPING) continue;

            // Kiểm tra Slot xem đã bị chiếm bởi MANUAL code chưa
            if (promo.getPromotionSlot() == Promotion.PromotionSlot.ORDER) {
                if (hasOrderSlot) continue; 
            } else if (promo.getPromotionSlot() == Promotion.PromotionSlot.SHIPPING) {
                if (hasShippingSlot) continue;
            }

            // Kiểm tra Condition Value cơ bản (VD: DAY_OF_WEEK)
            if (promo.getConditionType() == Promotion.ConditionType.DAY_OF_WEEK && promo.getConditionValue() != null) {
                int currentDay = java.time.LocalDate.now().getDayOfWeek().getValue(); // 1 = Mon, 7 = Sun
                boolean matchDay = java.util.Arrays.stream(promo.getConditionValue().split(","))
                        .map(String::trim)
                        .anyMatch(d -> d.equals(String.valueOf(currentDay)));
                if (!matchDay) continue;
            }

            // Kiểm tra Min Order Value
            if (promo.getMinOrderValue() != null && orderTotal.compareTo(promo.getMinOrderValue()) < 0) {
                continue;
            }

            // Áp dụng thành công mã AUTO
            if (promo.getPromotionSlot() == Promotion.PromotionSlot.ORDER) hasOrderSlot = true;
            if (promo.getPromotionSlot() == Promotion.PromotionSlot.SHIPPING) hasShippingSlot = true;

            BigDecimal autoDiscount = calculateSinglePromoDiscount(promo, orderTotal);
            totalDiscount = totalDiscount.add(autoDiscount);
            log.info("[PromotionService] Auto-applied promo code='{}', discount={}", promo.getCode(), autoDiscount);
        }

        return totalDiscount;
    }

    private BigDecimal calculateSinglePromoDiscount(Promotion promo, BigDecimal orderTotal) {
        BigDecimal discount = BigDecimal.ZERO;
        Promotion.DiscountType type = promo.getDiscountType();
        BigDecimal value = promo.getDiscountValue();
        log.info("[PromotionService] Calculating: type={}, discountValue={}, discountType.class={}",
            type, value, type != null ? type.getClass().getName() : "NULL");

        if (type == null) {
            // discountType is null in DB → fallback to FIXED_AMOUNT
            log.warn("[PromotionService] discountType is NULL! Using discountValue as fixed amount");
            if (value != null && value.compareTo(BigDecimal.ZERO) > 0) {
                discount = value.min(orderTotal);
            }
        } else if (type == Promotion.DiscountType.PERCENTAGE) {
            if (value != null) {
                discount = orderTotal.multiply(value)
                        .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.HALF_UP);
                // Chỉ giới hạn nếu maxDiscountAmount > 0 (tránh bug khi DB lưu 0.00 thay vì NULL)
                if (promo.getMaxDiscountAmount() != null
                        && promo.getMaxDiscountAmount().compareTo(BigDecimal.ZERO) > 0
                        && discount.compareTo(promo.getMaxDiscountAmount()) > 0) {
                    discount = promo.getMaxDiscountAmount();
                }
            }
        } else if (type == Promotion.DiscountType.FIXED_AMOUNT) {
            if (value != null) {
                discount = value.min(orderTotal);
            }
        } else {
            // BUY_X_GET_Y hoặc type lạ
            log.warn("[PromotionService] DiscountType '{}' → dùng discountValue={} làm FIXED", type, value);
            if (value != null && value.compareTo(BigDecimal.ZERO) > 0) {
                discount = value.min(orderTotal);
            }
        }

        if (discount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("PROMO_NOT_APPLICABLE", "Mã khuyến mãi không áp dụng được.");
        }

        return discount;
    }

    /**
     * Sau khi checkout thành công, tăng usedCount.
     */
    @Transactional
    public void markUsed(String code) {
        promotionRepository.findByCode(code.toUpperCase()).ifPresent(promo -> {
            promo.incrementUsage();
            promotionRepository.save(promo);
        });
    }

    private PromotionResponse toResponse(Promotion p) {
        return PromotionResponse.builder()
                .id(p.getId())
                .code(p.getCode())
                .name(p.getName())
                .description(p.getDescription())
                .discountType(p.getDiscountType() != null ? p.getDiscountType().name() : "PERCENTAGE")
                .discountValue(p.getDiscountValue())
                .minOrderValue(p.getMinOrderValue())
                .maxDiscountAmount(p.getMaxDiscountAmount())
                .maxUsage(p.getMaxUsage())
                .usedCount(p.getUsedCount() != null ? p.getUsedCount() : 0)
                .applicableProductId(p.getApplicableProductId())
                .applicableCategoryId(p.getApplicableCategoryId())
                .buyQuantity(p.getBuyQuantity())
                .getQuantity(p.getGetQuantity())
                .startDate(p.getStartDate())
                .endDate(p.getEndDate())
                .isActive(p.getIsActive() != null ? p.getIsActive() : true)
                .valid(p.isValid(null))
                .promotionSlot(p.getPromotionSlot() != null ? p.getPromotionSlot().name() : "ORDER")
                .applicableChannel(p.getApplicableChannel() != null ? p.getApplicableChannel().name() : "ALL")
                .triggerType(p.getTriggerType() != null ? p.getTriggerType().name() : "MANUAL")
                .conditionType(p.getConditionType() != null ? p.getConditionType().name() : "NULL")
                .conditionValue(p.getConditionValue())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
