package com.warehouse.transfer.service;

/**
 * 后来的模型输出和已审批快照不是同一份 JSON。执行器拒绝运行它，避免按另一份计划再调一次。
 */
public class PlanSnapshotMismatchException extends RuntimeException {

    private final long taskId;

    public PlanSnapshotMismatchException(long taskId) {
        super("任务 " + taskId + " 的候选计划与已冻结的审批快照不一致，执行器拒绝运行");
        this.taskId = taskId;
    }

    public long getTaskId() {
        return taskId;
    }
}
