package com.warehouse.transfer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.transfer.domain.AgentTask;
import com.warehouse.transfer.domain.InventoryBalance;
import com.warehouse.transfer.domain.StepStatus;
import com.warehouse.transfer.domain.TaskStatus;
import com.warehouse.transfer.domain.TaskStepExecution;
import com.warehouse.transfer.plan.PlanSnapshots;
import com.warehouse.transfer.plan.PlanStep;
import com.warehouse.transfer.plan.StepTypes;
import com.warehouse.transfer.plan.TransferPlan;
import com.warehouse.transfer.repo.AgentTaskRepository;
import com.warehouse.transfer.repo.InventoryBalanceRepository;
import com.warehouse.transfer.repo.TaskStepExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行器只跑数据库里的 approved_snapshot_json。
 * resume 没有“计划”参数，也不会调用解析器：崩溃之后内存里的计划没了，也不能再向模型要一版。
 * 如果调用方拿着另一份计划进来，先走 executeCandidate，对不上快照就直接拒绝。
 */
@Service
public class TransferExecutor {

    private static final Logger log = LoggerFactory.getLogger(TransferExecutor.class);

    private final AgentTaskRepository taskRepository;
    private final TaskStepExecutionRepository stepRepository;
    private final InventoryBalanceRepository balanceRepository;
    private final InventoryService inventoryService;
    private final PlanSnapshots planSnapshots;
    private final Checkpoints checkpoints;
    private final CrashGate crashGate;
    private final ObjectMapper objectMapper;

    public TransferExecutor(
            AgentTaskRepository taskRepository,
            TaskStepExecutionRepository stepRepository,
            InventoryBalanceRepository balanceRepository,
            InventoryService inventoryService,
            PlanSnapshots planSnapshots,
            Checkpoints checkpoints,
            CrashGate crashGate,
            ObjectMapper objectMapper
    ) {
        this.taskRepository = taskRepository;
        this.stepRepository = stepRepository;
        this.balanceRepository = balanceRepository;
        this.inventoryService = inventoryService;
        this.planSnapshots = planSnapshots;
        this.checkpoints = checkpoints;
        this.crashGate = crashGate;
        this.objectMapper = objectMapper;
    }

    /**
     * 模型（或测试）稍后又吐出一份计划时走这里。
     * 只有和冻结快照结构完全一致才继续；字段措辞或步骤顺序不同都会被拒绝，库存不会动。
     */
    public void executeCandidate(long taskId, String candidatePlanJson) {
        AgentTask task = requireTask(taskId);
        if (task.getApprovedSnapshotJson() == null) {
            throw new IllegalStateException("任务 " + taskId + " 还没有可执行的审批快照");
        }
        if (!planSnapshots.same(task.getApprovedSnapshotJson(), candidatePlanJson)) {
            log.warn("拒绝执行任务 {}：候选计划与已冻结的审批快照不一致", taskId);
            throw new PlanSnapshotMismatchException(taskId);
        }
        resume(taskId);
    }

    /**
     * 从库里的快照和步骤结果继续。已成功的步骤直接跳过，不会重做预占。
     */
    public void resume(long taskId) {
        AgentTask task = requireTask(taskId);
        if (task.getStatus() == TaskStatus.done) {
            log.info("任务 {} 已完成，跳过", taskId);
            return;
        }
        if (task.getStatus() == TaskStatus.failed) {
            throw new IllegalStateException("任务 " + taskId + " 已失败，不会自动重跑");
        }
        if (task.getApprovedSnapshotJson() == null) {
            throw new IllegalStateException("任务 " + taskId + " 没有审批快照，拒绝执行");
        }

        // 每次都重新读库，不用调用方手里的对象，避免拿到审批前的旧计划。
        TransferPlan plan = planSnapshots.read(reload(taskId).getApprovedSnapshotJson());
        log.info(
                "按冻结快照继续任务 {}：{} 从 {} 到 {}，数量 {}",
                taskId, plan.sku(), plan.fromWarehouse(), plan.toWarehouse(), plan.qty()
        );

        try {
            for (PlanStep step : plan.steps()) {
                if (alreadySucceeded(taskId, step.stepId())) {
                    log.info("跳过已完成步骤 {}:{}", taskId, step.stepId());
                    continue;
                }
                String resultJson = runStep(taskId, plan, step);
                saveSucceeded(taskId, step, resultJson);
                // 断点在提交之后。异常抛出前，这一步的结果已经在 task_step_execution 里。
                crashGate.afterStepSaved(step.stepId());
            }
            markDone(taskId);
        } catch (SimulatedCrashException ex) {
            log.warn("任务 {} 在步骤 {} 落库后中断，状态保持 executing", taskId, ex.getStepId());
            throw ex;
        } catch (RuntimeException ex) {
            markFailed(taskId, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            throw ex;
        }
    }

    private String runStep(long taskId, TransferPlan plan, PlanStep step) {
        log.info("执行任务 {} 步骤 {} ({})", taskId, step.stepId(), step.type());
        return switch (step.type()) {
            case StepTypes.VALIDATE_TRANSFER -> validate(plan);
            case StepTypes.RESERVE_INVENTORY -> reserve(taskId, plan, step);
            case StepTypes.RECORD_TRANSFER -> record(plan);
            default -> throw new IllegalStateException("未知步骤类型: " + step.type());
        };
    }

    private String validate(TransferPlan plan) {
        InventoryBalance balance = balanceRepository
                .findByWarehouseNameAndSku(plan.fromWarehouse(), plan.sku())
                .orElseThrow(() -> new IllegalStateException(
                        "调出仓没有这种货: " + plan.fromWarehouse() + " / " + plan.sku()
                ));
        balanceRepository.findByWarehouseNameAndSku(plan.toWarehouse(), plan.sku())
                .orElseThrow(() -> new IllegalStateException("调入仓不存在: " + plan.toWarehouse()));
        if (balance.getOnHand() < plan.qty()) {
            throw new IllegalStateException(
                    plan.fromWarehouse() + " 可用 " + balance.getOnHand() + "，不够调出 " + plan.qty()
            );
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("onHand", balance.getOnHand());
        result.put("reservedQty", balance.getReservedQty());
        return toJson(result);
    }

    private String reserve(long taskId, TransferPlan plan, PlanStep step) {
        ReserveOutcome outcome = inventoryService.reserve(
                taskId,
                step.stepId(),
                plan.fromWarehouse(),
                plan.sku(),
                plan.qty()
        );
        return toJson(outcome);
    }

    private String record(TransferPlan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("sku", plan.sku());
        result.put("fromWarehouse", plan.fromWarehouse());
        result.put("toWarehouse", plan.toWarehouse());
        result.put("qty", plan.qty());
        result.put("note", "预占已按审批快照完成，本步骤不再扣库存");
        return toJson(result);
    }

    private boolean alreadySucceeded(long taskId, String stepId) {
        return stepRepository.findByTaskIdAndStepId(taskId, stepId)
                .filter(step -> step.getStatus() == StepStatus.succeeded)
                .isPresent();
    }

    private void saveSucceeded(long taskId, PlanStep step, String resultJson) {
        checkpoints.commit(() -> {
            TaskStepExecution execution = new TaskStepExecution();
            execution.setTaskId(taskId);
            execution.setStepId(step.stepId());
            execution.setStepType(step.type());
            execution.setStatus(StepStatus.succeeded);
            execution.setResultJson(resultJson);
            execution.setSavedAt(Instant.now());
            stepRepository.save(execution);
        });
    }

    private void markDone(long taskId) {
        checkpoints.commit(() -> {
            AgentTask task = reload(taskId);
            task.setStatus(TaskStatus.done);
            task.setErrorMessage(null);
            taskRepository.save(task);
        });
    }

    private void markFailed(long taskId, String message) {
        String stored = message.length() > 1000 ? message.substring(0, 1000) : message;
        checkpoints.commit(() -> {
            AgentTask task = reload(taskId);
            task.setStatus(TaskStatus.failed);
            task.setErrorMessage(stored);
            taskRepository.save(task);
        });
    }

    private AgentTask requireTask(long taskId) {
        return taskRepository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    private AgentTask reload(long taskId) {
        return requireTask(taskId);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("步骤结果无法序列化", ex);
        }
    }
}
