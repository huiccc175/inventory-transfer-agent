package com.warehouse.transfer.repo;

import com.warehouse.transfer.domain.InventoryBalance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InventoryBalanceRepository extends JpaRepository<InventoryBalance, Long> {

    Optional<InventoryBalance> findByWarehouseNameAndSku(String warehouseName, String sku);
}
