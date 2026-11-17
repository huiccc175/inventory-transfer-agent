# 仓间调拨 Agent

操作员说一句「把 SKU-8821 从上海仓调 30 件到广州仓」。服务把它解析成一份计划，存进 `agent_task`，停在 `awaiting_approval`。人审批之后，计划被复制成一份不可变快照，执行器只按这份快照往下执行：校验、预占库存、记下调拨。进程如果在半路停掉，重启后从已经落库的步骤继续，不会再解析原句，也不会向模型再要一版计划。

这一版没有聊天界面，也没有接入大模型。解析是按规则抽 `from_warehouse`、`to_warehouse`、`sku`、`qty`。库存是本机 H2 里的两行演示数据。

## 状态

`agent_task.status` 只有这几个值：

| 状态 | 含义 |
| --- | --- |
| `draft` | 指令已经收下，计划还没写好 |
| `awaiting_approval` | 计划已持久化，等人工审批，此时不动库存 |
| `executing` | 快照已冻结，正在按步骤执行，或执行到一半中断了 |
| `done` | 快照里的步骤都成功了 |
| `failed` | 指令无法解析，或执行中出现了业务错误（例如库存不足） |

## 三件必须守住的事

1. **断点续跑。** 每个步骤成功后单独提交到 `task_step_execution`。预占也在自己的事务里提交。启动时 `RecoveryService` 只捞 `executing` 的任务，跳过已经成功的步骤。恢复路径不调用解析器。
2. **快照冻结。** 审批把当时的 `plan_json` 抄到 `approved_snapshot_json`。这个字段一旦有值就不再改，哪怕后来草稿被换成另一份计划。执行器的 `resume` 没有计划参数，只读这份快照。若有人把另一份计划送进 `executeCandidate`，字段顺序、数量类型或步骤顺序有任何不同，都会抛 `PlanSnapshotMismatchException`，库存不动。
3. **预占幂等。** 幂等键是 `{taskId}:{stepId}`，例如 `15:reserve`，存在 `inventory_reservation` 上并加了唯一约束。同一步骤再次调用时直接返回第一次的数量，不会按新的 qty 再扣一次。

比较快照时会压缩空白，但**保留字段顺序和值的类型**。所以下面两份计划不会被当成同一份：

- 计划 A：`"qty": 30`
- 计划 B：`"qty": "30件"`，并且字段顺序、步骤顺序也不同

业务上都是 SKU-8821 从上海仓到广州仓调 30 件。执行器不负责“再理解一遍”，只认审批时冻住的那一串 JSON。

## 代码从哪看

| 类 | 作用 |
| --- | --- |
| `InstructionParser` | 规则解析。以后换 LLM 只改这里，而且新输出只能作为待审批草稿 |
| `TransferTaskService.freezeApprovedSnapshot` | 把计划抄成快照，只写一次 |
| `TransferExecutor` | 按快照执行；`executeCandidate` 负责拒绝另一份计划 |
| `InventoryService.reserve` | 幂等预占 |
| `RecoveryService` | 启动时和实验里的“重启”都走这里 |
| `CrashGate` | 某步骤落库后抛异常，用来模拟进程中断。默认关闭 |

演示库存（只在行不存在时插入，重启不会把已经预占的数量还回去）：

| 仓库 | SKU | 可用 | 已预占 |
| --- | --- | --- | --- |
| 上海仓 | SKU-8821 | 100 | 0 |
| 广州仓 | SKU-8821 | 10 | 0 |

本切片只做调出仓预占：上海仓可用减 30、已预占加 30。广州仓数量不变。

## 环境

- JDK 17 或更高。编译目标是 Java 17（JDK 21 也可以，Maven 会按 17 生成字节码）。
- Maven 3.8+。仓库里没有包装器时，直接用系统里的 `mvn`。

```bash
mvn test
mvn spring-boot:run
```

服务默认监听 `http://localhost:8080`。业务数据在 `./data/transfer`（H2 文件库，已加入 `.gitignore`）。停掉进程再启动，`executing` 的任务会自动续跑。

## 调用示例

创建任务（解析后停在待审批，库存不变）：

```bash
curl -s -X POST http://localhost:8080/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"instruction":"把 SKU-8821 从上海仓调 30 件到广州仓"}'
```

审批并执行。正常情况下状态变为 `done`，上海仓可用变为 70、已预占变为 30：

```bash
curl -s -X POST http://localhost:8080/api/tasks/1/approve
```

查看任务和库存：

```bash
curl -s http://localhost:8080/api/tasks/1
curl -s http://localhost:8080/api/inventory
```

如果进程在 `executing` 时停过，重启后会自动续跑。也可以手动补一刀：

```bash
curl -s -X POST http://localhost:8080/api/tasks/1/resume
```

再审批一次或再 `resume` 一次不会重复扣库存。

## 手动模拟崩溃再启动

`app.crash.after-step=reserve` 会在 `reserve` 步骤的结果**已经提交之后**抛错，任务保持 `executing`。先停掉进程，再用不带这个参数的命令启动，文件库里的断点还在，启动恢复会把剩下的步骤做完，库存仍然只扣一次。

```bash
# 终端里先启动带崩溃点的进程
mvn spring-boot:run -Dspring-boot.run.arguments=--app.crash.after-step=reserve

curl -s -X POST http://localhost:8080/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"instruction":"把 SKU-8821 从上海仓调 30 件到广州仓"}'

curl -s -X POST http://localhost:8080/api/tasks/1/approve
# 这里会返回 500，说明步骤 reserve 已落库但任务还没完成。然后 Ctrl+C 停掉进程。

# 再正常启动。启动日志里会恢复 executing 任务。
mvn spring-boot:run

curl -s http://localhost:8080/api/tasks/1
curl -s http://localhost:8080/api/inventory
```

第二次启动不要再带 `--app.crash.after-step=reserve`。已经成功的步骤会被跳过，所以即使开关还开着，也不会在同一步上再次自杀；但为了和真实恢复一致，重启时请关掉它。

## 实验测试

手写计划在：

- `src/test/resources/experiment/plan-a.json`
- `src/test/resources/experiment/plan-b.json`

自动化实验是 `TransferRecoveryExperimentTest`（JUnit）：

```bash
mvn test
mvn test -Dtest=TransferRecoveryExperimentTest
```

实验 1：写入计划 A 和一份尚未审批的计划 B，冻结 A 的快照，执行到 `reserve` 落库后模拟崩溃。此时任务仍是 `executing`，上海仓已经少了 30。接着用同一个幂等键、但数量写成 999 再调一次预占，断言不会再扣。然后把草稿改成计划 B、把原始指令改成无法解析的句子，调用和进程启动相同的 `RecoveryService.resumeInterrupted()`。断言只有 A 被续跑到 `done`，预占记录仍然只有一条、数量是 30，B 还停在 `awaiting_approval`。

实验 2：A 审批之后，把“只调换字段顺序”的计划和计划 B 分别送进执行器，两者都被拒绝，库存保持 100。把草稿改成 B 再审批一次，快照仍然是 A。计划 B 自己也无法被冻结成可执行快照。最后按 A 的快照执行，库存只扣一次。

崩溃在测试里是“步骤事务提交之后抛异常”，然后走真正的恢复入口。没有杀掉操作系统进程；手动一节用文件库和重启覆盖那条路径。

## 以后可以补什么

- 把 `InstructionParser` 换成真实 LLM。模型输出只写入 `plan_json` 并停在 `awaiting_approval`。不要把模型的新输出直接交给执行器；要对一下快照，不一致就拒绝。
- 生产库换成 PostgreSQL。H2 文件库只为了本机重启后数据还在。
- 预占之后补真正的出库、入库单据。现在只有调出仓预占。
- 审批接口加上操作者身份。
- 不同任务同时预占同一行库存时，加行锁，避免超卖。同一任务的重试已经由幂等键挡住。
