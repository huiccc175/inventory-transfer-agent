package com.warehouse.transfer.service;

import com.warehouse.transfer.domain.AgentTask;
import com.warehouse.transfer.domain.TaskStatus;
import com.warehouse.transfer.domain.TaskStepExecution;
import com.warehouse.transfer.parser.InstructionParseException;
import com.warehouse.transfer.parser.InstructionParser;
import com.warehouse.transfer.plan.PlanSnapshots;
import com.warehouse.transfer.plan.TransferPlan;
import com.warehouse.transfer.repo.AgentTaskRepository;
import com.warehouse.transfer.repo.TaskStepExecutionRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 接指令、等人审批、把计划冻成快照。
 * 审批时只复制当时的 plan_json。之后就算草稿被改掉，快照也不动。
 */
@Service
public class TransferTaskService {

    private final AgentTaskRepository taskRepository;
    private final TaskStepExecutionRepository stepRepository;
    private final InstructionParser instructionParser;
    private final PlanSnapshots planSnapshots;
    private final TransferExecutor executor;
    private final Checkpoints checkpoints;

    public TransferTaskService(
            AgentTaskRepository taskRepository,
            TaskStepExecutionRepository stepRepository,
            InstructionParser instructionParser,
            PlanSnapshots planSnapshots,
            TransferExecutor executor,
            Checkpoints checkpoints
    ) {
        this.taskRepository = taskRepository;
        this.stepRepository = stepRepository;
        this.instructionParser = instructionParser;
        this.planSnapshots = planSnapshots;
        this.executor = executor;
        this.checkpoints = checkpoints;
    }

    public AgentTask submitInstruction(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new InstructionParseException("instruction 不能为空");
        }
        String text = instruction.trim();
        long id = checkpoints.commit(() -> {
            AgentTask task = new AgentTask();
            task.setRawInstruction(text);
            task.setStatus(TaskStatus.draft);
            return taskRepository.save(task).getId();
        });
        try {
            TransferPlan plan = instructionParser.parse(text);
            String planJson = planSnapshots.write(plan);
            checkpoints.commit(() -> {
                AgentTask task = load(id);
                task.setPlanJson(planJson);
                task.setStatus(TaskStatus.awaiting_approval);
                taskRepository.save(task);
            });
        } catch (RuntimeException ex) {
            checkpoints.commit(() -> {
                AgentTask task = load(id);
                task.setStatus(TaskStatus.failed);
                task.setErrorMessage(trim(ex.getMessage()));
                taskRepository.save(task);
            });
            throw ex;
        }
        return load(id);
    }

    /**
     * 实验用：把一份手写 JSON 直接存成待审批计划，不经过解析器。
     */
    public AgentTask submitExactPlan(String planJson) {
        String canonical = planSnapshots.normalize(planJson);
        long id = checkpoints.commit(() -> {
            AgentTask task = new AgentTask();
            task.setRawInstruction("experiment-plan");
            task.setPlanJson(canonical);
            task.setStatus(TaskStatus.awaiting_approval);
            return taskRepository.save(task).getId();
        });
        return load(id);
    }

    /**
     * 人点了同意。只在快照还是空的时候写一次，写完就再也不改。
     * 状态改为 executing，但这里还不碰库存；执行交给 TransferExecutor。
     */
    public AgentTask freezeApprovedSnapshot(long id) {
        checkpoints.commit(() -> {
            AgentTask task = load(id);
            if (task.getApprovedSnapshotJson() != null) {
                return;
            }
            if (task.getStatus() != TaskStatus.awaiting_approval) {
                throw new IllegalStateException("只有 awaiting_approval 可以审批，当前是 " + task.getStatus());
            }
            if (task.getPlanJson() == null || task.getPlanJson().isBlank()) {
                throw new IllegalStateException("任务 " + id + " 还没有计划");
            }
            String snapshot = planSnapshots.normalize(task.getPlanJson());
            // 先确认这份快照能被执行器读取。读失败时整个提交回滚，状态仍是 awaiting_approval。
            planSnapshots.read(snapshot);
            task.setApprovedSnapshotJson(snapshot);
            task.setStatus(TaskStatus.executing);
            taskRepository.save(task);
        });
        return load(id);
    }

    public AgentTask approve(long id) {
        freezeApprovedSnapshot(id);
        executor.resume(id);
        return load(id);
    }

    public AgentTask resume(long id) {
        executor.resume(id);
        return load(id);
    }

    public AgentTask get(long id) {
        return load(id);
    }

    public List<TaskStepExecution> steps(long id) {
        load(id);
        return stepRepository.findByTaskIdOrderByIdAsc(id);
    }

    private AgentTask load(long id) {
        return taskRepository.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
    }

    private static String trim(String message) {
        if (message == null) {
            return "未知错误";
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
