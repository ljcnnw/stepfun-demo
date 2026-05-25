package org.example.asr.handler;

import com.alibaba.fastjson2.JSONObject;
import org.example.asr.client.FanoAsrClient;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AsrWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AsrWebSocketHandler.class);

    // 本地 VAD 参数
    // 连续 8 帧（800ms）RMS 低于阈值则判定说话结束
    private static final int VAD_SILENCE_FRAMES = 8;
    // 连续 2 帧（200ms）RMS 超过阈值则判定说话开始
    private static final int VAD_SPEECH_FRAMES = 2;
    // RMS 阈值（与前端 worklet 的 MIC_GAIN=3 对应，原始 RMS 约 0.01 即触发）
    private static final double VAD_RMS_THRESHOLD = 0.015;
    // 最大缓冲 30 秒（16kHz, 16bit = 32000 bytes/s）
    private static final int MAX_BUFFER_BYTES = 32000 * 30;

    @Value("${stepfun.api.key}")
    private String apiKey;

    @Value("${stepfun.asr.url}")
    private String asrUrl;

    @Value("${fano.asr.url}")
    private String fanoAsrUrl;

    @Value("${fano.asr.token}")
    private String fanoAsrToken;

    @Autowired
    private LlmService llmService;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final Map<String, StepfunWsClient> asrClients = new ConcurrentHashMap<>();
    private final Map<String, List<TtsWebSocketClient>> ttsClients = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> llmCancelled = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> isPlaying = new ConcurrentHashMap<>();

    // per-session ASR provider（stepfun / fano）
    private final Map<String, String> asrProviderMap = new ConcurrentHashMap<>();

    // FANO 本地 VAD 状态
    private final Map<String, List<byte[]>> fanoAudioBuffer = new ConcurrentHashMap<>();
    private final Map<String, Integer> fanoSilenceFrames = new ConcurrentHashMap<>();
    private final Map<String, Integer> fanoSpeechFrames = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> fanoSpeaking = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("【连接建立】前端已连接，sessionId={}", session.getId());
        ttsClients.put(session.getId(), new CopyOnWriteArrayList<>());
        isPlaying.put(session.getId(), new AtomicBoolean(false));
        asrProviderMap.put(session.getId(), "stepfun");

        initFanoVadState(session.getId());
        connectStepfunAsr(session);

        llmService.playGreeting(session.getId(), session, tts -> {
            ttsClients.get(session.getId()).add(tts);
            isPlaying.get(session.getId()).set(true);
        });
    }

    private void connectStepfunAsr(WebSocketSession session) throws Exception {
        StepfunWsClient asr = new StepfunWsClient(new URI(asrUrl), apiKey, session);
        asr.setListener(new StepfunWsClient.AsrEventListener() {
            @Override
            public void onSpeechConfirmed() {
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
                triggerLlm(session, transcript);
            }
        });
        asr.connect();
        asrClients.put(session.getId(), asr);
        log.info("【ASR 连接】已向 Stepfun ASR 发起连接，sessionId={}", session.getId());
    }

    private void initFanoVadState(String sessionId) {
        fanoAudioBuffer.put(sessionId, new ArrayList<>());
        fanoSilenceFrames.put(sessionId, 0);
        fanoSpeechFrames.put(sessionId, 0);
        fanoSpeaking.put(sessionId, new AtomicBoolean(false));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        ByteBuffer payload = message.getPayload();
        byte[] pcmBytes = new byte[payload.remaining()];
        payload.get(pcmBytes);

        String provider = asrProviderMap.getOrDefault(session.getId(), "stepfun");
        if ("fano".equals(provider)) {
            handleFanoAudioFrame(session, pcmBytes);
        } else {
            StepfunWsClient asr = asrClients.get(session.getId());
            if (asr != null) asr.sendAudioFrame(pcmBytes);
        }
    }

    /**
     * 本地 VAD 处理：累积音频帧，检测说话开始/结束，结束后调用 FANO 识别。
     * pcmBytes 为 Int16 PCM，每帧 100ms（1600 采样 = 3200 bytes）。
     */
    private void handleFanoAudioFrame(WebSocketSession session, byte[] pcmBytes) {
        String sessionId = session.getId();

        double rms = calcRms(pcmBytes);
        boolean isSpeech = rms >= VAD_RMS_THRESHOLD;

        AtomicBoolean speaking = fanoSpeaking.get(sessionId);
        if (speaking == null) return;

        if (!speaking.get()) {
            // 等待说话开始
            if (isSpeech) {
                int cnt = fanoSpeechFrames.merge(sessionId, 1, Integer::sum);
                if (cnt >= VAD_SPEECH_FRAMES) {
                    speaking.set(true);
                    fanoSilenceFrames.put(sessionId, 0);
                    fanoSpeechFrames.put(sessionId, 0);
                    log.info("【FANO VAD】检测到说话开始，sessionId={}", sessionId);
                    // 打断当前 TTS（如果在播放）
                    AtomicBoolean playing = isPlaying.get(sessionId);
                    if (playing != null && playing.get()) interruptAll(session);
                }
            } else {
                fanoSpeechFrames.put(sessionId, 0);
            }
        } else {
            // 说话中，累积音频
            List<byte[]> buf = fanoAudioBuffer.get(sessionId);
            if (buf != null) {
                int totalBytes = buf.stream().mapToInt(b -> b.length).sum();
                if (totalBytes < MAX_BUFFER_BYTES) buf.add(pcmBytes);
            }

            if (!isSpeech) {
                int cnt = fanoSilenceFrames.merge(sessionId, 1, Integer::sum);
                if (cnt >= VAD_SILENCE_FRAMES) {
                    // 说话结束，取出缓冲发给 FANO
                    speaking.set(false);
                    fanoSilenceFrames.put(sessionId, 0);
                    List<byte[]> frames = fanoAudioBuffer.get(sessionId);
                    fanoAudioBuffer.put(sessionId, new ArrayList<>());
                    log.info("【FANO VAD】检测到说话结束，缓冲帧数={}，sessionId={}", frames.size(), sessionId);
                    submitFanoRecognize(session, frames);
                }
            } else {
                fanoSilenceFrames.put(sessionId, 0);
            }
        }
    }

    private void submitFanoRecognize(WebSocketSession session, List<byte[]> frames) {
        executor.submit(() -> {
            try {
                // 合并所有帧为一个字节数组
                int total = frames.stream().mapToInt(b -> b.length).sum();
                byte[] allPcm = new byte[total];
                int offset = 0;
                for (byte[] frame : frames) {
                    System.arraycopy(frame, 0, allPcm, offset, frame.length);
                    offset += frame.length;
                }

                FanoAsrClient fano = new FanoAsrClient(fanoAsrUrl, fanoAsrToken);
                String transcript = fano.recognize(allPcm);
                if (transcript == null || transcript.trim().isEmpty()) {
                    log.info("【FANO ASR】识别结果为空，跳过，sessionId={}", session.getId());
                    return;
                }
                // 模拟发送前端转录完成事件
                if (session.isOpen()) {
                    JSONObject msg = new JSONObject();
                    msg.put("type", "conversation.item.input_audio_transcription.completed");
                    msg.put("item_id", "fano_" + System.currentTimeMillis());
                    msg.put("transcript", transcript);
                    synchronized (session) {
                        session.sendMessage(new TextMessage(msg.toJSONString()));
                    }
                }
                triggerLlm(session, transcript);
            } catch (Exception e) {
                log.error("【FANO ASR】识别提交异常，sessionId={}", session.getId(), e);
            }
        });
    }

    private void triggerLlm(WebSocketSession session, String transcript) {
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
                    log.info("【LLM Provider 切换】sessionId={}，provider={}", session.getId(), provider);
                }
                return;
            }

            if ("asr.provider".equals(type)) {
                String provider = json.getString("provider");
                if ("stepfun".equals(provider) || "fano".equals(provider)) {
                    asrProviderMap.put(session.getId(), provider);
                    log.info("【ASR Provider 切换】sessionId={}，provider={}", session.getId(), provider);
                    // 切换到 fano 时重置本地 VAD 状态
                    if ("fano".equals(provider)) {
                        initFanoVadState(session.getId());
                    }
                }
                return;
            }

            // 其他消息透传给 Stepfun ASR
            StepfunWsClient asr = asrClients.get(session.getId());
            if (asr != null && asr.isOpen()) {
                asr.send(message.getPayload());
            }
        } catch (Exception e) {
            log.error("【错误】处理文本消息异常，sessionId={}", session.getId(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("【连接断开】前端已断开，sessionId={}，status={}", session.getId(), status);
        cleanupSession(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("【传输错误】sessionId={}", session.getId(), exception);
        cleanupSession(session.getId());
    }

    private void interruptAll(WebSocketSession session) {
        String sessionId = session.getId();

        AtomicBoolean cancelled = llmCancelled.remove(sessionId);
        if (cancelled != null) cancelled.set(true);

        List<TtsWebSocketClient> list = ttsClients.get(sessionId);
        if (list != null) {
            for (TtsWebSocketClient tts : list) {
                if (!tts.isClosed()) tts.close();
            }
            list.clear();
        }

        AtomicBoolean playing = isPlaying.get(sessionId);
        if (playing != null) playing.set(false);

        // 重置 FANO VAD 说话状态（避免打断后缓冲残留）
        AtomicBoolean speaking = fanoSpeaking.get(sessionId);
        if (speaking != null) speaking.set(false);
        fanoAudioBuffer.put(sessionId, new ArrayList<>());
        fanoSilenceFrames.put(sessionId, 0);

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

    private void cleanupSession(String sessionId) {
        AtomicBoolean cancelled = llmCancelled.remove(sessionId);
        if (cancelled != null) cancelled.set(true);

        List<TtsWebSocketClient> list = ttsClients.remove(sessionId);
        if (list != null) {
            for (TtsWebSocketClient tts : list) {
                if (!tts.isClosed()) tts.close();
            }
        }

        isPlaying.remove(sessionId);
        asrProviderMap.remove(sessionId);
        fanoAudioBuffer.remove(sessionId);
        fanoSilenceFrames.remove(sessionId);
        fanoSpeechFrames.remove(sessionId);
        fanoSpeaking.remove(sessionId);

        StepfunWsClient asr = asrClients.remove(sessionId);
        if (asr != null && !asr.isClosed()) asr.close();

        llmService.clearState(sessionId);

        log.info("【资源清理】session 资源已全部释放，sessionId={}", sessionId);
    }

    /**
     * 计算 Int16 PCM 字节数组的 RMS 值（0~1 范围）。
     */
    private double calcRms(byte[] pcmBytes) {
        int samples = pcmBytes.length / 2;
        if (samples == 0) return 0;
        double sum = 0;
        for (int i = 0; i < samples; i++) {
            // 小端序 Int16
            short sample = (short) ((pcmBytes[i * 2] & 0xFF) | (pcmBytes[i * 2 + 1] << 8));
            double normalized = sample / 32768.0;
            sum += normalized * normalized;
        }
        return Math.sqrt(sum / samples);
    }
}
