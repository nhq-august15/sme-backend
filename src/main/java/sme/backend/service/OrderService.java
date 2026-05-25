package sme.backend.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.CreateOrderRequest;
import sme.backend.dto.response.OrderResponse;
import sme.backend.entity.*;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final WarehouseRepository warehouseRepository;
    private final InventoryService inventoryService;
    private final NotificationService notificationService;
    private final CashbookTransactionRepository cashbookRepository;
    private final InternalTransferRepository transferRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final InvoiceRepository invoiceRepository;
    private final ProductReviewRepository productReviewRepository; // <-- ĐÃ THÊM

    private final EntityManager entityManager;



    private UUID getCurrentUserIdSafe() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null; // Trả về null nếu guest
        }
        try {
            Object principal = auth.getPrincipal();
            java.lang.reflect.Method method = principal.getClass().getMethod("getId");
            Object id = method.invoke(principal);
            if (id instanceof UUID)
                return (UUID) id;
            if (id != null)
                return UUID.fromString(id.toString());
        } catch (Exception e) {
            log.warn("Không lấy được ID qua Token, chuyển sang quét DB bằng username...");
        }
        try {
            String username = auth.getName();
            if (username != null && !username.isEmpty()) {
                List<?> results = entityManager
                        .createNativeQuery("SELECT id FROM users WHERE username = :username LIMIT 1")
                        .setParameter("username", username)
                        .getResultList();
                if (!results.isEmpty() && results.get(0) != null) {
                    return UUID.fromString(results.get(0).toString());
                }
            }
        } catch (Exception e) {
            log.warn("Lỗi khi query bảng users: {}", e.getMessage());
        }
        return null;
    }

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest req) {
        Customer customer = customerRepository.findById(req.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", req.getCustomerId()));

        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        UUID currentUserId = getCurrentUserIdSafe();

        for (CreateOrderRequest.OrderItemRequest itemReq : req.getItems()) {
            Product product = productRepository.findById(itemReq.getProductId()).orElseThrow();

            Integer totalAvailObj = inventoryRepository.getTotalAvailableQuantity(product.getId());
            int available = totalAvailObj != null ? totalAvailObj : 0;

            if (available < itemReq.getQuantity()) {
                throw new BusinessException("INSUFFICIENT_STOCK",
                        "Sản phẩm '" + product.getName() + "' không đủ tồn kho trên toàn hệ thống.");
            }
            BigDecimal subtotal = product.getRetailPrice().multiply(BigDecimal.valueOf(itemReq.getQuantity()));
            orderItems.add(OrderItem.builder()
                    .productId(product.getId())
                    .quantity(itemReq.getQuantity())
                    .unitPrice(product.getRetailPrice())
                    .macPrice(product.getMacPrice() != null ? product.getMacPrice() : BigDecimal.ZERO)
                    .subtotal(subtotal)
                    .build());
            totalAmount = totalAmount.add(subtotal);
        }

        BigDecimal shippingFee = req.getShippingFee() != null ? req.getShippingFee() : BigDecimal.ZERO;
        BigDecimal discountAmount = req.getDiscountAmount() != null ? req.getDiscountAmount() : BigDecimal.ZERO;
        BigDecimal finalAmount = totalAmount.add(shippingFee).subtract(discountAmount);

        UUID assignedWarehouseId = null;
        Map<String, Object> chosenPlan = null;

        List<Map<String, Object>> plans = suggestBranchesForOrder(req.getProvinceCode(), req.getItems());
        if (plans.isEmpty())
            throw new BusinessException("NO_WAREHOUSE", "Không đủ hàng để gom chung 1 kiện trên toàn hệ thống.");

        // Chọn kho tối ưu nhất (ở index 0 vì list đã được sort)
        chosenPlan = plans.get(0);
        assignedWarehouseId = (UUID) chosenPlan.get("warehouseId");

        if (chosenPlan == null)
            throw new BusinessException("INVALID_WAREHOUSE", "Kho được chọn không hợp lệ.");

        Order.OrderType orderType = "BOPIS".equalsIgnoreCase(req.getType()) ? Order.OrderType.BOPIS
                : Order.OrderType.DELIVERY;
        boolean isReadyToShip = (Boolean) chosenPlan.get("isReadyToShip");

        // Logic trạng thái theo paymentMethod
        Order.OrderStatus initialStatus;
        if ("COD".equalsIgnoreCase(req.getPaymentMethod())) {
            initialStatus = isReadyToShip ? Order.OrderStatus.PENDING : Order.OrderStatus.WAITING_FOR_CONSOLIDATION;
        } else {
            initialStatus = Order.OrderStatus.PAYMENT_PENDING;
        }

        Order order = Order.builder()
                .code(generateOrderCode()).customerId(customer.getId()).assignedWarehouseId(assignedWarehouseId)
                .type(orderType).shippingName(req.getShippingName()).shippingPhone(req.getShippingPhone())
                .shippingAddress(req.getShippingAddress()).provinceCode(req.getProvinceCode()).totalAmount(totalAmount)
                .shippingFee(shippingFee).discountAmount(discountAmount).finalAmount(finalAmount).paymentMethod(req.getPaymentMethod())
                .paymentStatus(Order.PaymentStatus.UNPAID).note(req.getNote())
                .status(initialStatus)
                .build();
        orderItems.forEach(order::addItem);
        order = orderRepository.save(order);

        // Đặt trước hàng tồn kho tại kho được chỉ định
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> availableItems = (List<Map<String, Object>>) chosenPlan.get("availableItems");
        inventoryService.reserveForOnlineOrderBatch(availableItems, assignedWarehouseId, order.getId(), "SYSTEM");

        // Nếu cần tạo InternalTransfer để gom hàng
        if (!isReadyToShip) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transferReqs = (List<Map<String, Object>>) chosenPlan.get("transferRequirements");

            Map<UUID, List<Map<String, Object>>> transfersBySource = transferReqs.stream()
                    .collect(Collectors.groupingBy(reqMap -> (UUID) reqMap.get("fromWarehouseId")));

            for (Map.Entry<UUID, List<Map<String, Object>>> entry : transfersBySource.entrySet()) {
                UUID sourceWarehouseId = entry.getKey();

                UUID transferCreatorId = null;
                boolean isEmployee = false;
                if (currentUserId != null) {
                    isEmployee = userRepository.existsById(currentUserId);
                }
                
                if (isEmployee) {
                    transferCreatorId = currentUserId;
                } else {
                    transferCreatorId = userRepository.findByRoleAndIsActiveTrue(User.UserRole.ROLE_ADMIN)
                            .stream().findFirst().map(User::getId).orElse(null);
                    if (transferCreatorId == null) {
                        transferCreatorId = userRepository.findAllActive().stream().findFirst().map(User::getId).orElse(null);
                    }
                    if (transferCreatorId == null) {
                        throw new BusinessException("SYSTEM_ERROR", "Không thể tự động luân chuyển kho: Hệ thống không có nhân viên nào đang hoạt động.");
                    }
                }

                InternalTransfer transfer = InternalTransfer.builder()
                        .code("TRF-AUTO-" + System.currentTimeMillis() + "-"
                                + sourceWarehouseId.toString().substring(0, 4))
                        .fromWarehouseId(sourceWarehouseId).toWarehouseId(assignedWarehouseId)
                        .createdByUserId(transferCreatorId) // Gán ID Nhân viên hợp lệ
                        .status(InternalTransfer.TransferStatus.DRAFT)
                        .referenceOrderId(order.getId())
                        .note("Tự động tạo - Gom hàng cho Đơn #" + order.getCode())
                        .build();

                if (transfer.getItems() == null) {
                    transfer.setItems(new ArrayList<>());
                }

                for (Map<String, Object> reqItem : entry.getValue()) {
                    UUID pId = (UUID) reqItem.get("productId");
                    int qty = (Integer) reqItem.get("quantity");
                    transfer.addItem(TransferItem.builder().productId(pId).quantity(qty).build());
                }
                inventoryService.reserveForOnlineOrderBatch(entry.getValue(), sourceWarehouseId, order.getId(),
                        "SYSTEM_CONSOLIDATION");
                transferRepository.save(transfer);
                notificationService.notifyTransferArrived(transfer.getId(), sourceWarehouseId);
            }
        }

        notificationService.notifyNewOrder(order, assignedWarehouseId);
        
        // Cố gắng gửi email cho khách
        if (customer.getEmail() != null && !customer.getEmail().isBlank()) {
            try {
                emailService.sendOrderStatusEmail(
                    customer.getEmail(),
                    customer.getFullName() != null ? customer.getFullName() : order.getShippingName(),
                    order.getCode(),
                    order.getStatus().name(),
                    order.getFinalAmount().doubleValue()
                );
            } catch (Exception e) {
                log.warn("Không thể gửi email cho khách: {}", e.getMessage());
            }
        }

        return mapToResponse(order);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> suggestBranchesForOrder(String provinceCode,
            List<CreateOrderRequest.OrderItemRequest> items) {
        if (items == null || items.isEmpty())
            return List.of();
        List<Warehouse> activeWarehouses = warehouseRepository.findByIsActiveTrueOrderByName();
        List<Map<String, Object>> suggestions = new ArrayList<>();
        List<UUID> productIds = items.stream().map(CreateOrderRequest.OrderItemRequest::getProductId).toList();
        List<Inventory> allInventories = inventoryRepository.findByProductIdIn(productIds);
        Map<UUID, Map<UUID, Integer>> stockMatrix = new HashMap<>();
        for (Inventory inv : allInventories) {
            stockMatrix.computeIfAbsent(inv.getWarehouseId(), k -> new HashMap<>())
                    .put(inv.getProductId(), inv.getAvailableQuantity());
        }

        for (Warehouse targetWarehouse : activeWarehouses) {
            boolean isSameProvince = provinceCode != null && provinceCode.equals(targetWarehouse.getProvinceCode());
            List<Map<String, Object>> availableItems = new ArrayList<>();
            List<Map<String, Object>> transferRequirements = new ArrayList<>();
            boolean isReadyToShip = true;
            Map<UUID, Integer> targetStock = stockMatrix.getOrDefault(targetWarehouse.getId(), Collections.emptyMap());

            for (CreateOrderRequest.OrderItemRequest item : items) {
                int requiredQty = item.getQuantity();
                int currentStock = targetStock.getOrDefault(item.getProductId(), 0);

                if (currentStock >= requiredQty) {
                    availableItems.add(Map.of("productId", item.getProductId(), "quantity", requiredQty));
                } else {
                    isReadyToShip = false;
                    if (currentStock > 0) {
                        availableItems.add(Map.of("productId", item.getProductId(), "quantity", currentStock));
                    }
                    int missingQty = requiredQty - currentStock;
                    int remainingToFind = missingQty;
                    for (Warehouse sourceWarehouse : activeWarehouses) {
                        if (sourceWarehouse.getId().equals(targetWarehouse.getId()))
                            continue;
                        if (remainingToFind <= 0)
                            break;

                        Map<UUID, Integer> sourceStockMap = stockMatrix.getOrDefault(sourceWarehouse.getId(),
                                Collections.emptyMap());
                        int sourceStock = sourceStockMap.getOrDefault(item.getProductId(), 0);

                        if (sourceStock > 0) {
                            int takeQty = Math.min(sourceStock, remainingToFind);
                            remainingToFind -= takeQty;
                            Product prod = productRepository.findById(item.getProductId()).orElseThrow();
                            transferRequirements.add(Map.of(
                                    "fromWarehouseId", sourceWarehouse.getId(),
                                    "fromWarehouseName", sourceWarehouse.getName(),
                                    "productId", item.getProductId(),
                                    "productName", prod.getName(),
                                    "quantity", takeQty));
                        }
                    }
                    if (remainingToFind > 0) {
                        isReadyToShip = false;
                        transferRequirements.clear();
                        break;
                    }
                }
            }
            if (!isReadyToShip && transferRequirements.isEmpty())
                continue;

            Map<String, Object> plan = new HashMap<>();
            plan.put("warehouseId", targetWarehouse.getId());
            plan.put("warehouseName", targetWarehouse.getName());
            plan.put("isSameProvince", isSameProvince);
            plan.put("isReadyToShip", isReadyToShip);
            plan.put("availableItems", availableItems);
            plan.put("transferRequirements", transferRequirements);

            int score = 0;
            if (isReadyToShip)
                score += 1000;
            if (isSameProvince)
                score += 500;
            score -= transferRequirements.size() * 10;
            plan.put("sortScore", score);
            suggestions.add(plan);
        }
        suggestions.sort((a, b) -> Integer.compare((Integer) b.get("sortScore"), (Integer) a.get("sortScore")));
        return suggestions;
    }

    @Transactional
    public OrderResponse updateStatus(UUID orderId, String newStatus, String note, String trackingCode,
            String shippingProvider, String changedBy) {
        Order order = orderRepository.findByIdWithDetails(orderId).orElseThrow();
        Order.OrderStatus status = Order.OrderStatus.valueOf(newStatus.toUpperCase());
        order.transitionTo(status, note, changedBy);

        if (trackingCode != null)
            order.setTrackingCode(trackingCode);
        if (shippingProvider != null)
            order.setShippingProvider(shippingProvider);

        if (status == Order.OrderStatus.PACKING) {
            try {
                order.setPackedBy(UUID.fromString(changedBy));
            } catch (Exception ignored) {
            }
            order.setPackedAt(Instant.now());
        }

        if ((status == Order.OrderStatus.SHIPPING
                || (status == Order.OrderStatus.DELIVERED && order.getType() == Order.OrderType.BOPIS))
                && order.getAssignedWarehouseId() != null) {
            boolean alreadyShipped = order.getStatusHistory().stream()
                    .anyMatch(h -> "SHIPPING".equals(h.getNewStatus()));
            if (!alreadyShipped || status == Order.OrderStatus.SHIPPING) {
                order.getItems().forEach(item -> inventoryService.confirmOnlineShipment(item.getProductId(),
                        order.getAssignedWarehouseId(), item.getQuantity(), orderId, changedBy));
            }
        }

        if (status == Order.OrderStatus.CANCELLED) {
            List<InternalTransfer> transfers = transferRepository.findByReferenceOrderId(orderId);
            Map<UUID, Integer> stuckTransferQtys = new HashMap<>();

            for (InternalTransfer transfer : transfers) {
                if (transfer.getStatus() == InternalTransfer.TransferStatus.DRAFT) {
                    transfer.setStatus(InternalTransfer.TransferStatus.CANCELLED);
                    transfer.setNote((transfer.getNote() != null ? transfer.getNote() : "")
                            + " | Hủy tự động do Đơn hàng " + order.getCode() + " bị khách hủy.");
                    transferRepository.save(transfer);

                    for (TransferItem tItem : transfer.getItems()) {
                        inventoryService.releaseReservation(tItem.getProductId(), transfer.getFromWarehouseId(),
                                tItem.getQuantity(), orderId, changedBy);
                        stuckTransferQtys.put(tItem.getProductId(),
                                stuckTransferQtys.getOrDefault(tItem.getProductId(), 0) + tItem.getQuantity());
                    }
                } else if (transfer.getStatus() == InternalTransfer.TransferStatus.DISPATCHED) {
                    transfer.setStatus(InternalTransfer.TransferStatus.CANCELLED);
                    transfer.setNote((transfer.getNote() != null ? transfer.getNote() : "")
                            + " | Hủy tự động do Đơn hàng hủy. Hàng đang đi đường được hoàn trả về kho gốc.");
                    transferRepository.save(transfer);

                    for (TransferItem tItem : transfer.getItems()) {
                        Inventory srcInv = inventoryRepository
                                .findByProductIdAndWarehouseId(tItem.getProductId(), transfer.getFromWarehouseId())
                                .orElse(null);
                        if (srcInv != null) {
                            int before = srcInv.getQuantity() != null ? srcInv.getQuantity() : 0;
                            srcInv.setInTransit(Math.max(0, srcInv.getInTransit() - tItem.getQuantity()));
                            srcInv.setQuantity(srcInv.getQuantity() + tItem.getQuantity());
                            inventoryRepository.save(srcInv);
                            inventoryService.recordTransaction(srcInv, transfer.getId(), "CANCEL_TRANSFER",
                                    tItem.getQuantity(), before, srcInv.getQuantity(), changedBy,
                                    "Khách hủy đơn, quay đầu hàng đang luân chuyển");
                        }
                        stuckTransferQtys.put(tItem.getProductId(),
                                stuckTransferQtys.getOrDefault(tItem.getProductId(), 0) + tItem.getQuantity());
                    }
                }
            }

            if (order.getAssignedWarehouseId() != null) {
                for (OrderItem item : order.getItems()) {
                    int totalRequired = item.getQuantity();
                    int stuckQty = stuckTransferQtys.getOrDefault(item.getProductId(), 0);
                    int qtyToReleaseAtAssigned = totalRequired - stuckQty;
                    if (qtyToReleaseAtAssigned > 0) {
                        inventoryService.releaseReservation(item.getProductId(), order.getAssignedWarehouseId(),
                                qtyToReleaseAtAssigned, orderId, changedBy);
                    }
                }
            }
            order.setCancelledReason(note);
        }

        if (status == Order.OrderStatus.RETURNED && order.getAssignedWarehouseId() != null) {
            order.getItems().forEach(item -> {
                inventoryService.returnToStock(
                    item.getProductId(), order.getAssignedWarehouseId(), item.getQuantity(), orderId, "RETURNED_ORDER",
                    changedBy);
                Product p = productRepository.findById(item.getProductId()).orElse(null);
                if (p != null) {
                    p.setSoldQuantity(Math.max(0, (p.getSoldQuantity() != null ? p.getSoldQuantity() : 0) - item.getQuantity()));
                    productRepository.save(p);
                }
            });
        }

        if (status == Order.OrderStatus.DELIVERED) {
            order.getItems().forEach(item -> {
                Product p = productRepository.findById(item.getProductId()).orElse(null);
                if (p != null) {
                    p.setSoldQuantity((p.getSoldQuantity() != null ? p.getSoldQuantity() : 0) + item.getQuantity());
                    productRepository.save(p);
                }
            });
            if ("COD".equals(order.getPaymentMethod())) {
                order.setPaymentStatus(Order.PaymentStatus.PAID);
                recordCODRevenue(order);
            } else if ("BANK_TRANSFER".equals(order.getPaymentMethod())
                    && order.getPaymentStatus() == Order.PaymentStatus.UNPAID) {
                order.setPaymentStatus(Order.PaymentStatus.PAID);
                recordBankTransferRevenue(order, changedBy);
            } else if ("CASH".equals(order.getPaymentMethod())
                    && order.getPaymentStatus() == Order.PaymentStatus.UNPAID) {
                order.setPaymentStatus(Order.PaymentStatus.PAID);
                recordCashRevenue(order, changedBy);
            }
        }

        if (status == Order.OrderStatus.PENDING || status == Order.OrderStatus.DELIVERED
                || status == Order.OrderStatus.CANCELLED) {
            Customer customer = order.getCustomerId() != null
                    ? customerRepository.findById(order.getCustomerId()).orElse(null)
                    : null;
            if (customer != null && customer.getEmail() != null && !customer.getEmail().isBlank()) {
                try {
                    emailService.sendOrderStatusEmail(
                            customer.getEmail(),
                            customer.getFullName() != null ? customer.getFullName() : order.getShippingName(),
                            order.getCode(),
                            status.name(),
                            order.getFinalAmount().doubleValue());
                } catch (Exception e) {
                    log.warn("Không gửi được email cho đơn hàng {}: {}", order.getCode(), e.getMessage());
                }
            }
        }

        return mapToResponse(orderRepository.save(order));
    }

    @Transactional
    public void markAsPaid(UUID orderId, String gateway) {
        Order order = orderRepository.findByIdWithDetails(orderId).orElseThrow();
        if (order.getPaymentStatus() == Order.PaymentStatus.UNPAID) {
            order.setPaymentStatus(Order.PaymentStatus.PAID);
            if ("BANK_TRANSFER".equals(order.getPaymentMethod())) {
                recordBankTransferRevenue(order, "SYSTEM_" + gateway);
            }
            orderRepository.save(order);
        }
    }

    private void recordCODRevenue(Order order) {
        if (order.getAssignedWarehouseId() == null)
            return;
        cashbookRepository.save(CashbookTransaction.builder().warehouseId(order.getAssignedWarehouseId())
                .fundType(CashbookTransaction.FundType.CASH_111).transactionType(CashbookTransaction.TransactionType.IN)
                .referenceType("SALE_ONLINE").referenceId(order.getId()).amount(order.getFinalAmount())
                .description("Thu COD đơn hàng #" + order.getCode()).createdBy("SYSTEM").build());
    }

    @Transactional
    public OrderResponse assignWarehouse(UUID orderId, UUID newWarehouseId, String reason, String changedBy) {
        Order order = orderRepository.findByIdWithDetails(orderId).orElseThrow();
        if (order.getStatus() != Order.OrderStatus.PENDING && order.getStatus() != Order.OrderStatus.WAITING_FOR_CONSOLIDATION) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể chuyển kho khi đơn hàng đang chờ xử lý hoặc chờ gom hàng.");
        }

        Warehouse oldWarehouse = null;
        if (order.getAssignedWarehouseId() != null) {
            oldWarehouse = warehouseRepository.findById(order.getAssignedWarehouseId()).orElse(null);
        }
        Warehouse newWarehouse = warehouseRepository.findById(newWarehouseId).orElseThrow();

        // 1. Hủy các phiếu chuyển kho (DRAFT/DISPATCHED) liên quan đến đơn hàng này nếu đang gom hàng
        if (order.getStatus() == Order.OrderStatus.WAITING_FOR_CONSOLIDATION) {
            List<InternalTransfer> transfers = transferRepository.findByReferenceOrderId(orderId);
            for (InternalTransfer transfer : transfers) {
                if (transfer.getStatus() == InternalTransfer.TransferStatus.DRAFT) {
                    transfer.setStatus(InternalTransfer.TransferStatus.CANCELLED);
                    transfer.setNote((transfer.getNote() != null ? transfer.getNote() : "") + " | Hủy do Override đổi kho xử lý.");
                    transferRepository.save(transfer);
                    // Nhả tồn kho đã giữ ở kho gửi (nơi sẽ lấy hàng đi gom)
                    for (TransferItem tItem : transfer.getItems()) {
                        inventoryService.releaseReservation(tItem.getProductId(), transfer.getFromWarehouseId(), tItem.getQuantity(), orderId, changedBy);
                    }
                }
            }
        }

        // 2. Nhả tồn kho đã giữ tại kho ĐÓNG GÓI cũ
        if (order.getAssignedWarehouseId() != null) {
            for (OrderItem item : order.getItems()) {
                // TODO: Chỉ nhả số lượng thực tế đã giữ tại kho này (trừ đi phần đã bị kẹt ở các transfer đang đi đường)
                // Để đơn giản ở MVP, nhả toàn bộ số lượng yêu cầu của item
                inventoryService.releaseReservation(item.getProductId(), order.getAssignedWarehouseId(), item.getQuantity(), orderId, changedBy);
            }
        }

        // 3. Cập nhật kho mới và giữ tồn kho tại kho mới
        order.setAssignedWarehouseId(newWarehouseId);
        String note = "Điều hướng từ " + (oldWarehouse != null ? oldWarehouse.getName() : "Chưa gán") + " sang " + newWarehouse.getName();
        if (reason != null && !reason.isBlank()) {
            note += " | Lý do: " + reason;
        }

        // Giữ tồn kho ở kho mới (giả sử có đủ, nếu không đủ ở MVP sẽ âm tạm thời hoặc cần tính lại logic gom hàng)
        for (OrderItem item : order.getItems()) {
            inventoryService.reserveForOnlineOrderBatch(
                List.of(Map.of("productId", item.getProductId(), "quantity", item.getQuantity())), 
                newWarehouseId, orderId, changedBy
            );
        }

        // Tạm thời nếu Override thủ công, ta đẩy về PENDING để kho mới xử lý, hủy trạng thái WAITING_FOR_CONSOLIDATION
        order.transitionTo(Order.OrderStatus.PENDING, note, changedBy);

        return mapToResponse(orderRepository.save(order));
    }

    private void recordCashRevenue(Order order, String changedBy) {
        if (order.getAssignedWarehouseId() == null)
            return;
        cashbookRepository.save(CashbookTransaction.builder().warehouseId(order.getAssignedWarehouseId())
                .fundType(CashbookTransaction.FundType.CASH_111).transactionType(CashbookTransaction.TransactionType.IN)
                .referenceType("SALE_ONLINE").referenceId(order.getId()).amount(order.getFinalAmount())
                .description("Thu tiền mặt đơn hàng #" + order.getCode())
                .createdBy(changedBy != null ? changedBy : "SYSTEM").build());
    }

    private void recordBankTransferRevenue(Order order, String changedBy) {
        if (order.getAssignedWarehouseId() == null)
            return;
        cashbookRepository.save(CashbookTransaction.builder()
                .warehouseId(order.getAssignedWarehouseId())
                .fundType(CashbookTransaction.FundType.BANK_112)
                .transactionType(CashbookTransaction.TransactionType.IN)
                .referenceType("SALE_ONLINE")
                .referenceId(order.getId())
                .amount(order.getFinalAmount())
                .description("Thu chuyển khoản đơn hàng #" + order.getCode())
                .createdBy(changedBy != null ? changedBy : "SYSTEM")
                .build());
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrders(UUID warehouseId, Order.OrderStatus status, Order.OrderType type,
            String keyword, Pageable pageable) {
        return orderRepository.searchOrders(warehouseId, status, type, keyword, pageable)
                .map(this::mapToSimpleResponse);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderDetail(UUID orderId) {
        return mapToResponse(orderRepository.findByIdWithDetails(orderId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getPendingOrders(UUID warehouseId) {
        return orderRepository.findPendingOrdersByWarehouse(warehouseId).stream().map(this::mapToSimpleResponse)
                .toList();
    }

    private String generateOrderCode() {
        return "ORD-" + System.currentTimeMillis();
    }

    public OrderResponse mapToSimpleResponse(Order order) {
        String custName = "Khách lẻ", custPhone = null;
        if (order.getCustomerId() != null) {
            var customer = customerRepository.findById(order.getCustomerId()).orElse(null);
            if (customer != null) {
                custName = customer.getFullName();
                custPhone = customer.getPhoneNumber();
            }
        }
        String warehouseName = null;
        if (order.getAssignedWarehouseId() != null) {
            warehouseName = warehouseRepository.findById(order.getAssignedWarehouseId()).map(Warehouse::getName)
                    .orElse(null);
        }
        return OrderResponse.builder()
                .id(order.getId()).code(order.getCode()).customerId(order.getCustomerId())
                .customerName(custName).customerPhone(custPhone)
                .assignedWarehouseId(order.getAssignedWarehouseId()).assignedWarehouseName(warehouseName)
                .status(order.getStatus() != null ? order.getStatus().name() : null)
                .type(order.getType() != null ? order.getType().name() : null)
                .shippingName(order.getShippingName()).shippingPhone(order.getShippingPhone())
                .shippingAddress(order.getShippingAddress()).provinceCode(order.getProvinceCode())
                .totalAmount(order.getTotalAmount()).shippingFee(order.getShippingFee())
                .discountAmount(order.getDiscountAmount()).finalAmount(order.getFinalAmount())
                .paymentMethod(order.getPaymentMethod())
                .paymentStatus(order.getPaymentStatus() != null ? order.getPaymentStatus().name() : null)
                .trackingCode(order.getTrackingCode()).shippingProvider(order.getShippingProvider())
                .codReconciled(order.getCodReconciled()).note(order.getNote())
                .cancelledReason(order.getCancelledReason())
                .packedBy(order.getPackedBy()).packedAt(order.getPackedAt())
                .createdByName(order.getCreatedBy() != null && !order.getCreatedBy().equals("SYSTEM")
                        ? userRepository.findByUsername(order.getCreatedBy()).map(User::getFullName)
                                .orElse(order.getCreatedBy())
                        : "Hệ thống")
                .createdAt(order.getCreatedAt()).updatedAt(order.getUpdatedAt())
                .items(List.of()).statusHistory(List.of()).build();
    }

    public OrderResponse mapToResponse(Order order) {
        String custName = "Khách lẻ", custPhone = null;
        if (order.getCustomerId() != null) {
            var customer = customerRepository.findById(order.getCustomerId()).orElse(null);
            if (customer != null) {
                custName = customer.getFullName();
                custPhone = customer.getPhoneNumber();
            }
        }
        String warehouseName = null;
        if (order.getAssignedWarehouseId() != null) {
            warehouseName = warehouseRepository.findById(order.getAssignedWarehouseId()).map(Warehouse::getName)
                    .orElse(null);
        }

        String packedByName = null;
        if (order.getPackedBy() != null) {
            packedByName = userRepository.findById(order.getPackedBy()).map(User::getFullName).orElse(null);
        }

        List<OrderResponse.ItemResponse> items = order.getItems() == null ? List.of()
                : order.getItems().stream().map(i -> {
                    var product = productRepository.findById(i.getProductId()).orElse(null);
                    boolean isRev = false;
                    if (order.getCustomerId() != null) {
                        isRev = productReviewRepository.existsByProductIdAndCustomerIdAndOrderId(
                                i.getProductId(), order.getCustomerId(), order.getId());
                    }
                    return OrderResponse.ItemResponse.builder().productId(i.getProductId())
                            .productName(product != null ? product.getName() : null)
                            .isbnBarcode(product != null ? product.getIsbnBarcode() : null).quantity(i.getQuantity())
                            .unitPrice(i.getUnitPrice()).subtotal(i.getSubtotal())
                            .isReviewed(isRev) // <-- ĐÃ THÊM
                            .build();
                }).toList();

        List<OrderResponse.StatusHistoryResponse> history = order.getStatusHistory() == null ? List.of()
                : order.getStatusHistory().stream().map(h -> {
                    String changedByName = "Hệ thống";
                    if (h.getChangedBy() != null && !h.getChangedBy().equals("SYSTEM")) {
                        try {
                            UUID uId = UUID.fromString(h.getChangedBy());
                            changedByName = userRepository.findById(uId).map(User::getFullName)
                                    .orElse(h.getChangedBy());
                        } catch (Exception e) {
                            changedByName = h.getChangedBy();
                        }
                    }
                    return OrderResponse.StatusHistoryResponse.builder()
                            .oldStatus(h.getOldStatus())
                            .newStatus(h.getNewStatus())
                            .note(h.getNote())
                            .changedBy(h.getChangedBy())
                            .changedByName(changedByName)
                            .createdAt(h.getCreatedAt())
                            .build();
                }).toList();

        return OrderResponse.builder()
                .id(order.getId()).code(order.getCode()).customerId(order.getCustomerId())
                .customerName(custName).customerPhone(custPhone)
                .assignedWarehouseId(order.getAssignedWarehouseId()).assignedWarehouseName(warehouseName)
                .status(order.getStatus() != null ? order.getStatus().name() : null)
                .type(order.getType() != null ? order.getType().name() : null)
                .shippingName(order.getShippingName()).shippingPhone(order.getShippingPhone())
                .shippingAddress(order.getShippingAddress()).provinceCode(order.getProvinceCode())
                .totalAmount(order.getTotalAmount()).shippingFee(order.getShippingFee())
                .discountAmount(order.getDiscountAmount()).finalAmount(order.getFinalAmount())
                .paymentMethod(order.getPaymentMethod())
                .paymentStatus(order.getPaymentStatus() != null ? order.getPaymentStatus().name() : null)
                .trackingCode(order.getTrackingCode()).shippingProvider(order.getShippingProvider())
                .codReconciled(order.getCodReconciled()).note(order.getNote())
                .cancelledReason(order.getCancelledReason())
                .packedBy(order.getPackedBy())
                .packedByName(packedByName)
                .packedAt(order.getPackedAt())
                .createdByName(order.getCreatedBy() != null && !order.getCreatedBy().equals("SYSTEM")
                        ? userRepository.findByUsername(order.getCreatedBy()).map(User::getFullName)
                                .orElse(order.getCreatedBy())
                        : "Hệ thống")
                .createdAt(order.getCreatedAt()).updatedAt(order.getUpdatedAt())
                .items(items).statusHistory(history).build();
    }

    public Map<String, Object> getOrderStats(UUID warehouseId, Order.OrderStatus status, Order.OrderType type, String keyword, String source) {
        long totalCount = 0;
        long pendingCount = 0;
        long paidCount = 0;
        double totalRevenue = 0.0;

        if ("ONLINE".equals(source) || "ALL".equals(source) || source == null) {
            Map<String, Object> oStats = orderRepository.getOrderStats(
                warehouseId, 
                status, 
                type, 
                keyword,
                Order.OrderStatus.PENDING,
                Order.PaymentStatus.PAID,
                Order.OrderStatus.CANCELLED
            );
            if (oStats != null) {
                totalCount += ((Number) oStats.getOrDefault("totalCount", 0)).longValue();
                pendingCount += ((Number) oStats.getOrDefault("pendingCount", 0)).longValue();
                paidCount += ((Number) oStats.getOrDefault("paidCount", 0)).longValue();
                totalRevenue += ((Number) oStats.getOrDefault("totalRevenue", 0)).doubleValue();
            }
        }

        if ("OFFLINE".equals(source) || "ALL".equals(source) || source == null) {
            String invType = null;
            if (status != null) {
                if (status == Order.OrderStatus.DELIVERED) invType = "SALE";
                else if (status == Order.OrderStatus.RETURNED) invType = "RETURN";
                else if (status == Order.OrderStatus.CANCELLED) invType = "VOIDED";
                else {
                    invType = "NONE";
                }
            }
            if (!"NONE".equals(invType)) {
                Map<String, Object> iStats = invoiceRepository.getInvoiceStats(warehouseId, invType, keyword);
                if (iStats != null) {
                    totalCount += ((Number) iStats.getOrDefault("totalCount", 0)).longValue();
                    pendingCount += ((Number) iStats.getOrDefault("pendingCount", 0)).longValue();
                    paidCount += ((Number) iStats.getOrDefault("paidCount", 0)).longValue();
                    totalRevenue += ((Number) iStats.getOrDefault("totalRevenue", 0)).doubleValue();
                }
            }
        }

        return Map.of(
            "totalCount", totalCount,
            "pendingCount", pendingCount,
            "paidCount", paidCount,
            "totalRevenue", totalRevenue
        );
    }
}