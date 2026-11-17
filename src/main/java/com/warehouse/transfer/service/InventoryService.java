package com.warehouse.transfer.service;

import com.warehouse.transfer.domain.InventoryBalance;
import com.warehouse.transfer.domain.InventoryReservation;
import com.warehouse.transfer.repo.InventoryBalanceRepository;
import com.warehouse.transfer.repo.InventoryReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 预占库存。幂等键是 taskId + ":" + stepId。
 * 同一次调拨的同一步骤无论重试多少次，只扣减一次；重试返回第一次记下的数量，忽略新的 qty。
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryBalanceRepository balanceRepository;
    private final InventoryReservationRepository reservationRepository;
    private final Checkpoints checkpoints;

    public InventoryService(
            InventoryBalanceRepository balanceRepository,
            InventoryReservationRepository reservationRepository,
            Checkpoints checkpoints
    ) {
        this.balanceRepository = balanceRepository;
        this.reservationRepository = reservationRepository;
        this.checkpoints = checkpoints;
    }

    public static String idempotencyKey(long taskId, String stepId) {
        return taskId + ":" + stepId;
    }

    public List<InventoryBalance> list() {
        return balanceRepository.findAll(Sort.by("warehouseName"));
    }

    public ReserveOutcome reserve(long taskId, String stepId, String warehouseName, String sku, int qty) {
        String key = idempotencyKey(taskId, stepId);
        ReserveOutcome replay = replayIfPresent(key, warehouseName, sku);
        if (replay != null) {
            return replay;
        }
        try {
            return checkpoints.commit(() -> applyNewReservation(key, taskId, stepId, warehouseName, sku, qty));
        } catch (DataIntegrityViolationException ex) {
            // 两个重试同时插入时，输掉的那个事务会回滚扣减。再读一次胜出的记录即可。
            ReserveOutcome winner = replayIfPresent(key, warehouseName, sku);
            if (winner == null) {
                throw ex;
            }
            log.info("预占并发冲突后改为重放幂等键 {}", key);
            return winner;
        }
    }

    private ReserveOutcome replayIfPresent(String key, String warehouseName, String sku) {
        return reservationRepository.findByIdempotencyKey(key)
                .map(existing -> {
                    InventoryBalance balance = loadBalance(warehouseName, sku);
                    log.info("预占重试命中幂等键 {}，不再扣减库存，沿用数量 {}", key, existing.getQty());
                    return new ReserveOutcome(
                            key,
                            existing.getQty(),
                            true,
                            balance.getOnHand(),
                            balance.getReservedQty()
                    );
                })
                .orElse(null);
    }

    private ReserveOutcome applyNewReservation(
            String key,
            long taskId,
            String stepId,
            String warehouseName,
            String sku,
            int qty
    ) {
        // 事务内再查一次，避免读到旧结果后又插入。
        var existing = reservationRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            InventoryBalance balance = loadBalance(warehouseName, sku);
            return new ReserveOutcome(key, existing.get().getQty(), true, balance.getOnHand(), balance.getReservedQty());
        }
        if (qty <= 0) {
            throw new IllegalArgumentException("预占数量必须大于 0");
        }
        InventoryBalance balance = loadBalance(warehouseName, sku);
        if (balance.getOnHand() < qty) {
            throw new IllegalStateException(
                    warehouseName + " 的 " + sku + " 可用库存 " + balance.getOnHand() + "，不足 " + qty
            );
        }
        int onHandBefore = balance.getOnHand();
        balance.setOnHand(onHandBefore - qty);
        balance.setReservedQty(balance.getReservedQty() + qty);
        balanceRepository.save(balance);

        InventoryReservation reservation = new InventoryReservation();
        reservation.setIdempotencyKey(key);
        reservation.setTaskId(taskId);
        reservation.setStepId(stepId);
        reservation.setWarehouseName(warehouseName);
        reservation.setSku(sku);
        reservation.setQty(qty);
        reservationRepository.saveAndFlush(reservation);

        log.info(
                "预占成功 key={} {} {} qty={} onHand {} -> {}",
                key, warehouseName, sku, qty, onHandBefore, balance.getOnHand()
        );
        return new ReserveOutcome(key, qty, false, balance.getOnHand(), balance.getReservedQty());
    }

    private InventoryBalance loadBalance(String warehouseName, String sku) {
        return balanceRepository.findByWarehouseNameAndSku(warehouseName, sku)
                .orElseThrow(() -> new IllegalStateException("没有库存记录: " + warehouseName + " / " + sku));
    }
}
