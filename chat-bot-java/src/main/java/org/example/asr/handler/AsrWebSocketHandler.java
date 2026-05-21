package org.example.asr.handler;

import com.alibaba.fastjson2.JSONObject;
import org.example.asr.client.StepfunWsClient;
import org.example.asr.client.TtsWebSocketClient;
import org.example.asr.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 前端 WebSocket 连接的主处理器。
 * 每个前端连接对应一个 session，负责：
 *   1. 建立并管理与 Stepfun ASR 的上游连接
 *   2. 将前端发来的 PCM 音频帧转发给 ASR
 *   3. 用户说完话后触发 LLM + TTS 流程
 *   4. 处理 VAD 打断指令，终止当前播放
 */
@Component
public class AsrWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AsrWebSocketHandler.class);

    @Value("${stepfun.api.key}")
    private String apiKey;

    @Value("${stepfun.asr.url}")
    private String asrUrl;

    @Autowired
    private LlmService llmService;

    // 每个 session 对应一个 ASR 上游连接
    private final Map<String, StepfunWsClient> asrClients = new ConcurrentHashMap<>();
    // 每个 session 当前正在播放的 TTS 连接列表（一次 LLM 响应可能产生多个分句 TTS）
    private final Map<String, List<TtsWebSocketClient>> ttsClients = new ConcurrentHashMap<>();
    // 当前 LLM+TTS 流程的取消标志，设为 true 可中断整个流程
    private final Map<String, AtomicBoolean> llmCancelled = new ConcurrentHashMap<>();
    // 后端维护的播放状态，用于判断 VAD 打断是否需要执行
    private final Map<String, AtomicBoolean> isPlaying = new ConcurrentHashMap<>();

    /**
     * 前端 WebSocket 连接建立时触发。
     * 初始化该 session 的状态，并建立与 Stepfun ASR 的上游连接。
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("【连接建立】前端已连接，sessionId={}", session.getId());
        ttsClients.put(session.getId(), new CopyOnWriteArrayList<>());
        isPlaying.put(session.getId(), new AtomicBoolean(false));

        // 创建 ASR 客户端，连接 Stepfun ASR 服务
        StepfunWsClient asr = new StepfunWsClient(new URI(asrUrl), apiKey, session);
        asr.setListener(new StepfunWsClient.AsrEventListener() {
            @Override
            public void onSpeechConfirmed() {
                // ASR 收到首个 delta，确认用户真实在说话（非噪音误触发）
                // 只有当前正在播放 TTS 时才执行打断，避免正常说话完成后误取消刚启动的 LLM
                AtomicBoolean playing = isPlaying.get(session.getId());
                if (playing != null && playing.get()) {
                    log.info("【说话确认】正在播放 TTS，执行打断，sessionId={}", session.getId());
                    interruptAll(session);
                } else {
                    log.info("【说话确认】当前未在播放，跳过打断，sessionId={}", session.getId());
                }
            }

            @Override
            public void onUserSpeechCompleted(String transcript) {
                log.info("【用户说话完成】识别结果：{}，sessionId={}", transcript, session.getId());
                // 取消上一轮未完成的 LLM，避免多轮并发时互相干扰
                AtomicBoolean prev = llmCancelled.remove(session.getId());
                if (prev != null) prev.set(true);

                AtomicBoolean cancelled = llmService.streamChat(
                        transcript, session.getId(), session,
                        tts -> {
                            ttsClients.get(session.getId()).add(tts);
                            isPlaying.get(session.getId()).set(true);
                        });
                llmCancelled.put(session.getId(), cancelled);
            }
        });
        asr.connect();
        asrClients.put(session.getId(), asr);
        log.info("【ASR 连接】已向 Stepfun ASR 发起连接，sessionId={}", session.getId());

        // 连接建立后直接播放开场白
        llmService.playGreeting(session.getId(), session, tts -> {
            ttsClients.get(session.getId()).add(tts);
            isPlaying.get(session.getId()).set(true);
        });
    }

    /**
     * 接收前端发来的二进制音频帧（PCM 格式），转发给 ASR 服务。
     */
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        StepfunWsClient asr = asrClients.get(session.getId());
        if (asr == null) return;
        // 将 ByteBuffer 转为字节数组后发送给 ASR
        ByteBuffer payload = message.getPayload();
        byte[] pcmBytes = new byte[payload.remaining()];
        payload.get(pcmBytes);
        asr.sendAudioFrame(pcmBytes);
    }

    /**
     * 接收前端发来的文本控制消息。
     * 目前支持：tts.interrupt（VAD 触发打断）
     * 其余消息透传给 ASR 服务。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JSONObject json = JSONObject.parseObject(message.getPayload());
            String type = json.getString("type");

            if ("tts.interrupt".equals(type)) {
                AtomicBoolean playing = isPlaying.get(session.getId());
                if (playing != null && playing.get()) {
                    log.info("【VAD 打断】前端触发打断，正在执行，sessionId={}", session.getId());
                    interruptAll(session);
                } else {
                    log.info("【VAD 打断】忽略打断请求（当前未在播放），sessionId={}", session.getId());
                }
                return;
            }

            if ("llm.provider".equals(type)) {
                String provider = json.getString("provider");
                if ("sierra".equals(provider) || "stepfun".equals(provider)) {
                    llmService.setProvider(session.getId(), provider);
                    log.info("【Provider 切换】sessionId={}，provider={}", session.getId(), provider);
                }
                return;
            }

            // 其他文本消息（如 ASR 控制指令）透传给 ASR 服务
            StepfunWsClient asr = asrClients.get(session.getId());
            if (asr != null && asr.isOpen()) {
                asr.send(message.getPayload());
            }
        } catch (Exception e) {
            log.error("【错误】处理文本消息异常，sessionId={}", session.getId(), e);
        }
    }

    /**
     * 前端 WebSocket 连接断开时触发，清理该 session 的所有资源。
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("【连接断开】前端已断开，sessionId={}，status={}", session.getId(), status);
        cleanupSession(session.getId());
    }

    /**
     * WebSocket 传输层发生错误时触发，同样清理资源。
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("【传输错误】sessionId={}", session.getId(), exception);
        cleanupSession(session.getId());
    }

    /**
     * 打断当前 session 的所有进行中流程：
     *   1. 取消 LLM 流式读取
     *   2. 关闭所有 TTS WebSocket 连接
     *   3. 重置播放状态
     *   4. 通知前端立刻停止音频播放
     */
    private void interruptAll(WebSocketSession session) {
        String sessionId = session.getId();

        // 取消 LLM 流，LLM 线程和 TTS 消费线程会在下次检查时退出
        AtomicBoolean cancelled = llmCancelled.remove(sessionId);
        if (cancelled != null) cancelled.set(true);

        // 关闭所有正在进行的 TTS WebSocket 连接
        List<TtsWebSocketClient> list = ttsClients.get(sessionId);
        if (list != null) {
            for (TtsWebSocketClient tts : list) {
                if (!tts.isClosed()) tts.close();
            }
            list.clear();
        }

        // 重置播放状态
        AtomicBoolean playing = isPlaying.get(sessionId);
        if (playing != null) playing.set(false);

        // 主动通知前端立刻停止音频播放，不等待 tts.audio.done 事件
        if (session.isOpen()) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "tts.interrupted");
                synchronized (session) {
                    session.sendMessage(new TextMessage(msg.toJSONString()));
                }
            } catch (Exception e) {
                log.error("【错误】发送 tts.interrupted 失败，sessionId={}", sessionId, e);
            }
        }

        log.info("【打断完成】已终止所有 LLM/TTS 流程，sessionId={}", sessionId);
    }

    /**
     * 清理 session 的所有资源，连接断开时调用。
     */
    private void cleanupSession(String sessionId) {
        // 取消 LLM 流
        AtomicBoolean cancelled = llmCancelled.remove(sessionId);
        if (cancelled != null) cancelled.set(true);

        // 关闭所有 TTS 连接
        List<TtsWebSocketClient> list = ttsClients.remove(sessionId);
        if (list != null) {
            for (TtsWebSocketClient tts : list) {
                if (!tts.isClosed()) tts.close();
            }
        }

        isPlaying.remove(sessionId);

        // 关闭 ASR 上游连接
        StepfunWsClient asr = asrClients.remove(sessionId);
        if (asr != null && !asr.isClosed()) asr.close();

        // 清除该 session 的 Sierra state
        llmService.clearState(sessionId);

        log.info("【资源清理】session 资源已全部释放，sessionId={}", sessionId);
    }
}
