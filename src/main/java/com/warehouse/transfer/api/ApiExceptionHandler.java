package com.warehouse.transfer.api;

import com.warehouse.transfer.parser.InstructionParseException;
import com.warehouse.transfer.service.PlanSnapshotMismatchException;
import com.warehouse.transfer.service.SimulatedCrashException;
import com.warehouse.transfer.service.TaskNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InstructionParseException.class)
    public ResponseEntity<ErrorBody> badInstruction(InstructionParseException ex) {
        return ResponseEntity.badRequest().body(new ErrorBody(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> badPlan(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ErrorBody(ex.getMessage()));
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ErrorBody> missing(TaskNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody(ex.getMessage()));
    }

    @ExceptionHandler({IllegalStateException.class, PlanSnapshotMismatchException.class})
    public ResponseEntity<ErrorBody> conflict(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody(ex.getMessage()));
    }

    @ExceptionHandler(SimulatedCrashException.class)
    public ResponseEntity<ErrorBody> crashed(SimulatedCrashException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorBody(
                ex.getMessage() + "。请关掉崩溃开关后重启进程，或调用 POST /api/tasks/{id}/resume"
        ));
    }

    public record ErrorBody(String error) {
    }
}
