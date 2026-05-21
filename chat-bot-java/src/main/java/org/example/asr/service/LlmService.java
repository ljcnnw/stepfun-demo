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
            systemMsg.put("content", "你是一个友好的语音助手，回答简洁自然，适合语音播报，不要使用emoji。");
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
