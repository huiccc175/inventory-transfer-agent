package com.warehouse.transfer.plan;

import java.util.List;

public record TransferPlan(
        String sku,
        String fromWarehouse,
        String toWarehouse,
        int qty,
        List<PlanStep> steps
) {
}
