package com.warehouse.transfer.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 计划的读写，以及“是不是同一份已审批快照”的比较。
 * 比较前只压缩空白，字段顺序和值的类型都保留。
 * 因此只是换了字段顺序、把数量写成 "30件"、或者调换步骤，都是另一份计划，执行器必须拒绝。
 */
@Component
public class PlanSnapshots {

    private final ObjectMapper mapper;

    public PlanSnapshots(ObjectMapper mapper) {
        this.mapper = mapper.copy();
        // 快照全等要保留字段顺序，不能按字母重排。
        this.mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, false);
        this.mapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, false);
    }

    public String write(TransferPlan plan) {
        ObjectNode root = mapper.createObjectNode();
        root.put("sku", plan.sku());
        root.put("fromWarehouse", plan.fromWarehouse());
        root.put("toWarehouse", plan.toWarehouse());
        root.put("qty", plan.qty());
        ArrayNode steps = root.putArray("steps");
        for (PlanStep step : plan.steps()) {
            ObjectNode node = steps.addObject();
            node.put("stepId", step.stepId());
            node.put("type", step.type());
        }
        return write(root);
    }

    /**
     * 压缩空白，但不重排字段。审批时存进去的就是这串文本，之后用它做全等比较。
     */
    public String normalize(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || node.isNull()) {
                throw new IllegalArgumentException("计划不是合法 JSON");
            }
            return write(node);
        } catch (IOException ex) {
            throw new IllegalArgumentException("计划不是合法 JSON", ex);
        }
    }

    public boolean same(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    /**
     * 只接受已经冻结、可执行的快照：qty 必须是整数，不能是“30件”这种措辞。
     */
    public TransferPlan read(String json) {
        JsonNode node = readTree(json);
        JsonNode qtyNode = node.get("qty");
        if (qtyNode == null || !qtyNode.isIntegralNumber() || !qtyNode.canConvertToInt()) {
            throw new IllegalArgumentException("审批快照里的 qty 必须是整数，拒绝按措辞再解释一遍");
        }
        int qty = qtyNode.intValue();
        if (qty <= 0) {
            throw new IllegalArgumentException("qty 必须大于 0");
        }
        String sku = requiredText(node, "sku");
        String fromWarehouse = requiredText(node, "fromWarehouse");
        String toWarehouse = requiredText(node, "toWarehouse");
        if (fromWarehouse.equals(toWarehouse)) {
            throw new IllegalArgumentException("调出仓和调入仓不能相同");
        }
        JsonNode stepsNode = node.get("steps");
        if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
            throw new IllegalArgumentException("计划缺少 steps");
        }
        List<PlanStep> steps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode step : stepsNode) {
            String stepId = requiredText(step, "stepId");
            String type = requiredText(step, "type");
            if (!seen.add(stepId)) {
                throw new IllegalArgumentException("步骤 id 重复: " + stepId);
            }
            steps.add(new PlanStep(stepId, type));
        }
        return new TransferPlan(sku, fromWarehouse, toWarehouse, qty, List.copyOf(steps));
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (IOException ex) {
            throw new IllegalArgumentException("计划不是合法 JSON", ex);
        }
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("计划无法序列化", ex);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("计划缺少字段 " + field);
        }
        return value.asText();
    }
}
