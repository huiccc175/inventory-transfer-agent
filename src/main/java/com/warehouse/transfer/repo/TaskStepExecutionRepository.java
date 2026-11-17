package com.warehouse.transfer.repo;

import com.warehouse.transfer.domain.TaskStepExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskStepExecutionRepository extends JpaRepository<TaskStepExecution, Long> {

    Optional<TaskStepExecution> findByTaskIdAndStepId(Long taskId, String stepId);

    List<TaskStepExecution> findByTaskIdOrderByIdAsc(Long taskId);
}
