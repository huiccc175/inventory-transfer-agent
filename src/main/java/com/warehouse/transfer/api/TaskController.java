package com.warehouse.transfer.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.transfer.domain.AgentTask;
import com.warehouse.transfer.domain.InventoryBalance;
import com.warehouse.transfer.domain.TaskStepExecution;
import com.warehouse.transfer.service.InventoryService;
import com.warehouse.transfer.service.TransferTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api")
public class TaskController {

    private final TransferTaskService taskService;
    private final InventoryService inventoryService;
    private final ObjectMapper objectMapper;

    public TaskController(
            TransferTaskService taskService,
            InventoryService inventoryService,
            ObjectMapper objectMapper
    ) {
        this.taskService = taskService;
        this.inventoryService = inventoryService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/tasks")
    public ResponseEntity<TaskResponse> create(@RequestBody CreateTaskRequest request) {
        AgentTask task = taskService.submitInstruction(request.instruction());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(task));
    }

    @GetMapping("/tasks/{id}")
    public TaskResponse get(@PathVariable long id) {
        return toResponse(taskService.get(id));
    }

    @PostMapping("/tasks/{id}/approve")
    public TaskResponse approve(@PathVariable long id) {
        return toResponse(taskService.approve(id));
    }

    @PostMapping("/tasks/{id}/resume")
    public TaskResponse resume(@PathVariable long id) {
        return toResponse(taskService.resume(id));
    }

    @GetMapping("/inventory")
    public List<InventoryItem> inventory() {
        List<InventoryItem> items = new ArrayList<>();
        for (InventoryBalance balance : inventoryService.list()) {
            items.add(new InventoryItem(
                    balance.getWarehouseName(),
                    balance.getWarehouseCode(),
                    balance.getSku(),
                    balance.getOnHand(),
                    balance.getReservedQty()
            ));
        }
        return items;
    }

    private TaskResponse toResponse(AgentTask task) {
        List<StepResponse> steps = new ArrayList<>();
        for (TaskStepExecution step : taskService.steps(task.getId())) {
            steps.add(new StepResponse(
                    step.getStepId(),
                    step.getStepType(),
                    step.getStatus().name(),
                    readTree(step.getResultJson())
            ));
        }
        return new TaskResponse(
                task.getId(),
                task.getStatus().name(),
                task.getRawInstruction(),
                readTree(task.getPlanJson()),
                readTree(task.getApprovedSnapshotJson()),
                steps,
                task.getErrorMessage()
        );
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (IOException ex) {
            throw new IllegalStateException("库存任务里的 JSON 损坏", ex);
        }
    }

    public record CreateTaskRequest(String instruction) {
    }

    public record TaskResponse(
            long id,
            String status,
            String instruction,
            JsonNode plan,
            JsonNode approvedSnapshot,
            List<StepResponse> steps,
            String errorMessage
    ) {
    }

    public record StepResponse(String stepId, String type, String status, JsonNode result) {
    }

    public record InventoryItem(
            String warehouseName,
            String warehouseCode,
            String sku,
            int onHand,
            int reservedQty
    ) {
    }
}
