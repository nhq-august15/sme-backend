package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.CloseShiftRequest;
import sme.backend.dto.request.OpenShiftRequest;
import sme.backend.dto.response.ShiftResponse;
import sme.backend.entity.Shift;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.InvoiceRepository;
import sme.backend.repository.ShiftRepository;
import sme.backend.repository.UserRepository;
import sme.backend.repository.WarehouseRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShiftService {

    private final ShiftRepository shiftRepository;
    private final InvoiceRepository invoiceRepository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final WarehouseRepository warehouseRepository;

    // ─────────────────────────────────────────────────────────
    // MỞ CA (POS-01)
    // ─────────────────────────────────────────────────────────
    @Transactional
    public ShiftResponse openShift(UUID cashierId, UUID warehouseId, OpenShiftRequest req) {
        // Kiểm tra cashier đã có ca mở chưa
        if (shiftRepository.existsByCashierIdAndStatus(cashierId, Shift.ShiftStatus.OPEN)) {
            throw new BusinessException("SHIFT_ALREADY_OPEN",
                    "Thu ngân này đang có ca làm việc đang mở. Vui lòng đóng ca cũ trước.");
        }

        Shift shift = Shift.builder()
                .warehouseId(warehouseId)
                .cashierId(cashierId)
                .startingCash(req.getStartingCash())
                .status(Shift.ShiftStatus.OPEN)
                .build();

        shift = shiftRepository.save(shift);
        log.info("Shift opened: {} by cashier: {}", shift.getId(), cashierId);
        return mapToResponse(shift);
    }

    // ─────────────────────────────────────────────────────────
    // ĐÓNG CA MÙ (POS-B, Blind Close) (POS-09)
    // Cashier KHÔNG biết số tiền lý thuyết khi đóng ca
    // ─────────────────────────────────────────────────────────
    @Transactional
    public ShiftResponse closeShift(sme.backend.security.UserPrincipal principal, CloseShiftRequest req) {
        Shift shift;
        if (req.getShiftId() != null && principal.getRole() == sme.backend.entity.User.UserRole.ROLE_ADMIN) {
            shift = shiftRepository.findById(req.getShiftId())
                    .orElseThrow(() -> new BusinessException("SHIFT_NOT_FOUND", "Không tìm thấy ca làm việc này"));
            if (shift.getStatus() != Shift.ShiftStatus.OPEN) {
                throw new BusinessException("SHIFT_NOT_OPEN", "Ca làm việc không ở trạng thái OPEN");
            }
        } else {
            shift = shiftRepository
                    .findByCashierIdAndStatus(principal.getId(), Shift.ShiftStatus.OPEN)
                    .orElseThrow(() -> new BusinessException("NO_OPEN_SHIFT",
                            "Không tìm thấy ca làm việc đang mở cho thu ngân này"));
        }

        // Tính tiền lý thuyết: Đầu ca + Tiền mặt thu vào - Tiền mặt chi ra
        BigDecimal cashIn  = shiftRepository.sumCashInByShift(shift.getId());
        BigDecimal cashOut = shiftRepository.sumCashOutByShift(shift.getId());
        BigDecimal theoretical = shift.getStartingCash().add(cashIn).subtract(cashOut);

        // Gọi domain method (có validate lý do nếu lệch)
        shift.closeShift(req.getReportedCash(), theoretical, req.getDiscrepancyReason());
        shift = shiftRepository.save(shift);

        // Notify Manager duyệt ca
        notificationService.notifyShiftClosed(shift);
        log.info("Shift closed: {} | theoretical={} | reported={} | discrepancy={}",
                shift.getId(), theoretical, req.getReportedCash(),
                shift.getDiscrepancyAmount());
        return mapToResponse(shift);
    }

    // ─────────────────────────────────────────────────────────
    // DUYỆT CHỐT CA (Manager)
    // ─────────────────────────────────────────────────────────
    @Transactional
    public ShiftResponse approveShift(UUID shiftId, UUID managerId) {
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift", shiftId));

        shift.approve(managerId);
        shift = shiftRepository.save(shift);
        log.info("Shift approved: {} by manager: {}", shiftId, managerId);
        return mapToResponse(shift);
    }

    // ─────────────────────────────────────────────────────────
    // QUERIES
    // ─────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public Shift getOpenShiftByCashier(UUID cashierId) {
        return shiftRepository.findByCashierIdAndStatus(cashierId, Shift.ShiftStatus.OPEN)
                .orElseThrow(() -> new BusinessException("NO_OPEN_SHIFT",
                        "Thu ngân chưa mở ca. Vui lòng mở ca trước khi bán hàng."));
    }

    @Transactional(readOnly = true)
    public List<ShiftResponse> getPendingShifts(UUID warehouseId) {
        List<Shift> shifts = shiftRepository.findPendingShiftsExcludeAdminAndCustomer(warehouseId, Shift.ShiftStatus.CLOSED);
        return shifts.stream().map(this::mapToResponse).toList();
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<ShiftResponse> searchShifts(UUID warehouseId, org.springframework.data.domain.Pageable pageable) {
        return shiftRepository.searchShiftsExcludeAdminAndCustomer(warehouseId, pageable).map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public ShiftResponse getById(UUID id) {
        Shift shift = shiftRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Shift", id));
        return mapToResponse(shift);
    }

    // ─────────────────────────────────────────────────────────
    // MAPPER
    // ─────────────────────────────────────────────────────────
    public ShiftResponse mapToResponse(Shift shift) {
        BigDecimal revenue = BigDecimal.ZERO;
        if (shift.getId() != null) {
            try { revenue = invoiceRepository.sumRevenueByShift(shift.getId()); }
            catch (Exception ignored) {}
        }
        if (revenue == null) revenue = BigDecimal.ZERO;
        // Lookup names
        String cashierName = null;
        String warehouseName = null;
        try {
            cashierName = userRepository.findById(shift.getCashierId())
                    .map(u -> u.getFullName() != null ? u.getFullName() : u.getUsername())
                    .orElse(null);
        } catch (Exception ignored) {}
        try {
            warehouseName = warehouseRepository.findById(shift.getWarehouseId())
                    .map(w -> w.getName())
                    .orElse(null);
        } catch (Exception ignored) {}

        return ShiftResponse.builder()
                .id(shift.getId())
                .warehouseId(shift.getWarehouseId())
                .warehouseName(warehouseName)
                .cashierId(shift.getCashierId())
                .cashierName(cashierName)
                .startingCash(shift.getStartingCash())
                .reportedCash(shift.getReportedCash())
                .theoreticalCash(shift.getTheoreticalCash())
                .discrepancyAmount(shift.getDiscrepancyAmount())
                .discrepancyReason(shift.getDiscrepancyReason())
                .status(shift.getStatus().name())
                .openedAt(shift.getOpenedAt())
                .closedAt(shift.getClosedAt())
                .approvedAt(shift.getApprovedAt())
                .totalRevenue(revenue)
                .build();
    }
}
