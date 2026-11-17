package com.warehouse.transfer.domain;

/**
 * 写入 agent_task.status 的取值，接口原样返回这些名字。
 * draft 只存在于“指令已收下、计划还没解析完”的瞬间；
 * 真正停下来等人的状态是 awaiting_approval。
 */
public enum TaskStatus {
    draft,
    awaiting_approval,
    executing,
    done,
    failed
}
