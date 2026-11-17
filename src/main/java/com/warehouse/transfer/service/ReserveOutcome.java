package com.warehouse.transfer.service;

/**
 * @param replayed true 表示这次是超时重试，库存没有再次扣减，qty 是第一次预占记下的数量
 */
public record ReserveOutcome(
        String idempotencyKey,
        int qty,
        boolean replayed,
        int onHandAfter,
        int reservedQtyAfter
) {
}
