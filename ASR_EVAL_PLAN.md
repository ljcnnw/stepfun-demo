# ASR 厂商评估页面方案

## 1. 背景

当前项目已经具备三家 ASR 厂商的接入能力：

- Stepfun
- 豆包（火山引擎）
- 阿里云

目标是新建一套独立的前端评估页面，用于批量跑 `asr-test-cases` 中的音频 case，对三家厂商的识别效果做统一评估和对比。

这套页面不依赖现有的对话页 UI，只复用后端现成接口和 case 数据。

## 2. 目标

1. 批量运行 case
2. 支持三家厂商并行对比
3. 输出逐 case 的通过/失败结果
4. 自动计算主流 ASR 评估指标
5. 输出厂商级汇总指标
6. 支持查看单个 case 的识别详情、diff 和耗时

## 3. 推荐的评估指标

### 3.1 主指标

建议优先展示以下指标：

- CER: Character Error Rate，中文场景最核心
- WER: Word Error Rate，中英混合时有用
- Sentence Accuracy: 整句是否完全正确
- Key Entity Accuracy: 数字、金额、日期、手机号、人名等关键实体是否正确

### 3.2 工程指标

- First Latency: 首字返回时间
- Final Latency: 最终结果完成时间
- Timeout Rate: 超时率
- Failure Rate: 请求失败率、识别失败率

### 3.3 不建议首版就做的指标

- DER: 说话人分离指标，不适合单人 ASR 对比
- 音频质量类指标，如 PESQ/SSIM，不属于转写评估核心
- 复杂多轮对话质量指标，首版优先保证单轮评估可靠

## 4. 指标定义

### 4.1 CER

中文场景建议以字符为单位计算：

`CER = (S + D + I) / N`

其中：

- `S` = substitute，替换错误数
- `D` = delete，删除错误数
- `I` = insert，插入错误数
- `N` = 标准答案字符数

### 4.2 WER

适合英文或中英混合：

`WER = (S + D + I) / N_words`

### 4.3 Sentence Accuracy

整句完全匹配则记为 1，否则记为 0。

### 4.4 Key Entity Accuracy

针对 case 中的关键实体字段逐项判断：

- 数字
- 金额
- 电话
- 日期
- 人名
- 地址

可以按“实体全对才算对”或“命中率”两种方式统计。

## 5. 评估口径建议

建议支持两种文本口径：

### 5.1 严格模式

- 保留原始文本
- 适合检查格式和标点准确度

### 5.2 宽松模式

做统一归一化后再比对：

- 去标点
- 去空白
- 统一全半角
- 统一数字格式
- 统一大小写

建议默认以“宽松模式”做主评估，“严格模式”作为辅助展示。

## 6. Case 数据结构

目前 case 只有音频和基础 meta，建议补充标准答案字段。

### 6.1 推荐字段

```json
{
  "id": "case-id",
  "name": "数字测试",
  "note": "",
  "originalFileName": "xxx.webm",
  "audioExt": ".webm",
  "durationSeconds": 4.44,
  "createdAt": "2026-07-08T16:29:42",
  "referenceText": "标准转写文本",
  "caseType": "number",
  "criticalTerms": ["12345", "2026年7月9日"],
  "passRule": {
    "type": "cer_threshold",
    "threshold": 0.08
  }
}
```

### 6.2 caseType 建议枚举

- `number`
- `money`
- `name`
- `sentence`
- `mixed`
- `noise`

## 7. 通过/失败规则

建议不要只用一个统一阈值，而是按 case 类型配置规则。

### 7.1 推荐规则

- 数字 / 金额 / 电话类
  - 关键实体必须完全正确
- 普通口语类
  - CER 达标即可
- 中英混合类
  - CER + WER 共同判断
- 噪声类
  - 允许更宽松阈值，但单独统计

### 7.2 示例

- `sentence`
  - `CER <= 0.08`
- `number`
  - `关键实体全部正确`
- `mixed`
  - `CER <= 0.10 && WER <= 0.12`

## 8. 前端页面结构

建议新建一套独立页面，而不是复用现有对话页。

### 8.1 页面分区

1. 顶部配置区
   - 选择厂商
   - 选择 case 集合
   - 选择评估模式
   - 选择通过规则
   - 开始 / 暂停 / 终止

2. 进度区
   - 当前任务进度
   - 已完成 / 总数
   - 成功 / 失败 / 超时

3. 厂商总览区
   - 每家厂商一个汇总卡片
   - 展示平均 CER、WER、通过率、延迟等

4. 明细表格区
   - 每个 case 一行
   - 三家厂商的识别结果和指标并排展示

5. 详情面板
   - 标准文本 vs 识别文本 diff
   - 音频回放
   - 原始日志

## 9. 任务流程

### 9.1 推荐流程

1. 选择需要评估的 case
2. 选择厂商：Stepfun / 豆包 / 阿里云
3. 选择运行模式
4. 点击开始
5. 前端逐个 case 触发后端识别
6. 收集每家厂商的 transcript、时间戳、错误信息
7. 前端计算指标
8. 渲染表格和汇总卡片

### 9.2 跑批策略建议

建议默认：

- case 之间串行
- 同一个 case 下三家厂商并行

这样便于对齐数据，也能兼顾速度和稳定性。

如果厂商限流或连接不稳定，再切换为完全串行。

## 10. 结果数据结构

### 10.1 单 case 结果

```json
{
  "caseId": "xxx",
  "caseName": "数字测试",
  "referenceText": "标准答案",
  "results": {
    "stepfun": {
      "transcript": "识别结果",
      "cer": 0.03,
      "wer": 0.05,
      "pass": true,
      "latencyMs": 1820,
      "errorMsg": ""
    },
    "aliyun": {},
    "volc": {}
  }
}
```

### 10.2 厂商汇总结果

```json
{
  "vendor": "stepfun",
  "avgCer": 0.04,
  "avgWer": 0.06,
  "passRate": 0.92,
  "entityAccuracy": 0.97,
  "avgFirstLatencyMs": 620,
  "avgFinalLatencyMs": 1800,
  "timeoutRate": 0.03,
  "failureRate": 0.02
}
```

## 11. 推荐的实现拆分

### 11.1 页面层

建议新页面按“总览 + 明细 + 详情”三层组织：

- 总览页：任务配置、厂商汇总、运行状态
- 明细表：逐 case 对比
- 详情抽屉：单 case diff、音频、原始事件流

推荐路由：

- `/eval`：评估首页
- `/eval/runs/:runId`：某次评估任务详情
- `/eval/cases`：case 管理

### 11.2 组件层

建议拆成这些组件：

- `EvalHeader`
- `VendorSelector`
- `CaseSelector`
- `RunControlBar`
- `ProgressSummary`
- `VendorMetricCard`
- `ResultTable`
- `CaseDetailDrawer`
- `TranscriptDiff`
- `AudioPreviewPlayer`
- `RunHistoryPanel`

### 11.3 状态层

建议把评估任务状态显式建模：

- `idle`
- `loading`
- `ready`
- `running`
- `paused`
- `completed`
- `cancelled`
- `failed`

建议单独维护三类状态：

- 全局任务状态
- 厂商级状态
- case 级状态

### 11.4 业务层

- 任务编排
- case 分发
- 结果聚合
- 指标计算
- pass/fail 判定
- 重跑策略

### 11.5 数据层

- 拉取 case 列表
- 拉取 case 音频
- 拉取 referenceText
- 记录运行日志
- 保存评估结果

## 12. 评估任务模型

建议新增一个“run”概念，表示一次完整的评估任务。

### 12.1 任务结构

```json
{
  "runId": "run-001",
  "name": "2026-07-09 三厂商评估",
  "vendors": ["stepfun", "volc", "aliyun"],
  "mode": "batch",
  "caseIds": ["case-a", "case-b"],
  "status": "running",
  "startedAt": "2026-07-09T10:00:00",
  "finishedAt": null
}
```

### 12.2 单 case 任务状态

每个 case 建议有自己的状态流转：

- `queued`
- `fetching_audio`
- `sending_audio`
- `recognizing`
- `done`
- `failed`
- `timeout`

### 12.3 单厂商结果结构

```json
{
  "vendor": "stepfun",
  "status": "done",
  "transcript": "识别文本",
  "normalizedTranscript": "归一化文本",
  "firstLatencyMs": 620,
  "finalLatencyMs": 1810,
  "errorCode": "",
  "errorMsg": "",
  "metrics": {
    "cer": 0.04,
    "wer": 0.06,
    "sentenceAccuracy": 1,
    "entityAccuracy": 0.95,
    "pass": true
  }
}
```

## 13. 指标计算细化

### 13.1 文本归一化

建议在计算前做统一预处理：

- 去首尾空白
- 全角转半角
- 中文标点归一化
- 英文大小写归一
- 连续空白压缩为单空格
- 数字格式统一

### 13.2 CER / WER 计算方式

建议使用标准编辑距离算法：

- `distance = min(edit_ops)`
- `rate = distance / reference_length`

首版可以不自己发明算法，直接用成熟的编辑距离实现。

### 13.3 Sentence Accuracy

当且仅当归一化后的识别结果与标准答案完全一致时记为通过。

### 13.4 Key Entity Accuracy

建议按实体粒度统计：

- `entity_total`
- `entity_correct`
- `entity_accuracy = entity_correct / entity_total`

### 13.5 Pass 判定建议

可配置成：

- `pass = sentenceAccuracy == 1`
- 或 `pass = cer <= threshold`
- 或 `pass = keyEntityAllCorrect && cer <= threshold`

推荐 case 级别可配置，不要写死。

## 14. 交互流程细化

### 14.1 进入页面

1. 自动拉取 case 列表
2. 自动拉取历史 run 列表
3. 展示默认厂商选择
4. 展示最近一次 run 的结果概览

### 14.2 发起评估

1. 用户选择 case
2. 用户选择厂商
3. 用户选择通过规则
4. 用户点击开始
5. 前端创建 run
6. 前端按调度策略逐个执行
7. 实时刷新进度和表格

### 14.3 运行中

- 可暂停
- 可继续
- 可中止
- 可单 case 重跑

### 14.4 完成后

- 自动汇总厂商指标
- 支持导出
- 支持查看失败 case
- 支持按指标排序

## 15. 和后端的接口关系

当前项目里已经有 case 管理接口：

- `GET /asr-bench/cases`
- `POST /asr-bench/cases`
- `GET /asr-bench/cases/{id}/audio`
- `DELETE /asr-bench/cases/{id}`

但要做完整评估页，建议再补一层“评估元数据”支持。

### 15.1 当前缺口

现有 case meta 里原本还没有 `referenceText`，这是做 CER/WER 的前提。

当前实现已改为把评估字段直接写回本地 `meta.json`，保存动作通过：

- `POST /asr-bench/cases` 新建时写入评估字段
- `PUT /asr-bench/cases/{id}` 更新已有 case 的评估字段

这样可以做到“前端改完，保存后直接落到本地文件”。

### 15.2 两种补法

方案 A:

- 扩展 `meta.json`
- 新增 `referenceText / caseType / criticalTerms / passRule`

方案 B:

- 单独新增一个 `eval-manifest.json`
- case 音频继续沿用现有目录
- 标准答案集中存放在 manifest 中

推荐方案 A，结构更直观。

### 15.3 评估结果持久化

建议新增接口保存 run 结果：

- `POST /asr-eval/runs`
- `GET /asr-eval/runs`
- `GET /asr-eval/runs/{runId}`
- `POST /asr-eval/runs/{runId}/export`

如果首版不想做后端持久化，也可以先只做前端内存态 + 本地导出。

## 16. 风险和注意点

1. 没有标准答案就无法做有效评估
2. 不同厂商的分句策略不同，单看整句结果会有偏差
3. 流式识别的中间过程不能直接拿来做最终评分
4. 需要统一音频格式，否则结果不可比
5. 不同 case 类型应有不同阈值，不建议全局一刀切

## 17. 分阶段实施建议

### Phase 1: MVP

- 新建独立评估页
- 支持 case 列表和批量执行
- 支持三家厂商对比
- 输出 transcript、CER、pass/fail、latency

### Phase 2: 完整评估

- 增加 WER
- 增加关键实体准确率
- 增加 diff 视图
- 增加统计汇总

### Phase 3: 工程化

- 导出 CSV / JSON
- 支持重跑 N 次取均值
- 支持筛选、排序、分页
- 支持历史任务对比

## 18. 当前项目里的建议结论

1. 现有对话页不要改，保持原样
2. 新建一套独立评估前端
3. 指标优先做 CER、通过率、关键实体准确率、延迟
4. case 必须补标准答案，否则无法做有效评估
5. 先做 MVP，再逐步补全高级统计
