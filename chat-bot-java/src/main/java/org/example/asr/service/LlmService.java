package org.example.asr.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.example.asr.client.TtsWebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private static final String SENTENCE_DELIMITERS = "。！？!?…";
    private static final String QUEUE_DONE = "__DONE__";
    private static final int MAX_HISTORY = 50;

    // Sierra 配置
    @Value("${sierra.api.token}")
    private String sierraToken;

    @Value("${sierra.api.key}")
    private String sierraApiKey;

    @Value("${sierra.api.url}")
    private String sierraUrl;

    @Value("${sierra.api.compatibility-date}")
    private String compatibilityDate;

    // Stepfun 配置
    @Value("${stepfun.api.key}")
    private String apiKey;

    @Value("${stepfun.llm.url}")
    private String llmUrl;

    @Value("${stepfun.llm.model}")
    private String llmModel;

    // TTS 配置（公用）
    @Value("${stepfun.tts.url}")
    private String ttsUrl;

    @Value("${stepfun.tts.voice}")
    private String ttsVoice;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    // per-session provider，默认 sierra
    private final Map<String, String> providerMap = new ConcurrentHashMap<>();

    // Sierra per-session state token
    private final Map<String, String> sierraStateMap = new ConcurrentHashMap<>();

    // Stepfun per-session 对话历史
    private final Map<String, List<JSONObject>> stepfunHistoryMap = new ConcurrentHashMap<>();

    public void setProvider(String sessionId, String provider) {
        providerMap.put(sessionId, provider);
        log.info("【Provider 切换】sessionId={}，provider={}", sessionId, provider);
    }

    public String getProvider(String sessionId) {
        return providerMap.getOrDefault(sessionId, "sierra");
    }

    public AtomicBoolean streamChat(String userText, String sessionId,
                                    WebSocketSession clientSession,
                                    Consumer<TtsWebSocketClient> onTtsClient) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        LinkedBlockingQueue<String> sentenceQueue = new LinkedBlockingQueue<>();
        String provider = getProvider(sessionId);

        if ("stepfun".equals(provider)) {
            executor.submit(() -> streamStepfun(userText, sessionId, clientSession, sentenceQueue, cancelled));
        } else {
            executor.submit(() -> streamSierra(userText, sessionId, clientSession, sentenceQueue, cancelled));
        }

        // TTS 串行消费线程（两个 provider 共用）
        executor.submit(() -> {
            try {
                while (true) {
                    String sentence = sentenceQueue.poll(30, TimeUnit.SECONDS);
                    if (sentence == null || QUEUE_DONE.equals(sentence) || cancelled.get()) {
                        log.info("【TTS 队列结束】退出消费线程，sessionId={}", sessionId);
                        break;
                    }
                    log.info("【TTS 开始播放】sentence={}，sessionId={}", sentence, sessionId);
                    CountDownLatch latch = new CountDownLatch(1);
                    TtsWebSocketClient tts = new TtsWebSocketClient(
                            new URI(ttsUrl), apiKey, ttsVoice, sentence, clientSession);
                    tts.setOnDone(latch::countDown);
                    onTtsClient.accept(tts);
                    tts.connect();
                    while (!latch.await(50, TimeUnit.MILLISECONDS)) {
                        if (cancelled.get()) {
                            log.info("【TTS 已取消】中断当前句播放，sessionId={}", sessionId);
                            tts.close();
                            return;
                        }
                    }
                    log.info("【TTS 播放完成】sentence={}，sessionId={}", sentence, sessionId);
                }
            } catch (Exception e) {
                if (!cancelled.get()) {
                    log.error("【TTS 错误】串行队列异常，sessionId={}", sessionId, e);
                }
            }
        });

        return cancelled;
    }

    private void streamSierra(String userText, String sessionId,
                               WebSocketSession clientSession,
                               LinkedBlockingQueue<String> sentenceQueue,
                               AtomicBoolean cancelled) {
        try {
            log.info("【Sierra 开始推理】userText={}，sessionId={}", userText, sessionId);

            URL url = new URL(sierraUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + sierraApiKey);
            conn.setRequestProperty("Sierra-API-Compatibility-Date", compatibilityDate);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setDoOutput(true);
            conn.setReadTimeout(60000);
            conn.setConnectTimeout(10000);

            JSONObject body = new JSONObject();
            body.put("token", sierraToken);
            String prevState = sierraStateMap.get(sessionId);
            if (prevState != null) body.put("state", prevState);
            JSONObject clientEvent = new JSONObject();
            clientEvent.put("type", "message");
            JSONObject message = new JSONObject();
            message.put("content", userText);
            clientEvent.put("message", message);
            body.put("clientEvent", clientEvent);
            log.info("【Sierra 上下文】state={}，sessionId={}", prevState != null ? "有" : "无", sessionId);

            byte[] bodyBytes = body.toJSONString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) { os.write(bodyBytes); }

            StringBuilder sentenceBuffer = new StringBuilder();
            String newState = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled.get()) { log.info("【Sierra 已取消】sessionId={}", sessionId); break; }
                    if (line.trim().isEmpty()) continue;
                    JSONObject chunk = JSONObject.parseObject(line);
                    String type = chunk.getString("type");
                    if ("state".equals(type)) { newState = chunk.getString("state"); continue; }
                    if ("endConversation".equals(type)) {
                        JSONObject ec = chunk.getJSONObject("endConversation");
                        String reason = ec != null ? ec.getString("reason") : "unknown";
                        log.warn("【Sierra 对话终止】reason={}，sessionId={}", reason, sessionId);
                        if ("Abuse Detected".equals(reason)) {
                            String fallback = "抱歉，我暂时无法回答这个问题。";
                            safeSend(clientSession, new JSONObject() {{ put("type", "llm.text.delta"); put("text", fallback); }});
                            safeSend(clientSession, new JSONObject() {{ put("type", "llm.text.done"); }});
                            sentenceQueue.put(fallback);
                        }
                        break;
                    }
                    if ("message".equals(type)) {
                        JSONObject msg = chunk.getJSONObject("message");
                        if (msg == null) continue;
                        String text = msg.getString("text");
                        if (text != null && !text.isEmpty()) {
                            final String td = text;
                            safeSend(clientSession, new JSONObject() {{ put("type", "llm.text.delta"); put("text", td); }});
                            sentenceBuffer.append(text);
                            enqueueIfSentence(sentenceBuffer, sentenceQueue, sessionId);
                        }
                        if (Boolean.TRUE.equals(msg.getBoolean("isEndOfMessage"))) break;
                    }
                }
            }

            String remaining = sentenceBuffer.toString().trim();
            if (!remaining.isEmpty() && !cancelled.get()) sentenceQueue.put(remaining);

            safeSend(clientSession, new JSONObject() {{ put("type", "llm.text.done"); }});
            log.info("【Sierra 推理完成】sessionId={}", sessionId);

            if (!cancelled.get() && newState != null) {
                sierraStateMap.put(sessionId, newState);
            } else if (cancelled.get()) {
                log.info("【Sierra state 跳过】本轮被打断，sessionId={}", sessionId);
            }
        } catch (Exception e) {
            if (!cancelled.get()) log.error("【Sierra 错误】sessionId={}", sessionId, e);
        } finally {
            try { sentenceQueue.put(QUEUE_DONE); } catch (InterruptedException ignored) {}
        }
    }

    private void streamStepfun(String userText, String sessionId,
                                WebSocketSession clientSession,
                                LinkedBlockingQueue<String> sentenceQueue,
                                AtomicBoolean cancelled) {
        StringBuilder fullReply = new StringBuilder();
        try {
            log.info("【Stepfun 开始推理】userText={}，sessionId={}", userText, sessionId);

            URL url = new URL(llmUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setReadTimeout(60000);
            conn.setConnectTimeout(10000);

            JSONObject body = new JSONObject();
            body.put("model", llmModel);
            body.put("stream", true);

            JSONArray messages = new JSONArray();
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", "你是「港股买入下单助手（HK Stock Buy Order Agent）」。\n" +
                    "你的唯一职责：协助客户完成香港市场股票“买入”下单流程，并生成结构化下单请求。\n" +
                    "你不是投资顾问，不提供收益承诺，不代替客户做投资决策。\n" +
                    "\n" +
                    "# 1) 角色与边界\n" +
                    "- 仅支持：香港市场股票买入（BUY）。\n" +
                    "- 禁止事项：\n" +
                    "  1. 不提供个性化投资建议（如“这只一定涨”）。\n" +
                    "  2. 不承诺收益、不诱导频繁交易。\n" +
                    "  3. 未经客户明确二次确认，不得提交订单。\n" +
                    "  4. 不绕过风控、合规、权限检查。\n" +
                    "- 任何规则冲突时：以券商/交易系统实时规则为准。\n" +
                    "\n" +
                    "# 2) 语言与沟通风格\n" +
                    "- 默认使用中文（可切换繁体中文/英文）。\n" +
                    "- 简洁、专业、逐步引导。\n" +
                    "- 对关键信息使用“复述确认”，避免歧义（股票代码、数量、价格、币种）。\n" +
                    "\n" +
                    "# 3) 下单前必须收集字段（缺一不可）\n" +
                    "请逐项收集并校验：\n" +
                    "1. account_id（交易账户）\n" +
                    "2. market（固定为 HK）\n" +
                    "3. symbol（股票代码，如 0700.HK 或 0700）\n" +
                    "4. side（固定为 BUY）\n" +
                    "5. quantity（买入股数，正整数）\n" +
                    "6. order_type（LIMIT / MARKET，若系统不支持市价则提示改限价）\n" +
                    "7. limit_price（限价单必填）\n" +
                    "8. time_in_force（DAY / GTC 等，以系统支持为准）\n" +
                    "9. currency（默认 HKD）\n" +
                    "10. client_confirmation（客户明确确认语句）\n" +
                    "\n" +
                    "# 4) 风控与合规校验（调用工具）\n" +
                    "在展示最终确认前，必须执行：\n" +
                    "- 账户状态校验：是否可交易、是否冻结、是否具备港股权限\n" +
                    "- 资金校验：可用购买力是否充足（含预估费用）\n" +
                    "- 标的校验：代码是否有效、是否停牌/限制买入\n" +
                    "- 交易规则校验：最小交易单位/手数、价格精度、交易时段\n" +
                    "- 合规校验：黑名单/受限名单/监管限制\n" +
                    "\n" +
                    "若任何校验失败：\n" +
                    "- 明确说明失败原因\n" +
                    "- 给出可执行替代方案（如调整数量/价格）\n" +
                    "- 不得进入下单提交步骤\n" +
                    "\n" +
                    "# 5) 强制二次确认机制（Two-step Confirmation）\n" +
                    "在提交前，必须先输出“订单预览”，格式如下：\n" +
                    "---\n" +
                    "订单预览（请确认）\n" +
                    "- 账户：{account_id}\n" +
                    "- 市场：HK\n" +
                    "- 股票：{symbol}\n" +
                    "- 方向：BUY\n" +
                    "- 数量：{quantity}\n" +
                    "- 类型：{order_type}\n" +
                    "- 价格：{limit_price 或 市价}\n" +
                    "- 有效期：{time_in_force}\n" +
                    "- 币种：{currency}\n" +
                    "- 预估成交金额：{estimated_amount}\n" +
                    "- 预估费用：{estimated_fees}\n" +
                    "- 预计总扣款：{estimated_total}\n" +
                    "---\n" +
                    "\n" +
                    "然后要求客户输入明确确认语句之一：\n" +
                    "- “确认下单”\n" +
                    "- “CONFIRM BUY”\n" +
                    "\n" +
                    "只有收到明确确认语句，才可调用下单接口。\n" +
                    "\n" +
                    "# 6) 工具调用规范（示例）\n" +
                    "你可使用以下工具（名称按实际系统替换）：\n" +
                    "1. get_quote(symbol, market)\n" +
                    "2. check_account(account_id)\n" +
                    "3. check_buying_power(account_id, estimated_total)\n" +
                    "4. validate_order(order_payload)\n" +
                    "5. place_order(order_payload)\n" +
                    "6. get_order_status(order_id)\n" +
                    "\n" +
                    "下单请求统一输出 JSON（不要夹杂自然语言）：\n" +
                    "{\n" +
                    "  \"account_id\": \"...\",\n" +
                    "  \"market\": \"HK\",\n" +
                    "  \"symbol\": \"...\",\n" +
                    "  \"side\": \"BUY\",\n" +
                    "  \"quantity\": 0,\n" +
                    "  \"order_type\": \"LIMIT\",\n" +
                    "  \"limit_price\": 0,\n" +
                    "  \"time_in_force\": \"DAY\",\n" +
                    "  \"currency\": \"HKD\",\n" +
                    "  \"client_confirmation_text\": \"确认下单\"\n" +
                    "}\n" +
                    "\n" +
                    "# 7) 成功/失败后的响应模板\n" +
                    "- 成功：\n" +
                    "  - 返回 order_id、提交时间、订单状态（如 NEW/PENDING）\n" +
                    "  - 提示“最终成交以交易所与券商回报为准”\n" +
                    "- 失败：\n" +
                    "  - 返回错误码、错误原因、可操作建议\n" +
                    "  - 示例：余额不足 -> 建议减少数量或调整价格\n" +
                    "\n" +
                    "# 8) 异常与安全策略\n" +
                    "- 若用户信息不完整：只提问缺失字段，不做猜测。\n" +
                    "- 若用户意图不清：先澄清再执行。\n" +
                    "- 若检测到高风险或违规请求：拒绝执行，并说明原因。\n" +
                    "- 全程保留审计字段：用户原话、确认文本、时间戳、关键参数。\n" +
                    "\n" +
                    "# 9) 固定免责声明（每次预览和结果后附带）\n" +
                    "“本助手仅提供交易执行协助，不构成任何投资建议。市场有风险，投资需谨慎。实际规则、费用与成交结果以券商及交易所回报为准。”");
            messages.add(systemMsg);

            List<JSONObject> history = getStepfunHistory(sessionId);
            synchronized (history) {
                for (JSONObject msg : history) messages.add(msg);
            }

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userText);
            messages.add(userMsg);
            body.put("messages", messages);
            log.info("【Stepfun 上下文】携带历史 {} 条，sessionId={}", history.size(), sessionId);

            byte[] bodyBytes = body.toJSONString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) { os.write(bodyBytes); }

            StringBuilder sentenceBuffer = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled.get()) { log.info("【Stepfun 已取消】sessionId={}", sessionId); break; }
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) break;
                    JSONObject chunk = JSONObject.parseObject(data);
                    JSONArray choices = chunk.getJSONArray("choices");
                    if (choices == null || choices.isEmpty()) continue;
                    JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                    if (delta == null) continue;
                    String content = delta.getString("content");
                    if (content == null || content.isEmpty()) continue;

                    final String td = content;
                    safeSend(clientSession, new JSONObject() {{ put("type", "llm.text.delta"); put("text", td); }});
                    fullReply.append(content);
                    sentenceBuffer.append(content);
                    enqueueIfSentence(sentenceBuffer, sentenceQueue, sessionId);
                }
            }

            String remaining = sentenceBuffer.toString().trim();
            if (!remaining.isEmpty() && !cancelled.get()) sentenceQueue.put(remaining);

            safeSend(clientSession, new JSONObject() {{ put("type", "llm.text.done"); }});
            log.info("【Stepfun 推理完成】sessionId={}", sessionId);

            if (!cancelled.get()) {
                appendStepfunHistory(sessionId, userText, fullReply.toString());
            } else {
                log.info("【Stepfun 历史跳过】本轮被打断，sessionId={}", sessionId);
            }
        } catch (Exception e) {
            if (!cancelled.get()) log.error("【Stepfun 错误】sessionId={}", sessionId, e);
        } finally {
            try { sentenceQueue.put(QUEUE_DONE); } catch (InterruptedException ignored) {}
        }
    }

    private void enqueueIfSentence(StringBuilder buf, LinkedBlockingQueue<String> queue, String sessionId) throws InterruptedException {
        int breakIdx = -1;
        for (int i = buf.length() - 1; i >= 0; i--) {
            if (SENTENCE_DELIMITERS.indexOf(buf.charAt(i)) >= 0) { breakIdx = i; break; }
        }
        if (breakIdx >= 0) {
            String sentence = buf.substring(0, breakIdx + 1).trim();
            buf.delete(0, breakIdx + 1);
            if (!sentence.isEmpty()) {
                log.info("【断句入队】{}，sessionId={}", sentence, sessionId);
                queue.put(sentence);
            }
        }
    }

    public void playGreeting(String sessionId, WebSocketSession clientSession,
                              Consumer<TtsWebSocketClient> onTtsClient) {
        String greeting = "欢迎来到股票交易助手。请告诉我您想买入还是卖出哪只股票，以及委托数量和价格。我会继续引导您完成下单。";
        safeSend(clientSession, new JSONObject() {{ put("type", "llm.text.delta"); put("text", greeting); }});
        safeSend(clientSession, new JSONObject() {{ put("type", "llm.text.done"); }});
        executor.submit(() -> {
            try {
                CountDownLatch latch = new CountDownLatch(1);
                TtsWebSocketClient tts = new TtsWebSocketClient(
                        new URI(ttsUrl), apiKey, ttsVoice, greeting, clientSession);
                tts.setOnDone(latch::countDown);
                onTtsClient.accept(tts);
                tts.connect();
                latch.await(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("【开场白错误】sessionId={}", sessionId, e);
            }
        });
    }

    private void appendStepfunHistory(String sessionId, String userText, String assistantText) {
        List<JSONObject> history = getStepfunHistory(sessionId);
        synchronized (history) {
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userText);
            JSONObject assistantMsg = new JSONObject();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", assistantText);
            history.add(userMsg);
            history.add(assistantMsg);
            while (history.size() > MAX_HISTORY) { history.remove(0); history.remove(0); }
        }
        log.info("【Stepfun 历史更新】当前 {} 条，sessionId={}", getStepfunHistory(sessionId).size(), sessionId);
    }

    private List<JSONObject> getStepfunHistory(String sessionId) {
        return stepfunHistoryMap.computeIfAbsent(sessionId, k -> new ArrayList<>());
    }

    public void clearState(String sessionId) {
        sierraStateMap.remove(sessionId);
        stepfunHistoryMap.remove(sessionId);
        providerMap.remove(sessionId);
        log.info("【State 清除】session 已清除，sessionId={}", sessionId);
    }

    private void safeSend(WebSocketSession session, JSONObject payload) {
        if (!session.isOpen()) return;
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(payload.toJSONString()));
            } catch (Exception e) {
                log.error("【错误】向前端发送消息失败，sessionId={}", session.getId(), e);
            }
        }
    }
}
