package com.warehouse.transfer.config;

import com.warehouse.transfer.domain.InventoryBalance;
import com.warehouse.transfer.repo.InventoryBalanceRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时补齐演示数据。已经有的库存行不会被改写，否则每次重启都会把预占“还回去”。
 */
@Component
@Order(0)
public class InventorySeeder implements ApplicationRunner {

    public static final String SHANGHAI = "上海仓";
    public static final String GUANGZHOU = "广州仓";
    public static final String SKU = "SKU-8821";
    public static final int SHANGHAI_INITIAL = 100;
    public static final int GUANGZHOU_INITIAL = 10;

    private final InventoryBalanceRepository balanceRepository;

    public InventorySeeder(InventoryBalanceRepository balanceRepository) {
        this.balanceRepository = balanceRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    public void seed() {
        ensure(SHANGHAI, "SH", SKU, SHANGHAI_INITIAL);
        ensure(GUANGZHOU, "GZ", SKU, GUANGZHOU_INITIAL);
    }

    private void ensure(String warehouseName, String warehouseCode, String sku, int onHand) {
        if (balanceRepository.findByWarehouseNameAndSku(warehouseName, sku).isPresent()) {
            return;
        }
        InventoryBalance balance = new InventoryBalance();
        balance.setWarehouseName(warehouseName);
        balance.setWarehouseCode(warehouseCode);
        balance.setSku(sku);
        balance.setOnHand(onHand);
        balance.setReservedQty(0);
        balanceRepository.save(balance);
    }
}
