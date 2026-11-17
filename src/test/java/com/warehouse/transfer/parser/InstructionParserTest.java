package com.warehouse.transfer.parser;

import com.warehouse.transfer.plan.PlanStep;
import com.warehouse.transfer.plan.StepTypes;
import com.warehouse.transfer.plan.TransferPlan;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstructionParserTest {

    private final InstructionParser parser = new InstructionParser();

    @Test
    void parsesTheOperatorSentenceIntoAFixedPlan() {
        TransferPlan plan = parser.parse("把 SKU-8821 从上海仓调 30 件到广州仓");

        assertThat(plan.sku()).isEqualTo("SKU-8821");
        assertThat(plan.fromWarehouse()).isEqualTo("上海仓");
        assertThat(plan.toWarehouse()).isEqualTo("广州仓");
        assertThat(plan.qty()).isEqualTo(30);
        assertThat(plan.steps()).containsExactly(
                new PlanStep("validate", StepTypes.VALIDATE_TRANSFER),
                new PlanStep("reserve", StepTypes.RESERVE_INVENTORY),
                new PlanStep("record", StepTypes.RECORD_TRANSFER)
        );
    }

    @Test
    void toleratesExtraSpacesAndTrailingWords() {
        TransferPlan plan = parser.parse("请把  SKU-8821  从上海仓调  30  件到广州仓，谢谢");

        assertThat(plan.qty()).isEqualTo(30);
        assertThat(plan.fromWarehouse()).isEqualTo("上海仓");
        assertThat(plan.toWarehouse()).isEqualTo("广州仓");
    }

    @Test
    void rejectsASentenceThatIsNotATransfer() {
        assertThatThrownBy(() -> parser.parse("今天天气不错"))
                .isInstanceOf(InstructionParseException.class);
    }
}
