package com.warehouse.transfer.parser;

import com.warehouse.transfer.plan.PlanStep;
import com.warehouse.transfer.plan.StepTypes;
import com.warehouse.transfer.plan.TransferPlan;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 这一层故意不用模型。它只认识一种句子，把四个字段抽出来，步骤列表是写死的。
 * 将来换成 LLM 时，只替换这里；不要让模型的新输出直接进入执行器。
 *
 * 支持的句子：把 SKU-8821 从上海仓调 30 件到广州仓
 */
@Component
public class InstructionParser {

    private static final Pattern SKU = Pattern.compile("SKU-\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM = Pattern.compile("从\\s*(\\S+?仓)");
    private static final Pattern QTY = Pattern.compile("(\\d+)\\s*件");
    private static final Pattern TO = Pattern.compile("到\\s*(\\S+?仓)");

    public TransferPlan parse(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new InstructionParseException("instruction 不能为空");
        }
        String sku = first(SKU, instruction, 0, "没有找到 SKU，例如 SKU-8821");
        String fromWarehouse = first(FROM, instruction, 1, "没有找到调出仓，例如「从上海仓」");
        String toWarehouse = first(TO, instruction, 1, "没有找到调入仓，例如「到广州仓」");
        int qty = Integer.parseInt(first(QTY, instruction, 1, "没有找到数量，例如「30 件」"));
        if (qty <= 0) {
            throw new InstructionParseException("数量必须大于 0");
        }
        if (fromWarehouse.equals(toWarehouse)) {
            throw new InstructionParseException("调出仓和调入仓不能相同");
        }
        // 步骤 id 和顺序是计划的一部分，审批后就冻结，恢复时不会重新生成。
        List<PlanStep> steps = List.of(
                new PlanStep("validate", StepTypes.VALIDATE_TRANSFER),
                new PlanStep("reserve", StepTypes.RESERVE_INVENTORY),
                new PlanStep("record", StepTypes.RECORD_TRANSFER)
        );
        return new TransferPlan(sku.toUpperCase(), fromWarehouse, toWarehouse, qty, steps);
    }

    private static String first(Pattern pattern, String text, int group, String error) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            throw new InstructionParseException(error);
        }
        return matcher.group(group);
    }
}
