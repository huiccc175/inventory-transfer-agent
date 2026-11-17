package com.warehouse.transfer.repo;

import com.warehouse.transfer.domain.AgentTask;
import com.warehouse.transfer.domain.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {

    List<AgentTask> findByStatus(TaskStatus status);
}
