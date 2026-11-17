package com.warehouse.transfer.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 每一步单独提交。
 * 进程在两次提交之间停掉时，已经写进库的步骤还在，没写进去的步骤恢复时再做。
 * 不用一个大事务包住整个调拨，否则崩溃会把预占一起回滚，演示不出“从断点继续”。
 */
@Component
public class Checkpoints {

    private final TransactionTemplate template;

    public Checkpoints(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.template = template;
    }

    public void commit(Runnable work) {
        template.executeWithoutResult(status -> work.run());
    }

    public <T> T commit(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
