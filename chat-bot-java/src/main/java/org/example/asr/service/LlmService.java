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

/**
 * LLM 推理 + TTS 合成的编排服务。
 *
 * 整体流程：
 *   1. LLM 线程：流式调用 Stepfun Chat API，按标点断句，将句子放入 sentenceQueue
 *   2. TTS 线程：从 sentenceQueue 取句子，串行创建 TTS 连接播放，用 CountDownLatch 等待每句播完后再播下一句
 *   3. 调用方持有 cancelled 标志，设为 true 可随时中断两个线程
 *   4. 每个 session 维护独立的对话历史，支持多轮上下文
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    // 断句标点，遇到这些字符时将缓冲区内容作为一句送入 TTS
    private static final String SENTENCE_DELIMITERS = "。！？!?…";
    // 队列结束哨兵，LLM 线程结束时放入，通知 TTS 线程退出
    private static final String QUEUE_DONE = "__DONE__";
    // 每个 session 保留的最大历史轮数（user + assistant 各算一条）
    private static final int MAX_HISTORY = 50;

    @Value("${stepfun.api.key}")
    private String apiKey;

    @Value("${stepfun.llm.url}")
    private String llmUrl;

    @Value("${stepfun.llm.model}")
    private String llmModel;

    @Value("${stepfun.tts.url}")
    private String ttsUrl;

    @Value("${stepfun.tts.voice}")
    private String ttsVoice;

    // 使用缓存线程池，LLM 和 TTS 各占一个线程，并发请求时自动扩展
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // 每个 session 独立的对话历史，存储 {role, content} 列表
    // 不包含 system 消息，system 消息每次请求时动态拼入
    private final Map<String, List<JSONObject>> historyMap = new ConcurrentHashMap<>();

    /**
     * 流式调用 LLM，断句后串行播放 TTS。
     * 本轮对话结束后将 user 和 assistant 消息追加到历史。
     *
     * @param userText      用户说的话（ASR 识别结果）
     * @param sessionId     前端 session ID，用于关联对话历史
     * @param clientSession 前端 WebSocket 连接，用于推送文字和音频
     * @param onTtsClient   TTS 连接创建时的回调，供 Handler 将连接加入管理列表
     * @return cancelled 标志，调用方设为 true 可中断整个流程
     */
    public AtomicBoolean streamChat(String userText, String sessionId,
                                    WebSocketSession clientSession,
                                    Consumer<TtsWebSocketClient> onTtsClient) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        // 生产者-消费者队列：LLM 线程生产断好的句子，TTS 线程串行消费
        LinkedBlockingQueue<String> sentenceQueue = new LinkedBlockingQueue<>();

        // ── LLM 流式读取线程 ──────────────────────────────────────────────
        executor.submit(() -> {
            // 用于收集本轮完整的 assistant 回复，结束后存入历史
            StringBuilder fullReply = new StringBuilder();
            try {
                log.info("【LLM 开始推理】userText={}，sessionId={}", userText, sessionId);

                // 建立 HTTP 连接，发送流式请求
                URL url = new URL(llmUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setDoOutput(true);
                conn.setReadTimeout(60000);
                conn.setConnectTimeout(10000);

                // 构建请求体：system + 历史上下文 + 本轮用户消息
                JSONObject body = new JSONObject();
                body.put("model", llmModel);
                body.put("stream", true);

                JSONArray messages = new JSONArray();

                // system 消息固定放第一条
                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", "你是一个友好的语音助手，回答简洁自然，适合语音播报，不要使用emoji。");
                messages.add(systemMsg);

                // 拼入历史上下文（最近 MAX_HISTORY 条）
                List<JSONObject> history = getHistory(sessionId);
                synchronized (history) {
                    for (JSONObject msg : history) {
                        messages.add(msg);
                    }
                }

                // 本轮用户消息
                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", userText);
                messages.add(userMsg);

                body.put("messages", messages);
                log.info("【LLM 上下文】携带历史 {} 条，sessionId={}", history.size(), sessionId);

                byte[] bodyBytes = body.toJSONString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyBytes);
                }

                // 逐行读取 SSE 流，解析 delta 内容
                StringBuilder sentenceBuffer = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (cancelled.get()) {
                            log.info("【LLM 已取消】中断流式读取，sessionId={}", sessionId);
                            break;
                        }
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

                        // 将文字增量推送给前端，前端实时展示 LLM 回复
                        safeSend(clientSession, new JSONObject() {{
                            put("type", "llm.text.delta");
                            put("text", content);
                        }});

                        fullReply.append(content);
                        sentenceBuffer.append(content);

                        // 检测断句标点，将完整句子放入队列供 TTS 消费
                        int breakIdx = -1;
                        for (int i = sentenceBuffer.length() - 1; i >= 0; i--) {
                            if (SENTENCE_DELIMITERS.indexOf(sentenceBuffer.charAt(i)) >= 0) {
                                breakIdx = i;
                                break;
                            }
                        }
                        if (breakIdx >= 0) {
                            String sentence = sentenceBuffer.substring(0, breakIdx + 1).trim();
                            sentenceBuffer.delete(0, breakIdx + 1);
                            if (!sentence.isEmpty()) {
                                log.info("【LLM 断句】入队：{}，sessionId={}", sentence, sessionId);
                                sentenceQueue.put(sentence);
                            }
                        }
                    }
                }

                // 将缓冲区中剩余的内容（最后一句可能没有标点结尾）也入队
                String remaining = sentenceBuffer.toString().trim();
                if (!remaining.isEmpty() && !cancelled.get()) {
                    log.info("【LLM 剩余内容】入队：{}，sessionId={}", remaining, sessionId);
                    sentenceQueue.put(remaining);
                }

                // 通知前端 LLM 推理完成
                safeSend(clientSession, new JSONObject() {{
                    put("type", "llm.text.done");
                }});
                log.info("【LLM 推理完成】sessionId={}", sessionId);

                // 只有正常完成（未被打断）才将本轮对话追加到历史
                if (!cancelled.get()) {
                    appendHistory(sessionId, userText, fullReply.toString());
                } else {
                    log.info("【历史跳过】本轮被打断，不保存到历史，sessionId={}", sessionId);
                }

            } catch (Exception e) {
                if (!cancelled.get()) {
                    log.error("【LLM 错误】流式读取异常，sessionId={}", sessionId, e);
                }
            } finally {
                // 无论正常结束还是异常，都放入哨兵通知 TTS 线程退出
                try { sentenceQueue.put(QUEUE_DONE); } catch (InterruptedException ignored) {}
            }
        });

        // ── TTS 串行消费线程 ──────────────────────────────────────────────
        executor.submit(() -> {
            try {
                while (true) {
                    // 从队列取句子，最多等待 30 秒（防止 LLM 线程异常时永久阻塞）
                    String sentence = sentenceQueue.poll(30, TimeUnit.SECONDS);
                    if (sentence == null || QUEUE_DONE.equals(sentence) || cancelled.get()) {
                        log.info("【TTS 队列结束】退出消费线程，sessionId={}", sessionId);
                        break;
                    }

                    log.info("【TTS 开始播放】sentence={}，sessionId={}", sentence, sessionId);

                    // 用 CountDownLatch 等待当前句 TTS 播完后再播下一句，保证串行顺序
                    CountDownLatch latch = new CountDownLatch(1);
                    TtsWebSocketClient tts = new TtsWebSocketClient(
                            new URI(ttsUrl), apiKey, ttsVoice, sentence, clientSession);
                    tts.setOnDone(latch::countDown);
                    // 通知 Handler 将此 TTS 连接加入管理列表，供打断时关闭
                    onTtsClient.accept(tts);
                    tts.connect();

                    // 每 50ms 检查一次是否被取消，避免长时间阻塞
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

    /**
     * 将本轮 user 和 assistant 消息追加到历史，超出上限时从头部删除最旧的一轮。
     */
    private void appendHistory(String sessionId, String userText, String assistantText) {
        List<JSONObject> history = getHistory(sessionId);
        synchronized (history) {
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userText);

            JSONObject assistantMsg = new JSONObject();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", assistantText);

            history.add(userMsg);
            history.add(assistantMsg);

            // 超出上限时删除最旧的一轮（两条）
            while (history.size() > MAX_HISTORY) {
                history.remove(0);
                history.remove(0);
            }
        }
        log.info("【历史更新】当前历史 {} 条，sessionId={}", getHistory(sessionId).size(), sessionId);
    }

    /**
     * 获取 session 的历史列表，不存在时自动创建。
     */
    private List<JSONObject> getHistory(String sessionId) {
        return historyMap.computeIfAbsent(sessionId, k -> new ArrayList<>());
    }

    /**
     * 清除 session 的对话历史，连接断开时调用。
     */
    public void clearHistory(String sessionId) {
        historyMap.remove(sessionId);
        log.info("【历史清除】session 对话历史已清除，sessionId={}", sessionId);
    }

    /**
     * 向前端发送 JSON 消息，加 synchronized 防止多线程并发写同一个 WebSocket 连接。
     */
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
