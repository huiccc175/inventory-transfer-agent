package com.warehouse.transfer.service;

import com.warehouse.transfer.domain.AgentTask;
import com.warehouse.transfer.domain.TaskStatus;
import com.warehouse.transfer.repo.AgentTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 进程启动时的恢复入口。测试里模拟“重启”时调用的也是这个方法。
 * 只捞 executing：还在等人审批的任务不能被自动执行。
 */
@Service
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final AgentTaskRepository taskRepository;
    private final TransferExecutor executor;

    public RecoveryService(AgentTaskRepository taskRepository, TransferExecutor executor) {
        this.taskRepository = taskRepository;
        this.executor = executor;
    }

    public int resumeInterrupted() {
        List<AgentTask> interrupted = taskRepository.findByStatus(TaskStatus.executing);
        log.info("发现 {} 个执行中的任务，按已落库的快照继续", interrupted.size());
        for (AgentTask task : interrupted) {
            executor.resume(task.getId());
        }
        return interrupted.size();
    }
}
