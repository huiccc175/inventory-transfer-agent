package com.warehouse.transfer;

import com.warehouse.transfer.config.InventorySeeder;
import com.warehouse.transfer.domain.TaskStatus;
import com.warehouse.transfer.repo.AgentTaskRepository;
import com.warehouse.transfer.repo.InventoryBalanceRepository;
import com.warehouse.transfer.repo.InventoryReservationRepository;
import com.warehouse.transfer.repo.TaskStepExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:transfer-api;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.recovery.enabled=false",
        "app.crash.after-step="
})
@AutoConfigureMockMvc
class TransferTaskApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired InventorySeeder seeder;
    @Autowired AgentTaskRepository taskRepository;
    @Autowired TaskStepExecutionRepository stepRepository;
    @Autowired InventoryBalanceRepository balanceRepository;
    @Autowired InventoryReservationRepository reservationRepository;

    @BeforeEach
    void resetDatabase() {
        reservationRepository.deleteAll();
        stepRepository.deleteAll();
        taskRepository.deleteAll();
        balanceRepository.deleteAll();
        seeder.seed();
    }

    @Test
    void createThenApproveReservesStockOnce() throws Exception {
        String body = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"把 SKU-8821 从上海仓调 30 件到广州仓\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("awaiting_approval"))
                .andExpect(jsonPath("$.plan.sku").value("SKU-8821"))
                .andExpect(jsonPath("$.plan.fromWarehouse").value("上海仓"))
                .andExpect(jsonPath("$.plan.toWarehouse").value("广州仓"))
                .andExpect(jsonPath("$.plan.qty").value(30))
                .andExpect(jsonPath("$.plan.steps.length()").value(3))
                .andExpect(jsonPath("$.approvedSnapshot").value(nullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Number id = com.jayway.jsonpath.JsonPath.read(body, "$.id");
        assertThat(onHand(InventorySeeder.SHANGHAI)).isEqualTo(InventorySeeder.SHANGHAI_INITIAL);

        mockMvc.perform(post("/api/tasks/" + id.longValue() + "/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.approvedSnapshot.qty").value(30))
                .andExpect(jsonPath("$.approvedSnapshot.sku").value("SKU-8821"))
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andExpect(jsonPath("$.steps[1].stepId").value("reserve"))
                .andExpect(jsonPath("$.steps[1].result.replayed").value(false))
                .andExpect(jsonPath("$.steps[1].result.qty").value(30));

        assertThat(onHand(InventorySeeder.SHANGHAI)).isEqualTo(70);
        assertThat(reserved(InventorySeeder.SHANGHAI)).isEqualTo(30);
        assertThat(onHand(InventorySeeder.GUANGZHOU)).isEqualTo(InventorySeeder.GUANGZHOU_INITIAL);
        assertThat(reservationRepository.count()).isEqualTo(1);

        mockMvc.perform(post("/api/tasks/" + id.longValue() + "/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"));
        mockMvc.perform(post("/api/tasks/" + id.longValue() + "/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"));

        assertThat(onHand(InventorySeeder.SHANGHAI)).isEqualTo(70);
        assertThat(reservationRepository.count()).isEqualTo(1);

        mockMvc.perform(get("/api/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].warehouseName").value("上海仓"))
                .andExpect(jsonPath("$[0].onHand").value(70))
                .andExpect(jsonPath("$[0].reservedQty").value(30))
                .andExpect(jsonPath("$[1].warehouseName").value("广州仓"))
                .andExpect(jsonPath("$[1].onHand").value(10));
    }

    @Test
    void unknownSentenceIsRejectedAndStoredAsFailed() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"hello\"}"))
                .andExpect(status().isBadRequest());

        assertThat(taskRepository.findAll()).hasSize(1);
        assertThat(taskRepository.findAll().get(0).getStatus()).isEqualTo(TaskStatus.failed);
        assertThat(onHand(InventorySeeder.SHANGHAI)).isEqualTo(InventorySeeder.SHANGHAI_INITIAL);
    }

    private int onHand(String warehouse) {
        return balanceRepository.findByWarehouseNameAndSku(warehouse, InventorySeeder.SKU)
                .orElseThrow()
                .getOnHand();
    }

    private int reserved(String warehouse) {
        return balanceRepository.findByWarehouseNameAndSku(warehouse, InventorySeeder.SKU)
                .orElseThrow()
                .getReservedQty();
    }
}
