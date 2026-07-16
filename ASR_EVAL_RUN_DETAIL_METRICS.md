# 评估任务详情指标说明

本文档只说明 `评估任务详情` 页面中实际展示的指标，不包含 `/eval` 主看板中的其他统计。

相关实现位置：

- 前端页面：[chat-bot-react/src/pages/EvalRunPage.tsx](/Users/liang/program/chat-bot-demo/chat-bot-react/src/pages/EvalRunPage.tsx)
- Python 评分服务：[asr-evaluator-python/app/main.py](/Users/liang/program/chat-bot-demo/asr-evaluator-python/app/main.py)
- Java 汇总逻辑：[chat-bot-java/src/main/java/org/example/asr/handler/EvalRunController.java](/Users/liang/program/chat-bot-demo/chat-bot-java/src/main/java/org/example/asr/handler/EvalRunController.java)
- 前端评分/排序逻辑：[chat-bot-react/src/lib/asrEval.ts](/Users/liang/program/chat-bot-demo/chat-bot-react/src/lib/asrEval.ts)

## 1. 顶部指标卡

| 指标 | 来源 | 含义 | 计算方式 |
|---|---|---|---|
| `任务状态` | 自定义 | 当前评估任务的运行状态 | 直接显示后端任务状态字段，常见值有 `running`、`paused`、`completed`、`failed`、`stopped` |
| `Case 完成` | 自定义 | 已完成的 case 数 / 总 case 数 | 显示 `completedCases / totalCases` |
| `厂商完成` | 自定义 | 已结束的厂商结果数 / 应跑厂商结果总数 | 显示 `doneVendors / totalVendors` |
| `记录更新` | 自定义 | 当前 run 最近一次更新时间 | 直接显示 `run.updatedAt` |
| `平均首包` | 自定义聚合 | 当前 run 中所有已完成厂商结果的平均首包时延 | 对所有有 `firstLatencyMs` 的结果取平均 |
| `超时 / 失败` | 自定义聚合 | 当前 run 的超时率 / 失败率 | 页面显示的是各厂商 `timeoutRate` 和 `failureRate` 的平均值；等价于从整体角度看当前 run 的超时与失败占比 |
| `当前最佳厂商` | 自定义排序 | 当前 run 中综合表现最好的厂商 | 先计算每个厂商 `综合分`，再按排序规则取第一名 |

## 2. 厂商总对比

| 指标 | 来源 | 含义 | 计算方式 |
|---|---|---|---|
| `排名` | 自定义排序 | 厂商在本次 run 中的名次 | 依次比较 `通过率`、`CER`、`最终时延` |
| `厂商` | 自定义 | 厂商名称 | 当前支持 `Stepfun`、`豆包 / 火山`、`阿里云` |
| `通过率` | 自定义聚合 | 该厂商通过的比例 | `passed / completed` |
| `CER` | `jiwer` + 自定义聚合 | 该厂商平均字符错误率 | 每条结果先用 `jiwer` 算 `CER`，再对该厂商所有已完成结果取平均 |
| `WER` | `jiwer` + 自定义聚合 | 该厂商平均词错误率 | 每条结果先用 `jiwer` 算 `WER`，再对该厂商所有已完成结果取平均 |
| `实体` | 自定义聚合 | 该厂商平均实体准确率 | 每条结果先算 `entityAccuracy`，再对该厂商所有有实体值的结果取平均 |
| `首包` | 自定义聚合 | 该厂商平均首包时延 | 对该厂商所有有 `firstLatencyMs` 的结果取平均 |
| `最终` | 自定义聚合 | 该厂商平均总时延 | 对该厂商所有有 `finalLatencyMs` 的结果取平均 |
| `胜出` | 自定义 | 该厂商赢了多少个 case | 每个 case 先选出一个“最佳厂商结果”，该厂商被选中的次数就是 `winCount` |
| `超时` | 自定义计数 | 该厂商超时次数 | `status === timeout` 的结果数量 |
| `失败` | 自定义计数 | 该厂商失败次数 | `status === failed` 的结果数量 |

### 2.1 综合分说明

厂商 `综合分` 使用如下权重：

- `通过率`: `0.35`
- `CER`: `0.25`
- `WER`: `0.12`
- `实体准确率`: `0.12`
- `平均首包时延`: `0.08`
- `平均总时延`: `0.08`

其中时延先转成 0 到 1 的得分：

- `首包得分 = 1 - min(avgFirstLatencyMs / 5000, 1)`
- `最终时延得分 = 1 - min(avgFinalLatencyMs / 10000, 1)`

如果某个指标没有值，则该项不参与加权，最后按实际参与的权重重新归一化。

## 3. 运行概览

这个区域展示的是每个厂商的简版汇总，与“厂商总对比”使用同一批底层数据。

| 指标 | 来源 | 含义 | 计算方式 |
|---|---|---|---|
| `completed/totalCases` | 自定义 | 该厂商已完成多少个 case | `summary.completed / run.summary.totalCases` |
| `Pass` | 自定义聚合 | 该厂商通过率 | `passed / completed` |
| `CER` | `jiwer` + 自定义聚合 | 该厂商平均 CER | 同上 |
| `WER` | `jiwer` + 自定义聚合 | 该厂商平均 WER | 同上 |
| `Latency` | 自定义聚合 | 该厂商平均总时延 | `avgFinalLatencyMs` |

## 4. 结果矩阵

### 4.1 行级指标

| 指标 | 来源 | 含义 | 计算方式 |
|---|---|---|---|
| `Case` | 自定义 | case 名称和类型 | 直接展示当前 case 的名称和 caseType |
| `参考文本` | 自定义 | 当前 case 的标准文本 | 直接展示 case 的 `referenceText` |
| `状态` | 自定义 | 当前 case 的整体流转状态 | 根据该 case 下所有厂商结果综合判断，如运行中、完成、失败、超时 |
| `最佳` | 自定义排序 | 当前 case 下表现最好的厂商 | 通过单条结果排序逻辑选出最佳结果对应的厂商 |
| `结论` | 自定义 | 当前 case 有多少厂商通过、多少厂商已完成 | 显示 `passCount / vendorCount` 与 `doneCount / vendorCount` |

### 4.2 每个厂商格子中的指标

| 指标 | 来源 | 含义 | 计算方式 |
|---|---|---|---|
| `CER` | `jiwer` | 当前结果字符错误率 | 由 Python 评分服务计算 |
| `总时延` | 自定义 | 当前结果从开始到最终识别完成的耗时 | 直接显示 `finalLatencyMs` |

### 4.3 单条结果排序逻辑

单条结果比较顺序如下：

1. `是否通过`
2. `CER`
3. `总时延`
4. `厂商名称`

WER、首包时延与总时延仅作诊断展示；总时延只在胜出排序完全相同时作为平分项。

## 5. 明细区

“明细”区域展示单个 `case x vendor` 的详细结果。

| 指标 | 来源 | 含义 | 计算方式 |
|---|---|---|---|
| `参考文本` | 自定义 | 当前 case 的标准文本 | 直接展示 case 的 `referenceText` |
| `识别结果` | 厂商 ASR 原始输出 | 当前厂商最终识别文本 | 后端识别完成后落入 `transcript` |
| `差异高亮` | 自定义 | 参考文本与识别结果的文本差异 | 前端 diff 高亮展示，不参与评分 |
| `阶段` | 自定义 | 当前结果所处阶段 | 直接显示 `phase`，如 `queued`、`recognizing`、`done`、`failed`、`timeout` |
| `CER` | `jiwer` | 当前结果字符错误率 | 先归一化，再去空白，再做字符级编辑距离计算 |
| `WER` | `jiwer` | 当前结果词错误率 | 先按自定义中文 token 规则切分，再做词级编辑距离计算 |
| `首包` | 自定义 | 当前结果首次有效识别返回的耗时 | `firstLatencyMs` |
| `总时延` | 自定义 | 当前结果最终识别完成耗时 | `finalLatencyMs` |
| `实体` | 自定义 | 命中实体数 / 实体总数 | 显示 `entityMatchedCount / entityTotalCount` |
| `实体准确率` | 自定义 | 当前结果关键实体命中率 | `entityMatchedCount / entityTotalCount` |
| `结果` | 自定义 | 当前结果是否通过 | 根据 case 配置的通过规则判断 |
| `归一化` | 自定义 | 当前评分所使用的归一化规则版本 | 当前固定为 `zh-v1` |
| `字符级 S / I / D` | `jiwer` | 字符级替换 / 插入 / 删除次数 | 来自 `jiwer` 的字符级编辑统计 |
| `词级 S / I / D` | `jiwer` | 词级替换 / 插入 / 删除次数 | 来自 `jiwer` 的词级编辑统计 |
| `实体缺失` | 自定义 | 当前结果未命中的关键实体列表 | 从配置的关键实体中筛出未命中的项 |

### 5.1 通过规则

当前 `结果` 的判定规则有三种：

| 规则 | 含义 | 计算方式 |
|---|---|---|
| `cer` | 以字符错误率为主 | `cer <= passThreshold` |
| `entity` | 以关键实体为主 | 配置关键实体时 `entityAccuracy == 1.0`；未配置时回退为 `cer <= passThreshold` |
| `mixed` | 同时要求文本和关键实体 | 配置关键实体时 `cer <= passThreshold` 且 `entityAccuracy == 1.0`；未配置时回退为 `cer <= passThreshold` |

## 6. 历史对比

这个区域比较最近两次 run 的平均指标变化。

| 指标 | 来源 | 含义 | 计算方式 |
|---|---|---|---|
| `Pass` | 自定义聚合 | 某次 run 的平均通过率 | 把该 run 下各厂商 `passRate` 再取平均 |
| `CER` | `jiwer` + 自定义聚合 | 某次 run 的平均 CER | 把各厂商 `avgCer` 再取平均 |
| `WER` | `jiwer` + 自定义聚合 | 某次 run 的平均 WER | 把各厂商 `avgWer` 再取平均 |
| `Final` | 自定义聚合 | 某次 run 的平均总时延 | 把各厂商 `avgFinalLatencyMs` 再取平均 |

## 7. 哪些指标来自 jiwer

在 `评估任务详情` 页面中，直接依赖 `jiwer` 的指标只有：

1. `CER`
2. `WER`
3. `字符级 S / I / D`
4. `词级 S / I / D`

基于这些单条结果再做聚合后展示的有：

1. `厂商总对比` 中的 `CER`
2. `厂商总对比` 中的 `WER`
3. `运行概览` 中的 `CER`
4. `运行概览` 中的 `WER`
5. `历史对比` 中的 `CER`
6. `历史对比` 中的 `WER`

## 8. 哪些指标是自定义的

除 `jiwer` 相关指标外，页面中其余指标都属于自定义或自定义聚合，包括：

1. `任务状态`
2. `Case 完成`
3. `厂商完成`
4. `记录更新`
5. `平均首包`
6. `超时 / 失败`
7. `当前最佳厂商`
8. `排名`
9. `综合分`
10. `通过率`
11. `实体`
12. `首包`
13. `最终`
14. `胜出`
15. `状态`
16. `最佳`
17. `结论`
18. `分`
19. `阶段`
20. `总时延`
21. `实体准确率`
22. `结果`
23. `归一化`
24. `实体缺失`
25. `历史对比` 中的 `Pass`
26. `历史对比` 中的 `Final`
