package com.warehouse.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.transfer.config.InventorySeeder;
import com.warehouse.transfer.domain.AgentTask;
import com.warehouse.transfer.domain.InventoryBalance;
import com.warehouse.transfer.domain.InventoryReservation;
import com.warehouse.transfer.domain.TaskStatus;
import com.warehouse.transfer.domain.TaskStepExecution;
import com.warehouse.transfer.plan.PlanSnapshots;
import com.warehouse.transfer.repo.AgentTaskRepository;
import com.warehouse.transfer.repo.InventoryBalanceRepository;
import com.warehouse.transfer.repo.InventoryReservationRepository;
import com.warehouse.transfer.repo.TaskStepExecutionRepository;
import com.warehouse.transfer.service.CrashGate;
import com.warehouse.transfer.service.InventoryService;
import com.warehouse.transfer.service.PlanSnapshotMismatchException;
import com.warehouse.transfer.service.RecoveryService;
import com.warehouse.transfer.service.ReserveOutcome;
import com.warehouse.transfer.service.SimulatedCrashException;
import com.warehouse.transfer.service.TransferExecutor;
import com.warehouse.transfer.service.TransferTaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验收实验。两份手写计划在 src/test/resources/experiment/。
 * 计划 A、B 都是「SKU-8821 从上海仓到广州仓，数量 30」。
 * A 的 qty 是数字 30；B 把数量写成字符串 "30件"，并且字段顺序、步骤顺序都不同。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:transfer-experiment;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.recovery.enabled=false",
        "app.crash.after-step="
})
class TransferRecoveryExperimentTest {

    @Autowired TransferTaskService taskService;
    @Autowired TransferExecutor executor;
    @Autowired RecoveryService recoveryService;
    @Autowired InventoryService inventoryService;
    @Autowired InventorySeeder seeder;
    @Autowired CrashGate crashGate;
    @Autowired PlanSnapshots planSnapshots;
    @Autowired ObjectMapper objectMapper;
    @Autowired AgentTaskRepository taskRepository;
    @Autowired TaskStepExecutionRepository stepRepository;
    @Autowired InventoryBalanceRepository balanceRepository;
    @Autowired InventoryReservationRepository reservationRepository;

    private String planA;
    private String planB;

    @BeforeEach
    void resetDatabase() throws IOException {
        crashGate.disarm();
        reservationRepository.deleteAll();
        stepRepository.deleteAll();
        taskRepository.deleteAll();
        balanceRepository.deleteAll();
        seeder.seed();
        planA = resource("/experiment/plan-a.json");
        planB = resource("/experiment/plan-b.json");
    }

    @AfterEach
    void disarmCrashGate() {
        crashGate.disarm();
    }

    @Test
    @DisplayName("实验1：崩溃后续跑只执行计划 A，库存只预占一次")
    void crashThenRestartResumesOnlyPlanAAndDeductsOnce() throws IOException {
        AgentTask taskA = taskService.submitExactPlan(planA);
        AgentTask taskB = taskService.submitExactPlan(planB);
        taskService.freezeApprovedSnapshot(taskA.getId());

        crashGate.armAfterStep("reserve");
        assertThatThrownBy(() -> executor.resume(taskA.getId()))
                .isInstanceOf(SimulatedCrashException.class);

        AgentTask crashed = taskService.get(taskA.getId());
        assertThat(crashed.getStatus()).isEqualTo(TaskStatus.executing);
        List<TaskStepExecution> stepsAfterCrash = stepRepository.findByTaskIdOrderByIdAsc(taskA.getId());
        assertThat(stepsAfterCrash).extracting(TaskStepExecution::getStepId).containsExactly("validate", "reserve");
        assertThat(balance(InventorySeeder.SHANGHAI).getOnHand()).isEqualTo(70);
        assertThat(balance(InventorySeeder.SHANGHAI).getReservedQty()).isEqualTo(30);
        assertThat(reservations()).hasSize(1);
        InventoryReservation reservation = reservations().get(0);
        assertThat(reservation.getIdempotencyKey()).isEqualTo(taskA.getId() + ":reserve");
        assertThat(reservation.getQty()).isEqualTo(30);
        Long reserveRowId = stepsAfterCrash.get(1).getId();

        // 预占接口超时后的重试：哪怕这次报的数量是 999，也必须沿用第一次的 30，不能再扣。
        ReserveOutcome retry = inventoryService.reserve(
                taskA.getId(), "reserve", InventorySeeder.SHANGHAI, InventorySeeder.SKU, 999);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.qty()).isEqualTo(30);
        assertThat(retry.onHandAfter()).isEqualTo(70);
        assertThat(balance(InventorySeeder.SHANGHAI).getOnHand()).isEqualTo(70);
        assertThat(balance(InventorySeeder.SHANGHAI).getReservedQty()).isEqualTo(30);
        assertThat(reservations()).hasSize(1);

        // 模拟进程重启后内存丢失，同时有人把草稿改成了计划 B，原始指令也作废了。
        // 恢复入口不允许再解析这句指令，也不许改用 plan_json。
        AgentTask tampered = taskRepository.findById(taskA.getId()).orElseThrow();
        tampered.setPlanJson(planB);
        tampered.setRawInstruction("这不是一条调拨指令");
        taskRepository.save(tampered);
        crashGate.disarm();

        assertThat(recoveryService.resumeInterrupted()).isEqualTo(1);

        AgentTask resumed = taskService.get(taskA.getId());
        assertThat(resumed.getStatus()).isEqualTo(TaskStatus.done);
        assertThat(planSnapshots.same(planA, resumed.getApprovedSnapshotJson())).isTrue();
        JsonNode snapshot = objectMapper.readTree(resumed.getApprovedSnapshotJson());
        assertThat(snapshot.get("qty").isNumber()).isTrue();
        assertThat(snapshot.get("qty").intValue()).isEqualTo(30);
        assertThat(snapshot.get("steps").get(0).get("stepId").asText()).isEqualTo("validate");

        List<TaskStepExecution> stepsAfterResume = stepRepository.findByTaskIdOrderByIdAsc(taskA.getId());
        assertThat(stepsAfterResume).extracting(TaskStepExecution::getStepId)
                .containsExactly("validate", "reserve", "record");
        assertThat(stepsAfterResume.get(1).getId()).isEqualTo(reserveRowId);
        assertThat(objectMapper.readTree(stepsAfterResume.get(1).getResultJson()).get("replayed").asBoolean())
                .isFalse();

        assertThat(balance(InventorySeeder.SHANGHAI).getOnHand()).isEqualTo(70);
        assertThat(balance(InventorySeeder.SHANGHAI).getReservedQty()).isEqualTo(30);
        assertThat(balance(InventorySeeder.GUANGZHOU).getOnHand()).isEqualTo(InventorySeeder.GUANGZHOU_INITIAL);
        assertThat(balance(InventorySeeder.GUANGZHOU).getReservedQty()).isEqualTo(0);
        assertThat(reservations()).hasSize(1);

        AgentTask untouched = taskService.get(taskB.getId());
        assertThat(untouched.getStatus()).isEqualTo(TaskStatus.awaiting_approval);
        assertThat(untouched.getApprovedSnapshotJson()).isNull();
        assertThat(stepRepository.findByTaskIdOrderByIdAsc(taskB.getId())).isEmpty();
    }

    @Test
    @DisplayName("实验2：审批 A 之后，执行器拒绝计划 B，也拒绝只调换了字段顺序的计划")
    void executorRejectsPlanBAfterSnapshotFrozen() throws IOException {
        JsonNode a = objectMapper.readTree(planA);
        JsonNode b = objectMapper.readTree(planB);
        assertThat(a.get("sku").asText()).isEqualTo(b.get("sku").asText());
        assertThat(a.get("fromWarehouse").asText()).isEqualTo(b.get("fromWarehouse").asText());
        assertThat(a.get("toWarehouse").asText()).isEqualTo(b.get("toWarehouse").asText());
        assertThat(a.get("qty")).isNotEqualTo(b.get("qty"));
        assertThat(planSnapshots.same(planA, planB)).isFalse();

        // 业务字段和步骤都与 A 相同，只是换了字段顺序。这也不是那份冻结快照。
        String reordered = """
                {
                  "qty": 30,
                  "toWarehouse": "广州仓",
                  "fromWarehouse": "上海仓",
                  "sku": "SKU-8821",
                  "steps": [
                    {"type": "VALIDATE_TRANSFER", "stepId": "validate"},
                    {"type": "RESERVE_INVENTORY", "stepId": "reserve"},
                    {"type": "RECORD_TRANSFER", "stepId": "record"}
                  ]
                }
                """;
        assertThat(planSnapshots.same(planA, reordered)).isFalse();

        AgentTask task = taskService.submitExactPlan(planA);
        taskService.freezeApprovedSnapshot(task.getId());
        assertThat(balance(InventorySeeder.SHANGHAI).getOnHand()).isEqualTo(InventorySeeder.SHANGHAI_INITIAL);

        assertThatThrownBy(() -> executor.executeCandidate(task.getId(), reordered))
                .isInstanceOf(PlanSnapshotMismatchException.class);
        assertThatThrownBy(() -> executor.executeCandidate(task.getId(), planB))
                .isInstanceOf(PlanSnapshotMismatchException.class);

        AgentTask afterReject = taskService.get(task.getId());
        assertThat(afterReject.getStatus()).isEqualTo(TaskStatus.executing);
        assertThat(planSnapshots.same(planA, afterReject.getApprovedSnapshotJson())).isTrue();
        assertThat(stepRepository.findByTaskIdOrderByIdAsc(task.getId())).isEmpty();
        assertThat(reservations()).isEmpty();
        assertThat(balance(InventorySeeder.SHANGHAI).getOnHand()).isEqualTo(InventorySeeder.SHANGHAI_INITIAL);

        // 草稿被改成 B 之后再次审批，也不能覆盖已经冻结的快照。
        AgentTask editable = taskRepository.findById(task.getId()).orElseThrow();
        editable.setPlanJson(planSnapshots.normalize(planB));
        taskRepository.save(editable);
        taskService.freezeApprovedSnapshot(task.getId());
        assertThat(planSnapshots.same(planA, taskService.get(task.getId()).getApprovedSnapshotJson())).isTrue();

        // B 自己也不能被审批成可执行快照：数量不是整数，冻结会回滚。
        AgentTask onlyB = taskService.submitExactPlan(planB);
        assertThatThrownBy(() -> taskService.freezeApprovedSnapshot(onlyB.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(taskService.get(onlyB.getId()).getStatus()).isEqualTo(TaskStatus.awaiting_approval);
        assertThat(taskService.get(onlyB.getId()).getApprovedSnapshotJson()).isNull();

        // 被拒绝之后，原来的快照 A 仍然可以单独跑完，而且只扣一次。
        executor.resume(task.getId());
        assertThat(taskService.get(task.getId()).getStatus()).isEqualTo(TaskStatus.done);
        assertThat(balance(InventorySeeder.SHANGHAI).getOnHand()).isEqualTo(70);
        assertThat(reservations()).hasSize(1);
        assertThat(reservations().get(0).getTaskId()).isEqualTo(task.getId());
        assertThat(reservations().get(0).getQty()).isEqualTo(30);
    }

    private InventoryBalance balance(String warehouseName) {
        return balanceRepository.findByWarehouseNameAndSku(warehouseName, InventorySeeder.SKU).orElseThrow();
    }

    private List<InventoryReservation> reservations() {
        return reservationRepository.findAll();
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = TransferRecoveryExperimentTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("找不到夹具 " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
