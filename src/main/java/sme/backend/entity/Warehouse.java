package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.envers.Audited;
import org.hibernate.envers.AuditTable;

import java.util.UUID;

@Entity
@Table(name = "warehouses")
@Audited
@AuditTable("warehouses_audit")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Warehouse extends BaseEntity {

    @Column(unique = true, nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "province_code", nullable = false, length = 20)
    private String provinceCode;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(length = 20)
    private String phone;

    @Column(name = "manager_id")
    private UUID managerId;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "warehouse_type", length = 20)
    @Builder.Default
    private WarehouseType warehouseType = WarehouseType.BRANCH;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "max_daily_orders")
    @Builder.Default
    private Integer maxDailyOrders = 200;

    @Column(name = "is_accepting_orders")
    @Builder.Default
    private Boolean isAcceptingOrders = true;

    public enum WarehouseType {
        MAIN,    
        BRANCH,  
        DROPSHIP 
    }
}