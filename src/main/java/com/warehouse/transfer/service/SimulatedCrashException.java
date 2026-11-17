package com.warehouse.transfer.service;

public class SimulatedCrashException extends RuntimeException {

    private final String stepId;

    public SimulatedCrashException(String stepId) {
        super("模拟崩溃：步骤 " + stepId + " 的结果已经落库，任务还没有完成");
        this.stepId = stepId;
    }

    public String getStepId() {
        return stepId;
    }
}
