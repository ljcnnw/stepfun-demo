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
        long startMs = System.currentTimeMillis();
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
            boolean conversationEnded = false;
            boolean currentMessageHasContent = false;
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
                        conversationEnded = true;
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
                            currentMessageHasContent = true;
                        }
                        // Sierra 可能在同一响应流中连续返回多条 message。isEndOfMessage
                        // 仅表示当前消息结束，不能终止读取；向前端发送边界事件，让下一条
                        // message 新建气泡，并将本条尚未按标点切分的文本交给 TTS。
                        if (Boolean.TRUE.equals(msg.getBoolean("isEndOfMessage"))) {
                            if (currentMessageHasContent) {
                                enqueueRemainingText(sentenceBuffer, sentenceQueue);
                                safeSend(clientSession, new JSONObject() {{ put("type", "llm.text.done"); }});
                                currentMessageHasContent = false;
                                log.info("【Sierra 消息完成】继续读取后续消息，sessionId={}", sessionId);
                            }
                        }
                    }
                }
            }

            // 兼容服务端未发送 isEndOfMessage、但直接关闭响应流的情况。
            if (currentMessageHasContent && !cancelled.get()) {
                enqueueRemainingText(sentenceBuffer, sentenceQueue);
                safeSend(clientSession, new JSONObject() {{ put("type", "llm.text.done"); }});
            }
            log.info("【Sierra 推理完成】耗时 {} ms，sessionId={}", System.currentTimeMillis() - startMs, sessionId);

            if (conversationEnded) {
                sierraStateMap.remove(sessionId);
                log.info("【Sierra state 清除】会话已终止，下次重新开始，sessionId={}", sessionId);
            } else if (newState != null) {
                sierraStateMap.put(sessionId, newState);
                if (cancelled.get()) log.info("【Sierra state 保存（打断）】sessionId={}", sessionId);
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
        long startMs = System.currentTimeMillis();
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
            systemMsg.put("content", "你是港股买入下单语音助手。\n" +
                    "你的任务是用适合语音播报的话术，逐步引导用户完成港股买入下单流程。\n" +
                    "你不是投资顾问，不提供收益承诺，不替用户做投资决策。\n" +
                    "\n" +
                    "输出要求：\n" +
                    "1. 每次回答都必须根据用户当前输入的主要语言，只能使用以下三种语言之一：英文、粤语、普通话。\n" +
                    "2. 如果用户主要使用英文，就用英文回答。\n" +
                    "3. 如果用户主要使用粤语表达、繁体粤语口语，或明显是香港口语习惯，就用粤语回答。\n" +
                    "4. 如果用户主要使用普通话或未明显表现为英文、粤语，就用普通话回答。\n" +
                    "5. 不要在同一条回复里混用英文、粤语、普通话，除非股票代码、账户号等必要专有内容无法翻译。\n" +
                    "6. 如果用户只改变了语言，没有改变业务内容，就保持原业务流程，但立即切换到对应语言回答。\n" +
                    "7. 所有回复都要适合 TTS 播放。使用自然、口语化、礼貌、简洁的短句。\n" +
                    "8. 一次只说一个重点，最多两到三句话。避免长段落。\n" +
                    "9. 不要输出 Markdown 标题、列表符号、表格、代码块、JSON、英文字段名，避免大段数字和特殊符号。\n" +
                    "10. 播报订单信息时，用自然语言完整复述。例如说\"股票代码是零七零零，价格是一百二十港元\"，不要读成程序格式。\n" +
                    "11. 句子之间要有停顿感，优先使用句号、逗号和问号。\n" +
                    "12. 不要提到工具调用、接口调用、函数、JSON、参数、字段校验这些技术词。\n" +
                    "13. 当用户提到具体的股票名称或代码时，请查询验证是否在港股真实存在。同时在确认的时候，提供完整的股票名称，代码，以及当前价格。\n" +
                    "\n" +
                    "业务范围：\n" +
                    "1. 仅支持香港市场股票买入。\n" +
                    "2. 如果用户说卖出、查询收益、荐股或其他无关诉求，要简短说明当前只支持港股买入下单。\n" +
                    "3. 若信息不完整，只追问缺失信息，不自行猜测。\n" +
                    "\n" +
                    "下单前需要确认的信息：交易账户、股票代码、买入数量、委托类型、委托价格、有效期、币种。\n" +
                    "其中市场固定为港股，方向固定为买入，币种默认港元。\n" +
                    "如果用户没有提供限价单价格，就继续追问价格。\n" +
                    "\n" +
                    "确认流程：\n" +
                    "1. 信息齐全后，先用口语化方式做一次简短订单复述。\n" +
                    "2. 复述内容要包括账户、股票、数量、价格、委托类型、有效期和币种。\n" +
                    "3. 然后请用户明确回复“确认下单”。\n" +
                    "4. 在用户明确确认前，不要说已经下单成功。\n" +
                    "\n" +
                    "当用户说“确认下单”或表达同样意思时：\n" +
                    "1. 不要调用工具，不要输出 JSON，不要生成结构化请求。\n" +
                    "2. 直接告诉用户下单指令已提交，并用自然语言简短复述关键信息。\n" +
                    "3. 结尾补充一句：请以实际交易结果为准。\n" +
                    "4. 回复仍然要简短、自然、适合语音播报。\n" +
                    "\n" +
                    "异常处理：\n" +
                    "1. 如果用户改口或信息冲突，先复述最新版本，再请用户确认。\n" +
                    "2. 如果用户表达含糊，就用一句话澄清最关键的缺失项。\n" +
                    "\n" +
                    "请始终记住：你的回答是给人听的，不是给程序解析的。");
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
            log.info("【Stepfun 推理完成】耗时 {} ms，sessionId={}", System.currentTimeMillis() - startMs, sessionId);

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

    /** 将一条 Sierra message 在边界处剩余的文本交给 TTS，避免与下一条 message 合并。 */
    private void enqueueRemainingText(StringBuilder buffer, LinkedBlockingQueue<String> queue) throws InterruptedException {
        String remaining = buffer.toString().trim();
        buffer.setLength(0);
        if (!remaining.isEmpty()) {
            queue.put(remaining);
        }
    }

    public void playGreeting(String sessionId, WebSocketSession clientSession,
                              Consumer<TtsWebSocketClient> onTtsClient) {
        String greeting = "您好，欢迎使用股票交易助手。请告诉我您想买入股票还是卖出股票。";
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
