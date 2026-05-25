package sme.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.UpdateWarehouseRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.WarehouseResponse;
import sme.backend.entity.Warehouse;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.WarehouseRepository;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

        private final WarehouseRepository warehouseRepository;

        /** GET /warehouses — lấy toàn bộ kho (kể cả đã khóa) */
        @GetMapping
        @PreAuthorize("hasAnyRole('MANAGER','ADMIN','CASHIER')")
        public ResponseEntity<ApiResponse<List<WarehouseResponse>>> getAll() {
                List<WarehouseResponse> result = warehouseRepository.findAll(Sort.by("name"))
                                .stream().map(WarehouseResponse::from).collect(Collectors.toList());
                return ResponseEntity.ok(ApiResponse.ok(result));
        }

        /** GET /warehouses/active — dropdown chọn chi nhánh cho Admin */
        @GetMapping("/active")
        @PreAuthorize("hasAnyRole('MANAGER','ADMIN','CASHIER')")
        public ResponseEntity<ApiResponse<List<WarehouseResponse>>> getActiveWarehouses() {
                List<WarehouseResponse> result = warehouseRepository.findByIsActiveTrueOrderByName()
                                .stream().map(WarehouseResponse::from).collect(Collectors.toList());
                return ResponseEntity.ok(ApiResponse.ok(result));
        }

        /** GET /warehouses/{id} */
        @GetMapping("/{id}")
        @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
        public ResponseEntity<ApiResponse<WarehouseResponse>> getOne(@PathVariable UUID id) {
                Warehouse w = warehouseRepository.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Warehouse", id));
                return ResponseEntity.ok(ApiResponse.ok(WarehouseResponse.from(w)));
        }

        /** POST /warehouses — tạo kho mới */
        @PostMapping
        @PreAuthorize("hasRole('ADMIN')")
        public ResponseEntity<ApiResponse<WarehouseResponse>> create(
                        @RequestBody Map<String, String> body) {
                String code = body.get("code");
                if (code == null || code.trim().isEmpty()) {
                        long count = warehouseRepository.count() + 1;
                        code = "CN" + String.format("%03d", count);
                        while (warehouseRepository.existsByCode(code)) {
                                count++;
                                code = "CN" + String.format("%03d", count);
                        }
                } else {
                        if (warehouseRepository.existsByCode(code)) {
                                throw new BusinessException("DUPLICATE_CODE",
                                                "Mã chi nhánh '" + code + "' đã tồn tại");
                        }
                }
                String typeStr = body.get("warehouseType");
                Warehouse.WarehouseType type = Warehouse.WarehouseType.BRANCH;
                if (typeStr != null) {
                        try {
                                type = Warehouse.WarehouseType.valueOf(typeStr);
                        } catch (Exception ignored) {}
                }
                Warehouse warehouse = Warehouse.builder()
                                .code(code)
                                .name(body.get("name"))
                                .provinceCode(body.getOrDefault("provinceCode", "00"))
                                .address(body.get("address"))
                                .phone(body.get("phone"))
                                .warehouseType(type)
                                .isActive(true)
                                .build();
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(ApiResponse.created(WarehouseResponse.from(warehouseRepository.save(warehouse))));
        }

        /** PUT /warehouses/{id} — cập nhật kho */
        @PutMapping("/{id}")
        @PreAuthorize("hasRole('ADMIN')")
        public ResponseEntity<ApiResponse<WarehouseResponse>> update(
                        @PathVariable UUID id,
                        @RequestBody UpdateWarehouseRequest request) {
                Warehouse w = warehouseRepository.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Warehouse", id));

                if (request.getName() != null)
                        w.setName(request.getName());
                if (request.getProvinceCode() != null)
                        w.setProvinceCode(request.getProvinceCode());
                if (request.getAddress() != null)
                        w.setAddress(request.getAddress());
                if (request.getPhone() != null)
                        w.setPhone(request.getPhone());
                if (request.getWarehouseType() != null)
                        w.setWarehouseType(request.getWarehouseType());

                if (request.getHasManagerId() != null && request.getHasManagerId()) {
                        w.setManagerId(request.getManagerId());
                }

                return ResponseEntity.ok(ApiResponse.ok(WarehouseResponse.from(warehouseRepository.save(w))));
        }

        /** PATCH /warehouses/{id}/deactivate — vô hiệu hóa kho */
        @PatchMapping("/{id}/deactivate")
        @PreAuthorize("hasRole('ADMIN')")
        public ResponseEntity<ApiResponse<WarehouseResponse>> deactivate(@PathVariable UUID id) {
                Warehouse w = warehouseRepository.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Warehouse", id));
                w.setIsActive(false);
                return ResponseEntity.ok(ApiResponse.ok(WarehouseResponse.from(warehouseRepository.save(w))));
        }

        /** PATCH /warehouses/{id}/activate — kích hoạt lại kho */
        @PatchMapping("/{id}/activate")
        @PreAuthorize("hasRole('ADMIN')")
        public ResponseEntity<ApiResponse<WarehouseResponse>> activate(@PathVariable UUID id) {
                Warehouse w = warehouseRepository.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Warehouse", id));
                w.setIsActive(true);
                return ResponseEntity.ok(ApiResponse.ok(WarehouseResponse.from(warehouseRepository.save(w))));
        }
}