package com.warehouse.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "agent_task")
public class AgentTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 1000)
    private String rawInstruction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskStatus status;

    /**
     * 解析器（或实验夹具）写出的草稿计划。审批之后执行器不再读它。
     */
    @Column(columnDefinition = "TEXT")
    private String planJson;

    /**
     * 人工审批时复制出来的计划快照。一旦写入就不再改。
     * 执行、崩溃恢复都只认这一份。
     */
    @Column(columnDefinition = "TEXT")
    private String approvedSnapshotJson;

    @Column(length = 1000)
    private String errorMessage;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getRawInstruction() {
        return rawInstruction;
    }

    public void setRawInstruction(String rawInstruction) {
        this.rawInstruction = rawInstruction;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public String getPlanJson() {
        return planJson;
    }

    public void setPlanJson(String planJson) {
        this.planJson = planJson;
    }

    public String getApprovedSnapshotJson() {
        return approvedSnapshotJson;
    }

    public void setApprovedSnapshotJson(String approvedSnapshotJson) {
        this.approvedSnapshotJson = approvedSnapshotJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
