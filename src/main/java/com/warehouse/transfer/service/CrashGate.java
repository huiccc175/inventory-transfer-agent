package com.warehouse.transfer.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 测试和手动演示用的崩溃点。生产配置保持关闭（app.crash.after-step 为空）。
 * 它在“步骤结果已经提交”之后抛异常，用来代替真的杀掉进程：
 * 数据库里的断点还在，内存里的计划丢了，接下来只能靠库里的快照恢复。
 */
@Component
public class CrashGate {

    private volatile String crashAfterStepId;

    public CrashGate(@Value("${app.crash.after-step:}") String configuredStepId) {
        this.crashAfterStepId = blankToNull(configuredStepId);
    }

    public void armAfterStep(String stepId) {
        this.crashAfterStepId = stepId;
    }

    public void disarm() {
        this.crashAfterStepId = null;
    }

    public void afterStepSaved(String stepId) {
        if (stepId != null && stepId.equals(crashAfterStepId)) {
            throw new SimulatedCrashException(stepId);
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
