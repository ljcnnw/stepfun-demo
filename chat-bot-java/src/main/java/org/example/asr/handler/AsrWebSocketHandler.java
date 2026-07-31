package org.example.asr.handler;

import com.alibaba.fastjson2.JSONObject;
import org.example.asr.client.AliyunAsrClient;
import org.example.asr.client.FanoAsrClient;
import org.example.asr.client.StepfunWsClient;
import org.example.asr.client.TtsWebSocketClient;
import org.example.asr.client.VolcAsrClient;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AsrWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AsrWebSocketHandler.class);

    // FANO 本地 VAD 参数
    private static final int VAD_SILENCE_FRAMES = 10;
    private static final int VAD_SPEECH_FRAMES = 2;
    private static final double VAD_RMS_THRESHOLD = 0.015;
    private static final int MAX_BUFFER_BYTES = 32000 * 30;
    private static final int DEFAULT_VENDOR_VAD_SILENCE_MS = 1000;
    private static final int MIN_VENDOR_VAD_SILENCE_MS = 200;
    private static final int MAX_VENDOR_VAD_SILENCE_MS = 5000;
    private static final long ASR_CONNECT_TIMEOUT_MS = 5000;

    @Value("${stepfun.api.key}")
    private String apiKey;

    @Value("${stepfun.asr.url}")
    private String asrUrl;

    @Value("${fano.asr.url}")
    private String fanoAsrUrl;

    @Value("${fano.asr.token}")
    private String fanoAsrToken;

    @Value("${aliyun.asr.url}")
    private String aliyunAsrUrl;

    @Value("${aliyun.asr.api-key}")
    private String aliyunAsrApiKey;

    @Value("${aliyun.asr.model:paraformer-realtime-v2}")
    private String aliyunAsrModel;

    @Value("${aliyun.asr.sample-rate:16000}")
    private int aliyunAsrSampleRate;

    @Value("${aliyun.asr.language-hints:}")
    private String aliyunAsrLanguageHints;

    @Value("${volc.asr.url}")
    private String volcAsrUrl;

    @Value("${volc.asr.api-key:}")
    private String volcAsrApiKey;

    @Value("${volc.asr.resource-id}")
    private String volcAsrResourceId;

    @Value("${volc.asr.request.model-name}")
    private String volcModelName;

    @Value("${volc.asr.request.enable-itn}")
    private boolean volcEnableItn;

    @Value("${volc.asr.request.enable-punc}")
    private boolean volcEnablePunc;

    @Value("${volc.asr.request.enable-ddc}")
    private boolean volcEnableDdc;

    @Value("${volc.asr.request.enable-nonstream}")
    private boolean volcEnableNonstream;

    @Value("${volc.asr.request.end-window-size}")
    private int volcEndWindowSize;

    @Value("${volc.asr.request.force-to-speech-time}")
    private int volcForceToSpeechTime;

    @Value("${volc.asr.request.output-zh-variant:}")
    private String volcOutputZhVariant;

    @Value("${volc.asr.request.enable-lid}")
    private boolean volcEnableLid;

    @Value("${volc.asr.request.result-type}")
    private String volcResultType;

    @Value("${volc.asr.request.boosting-table-id:}")
    private String volcBoostingTableId;

    @Autowired
    private LlmService llmService;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final Map<String, StepfunWsClient> asrClients = new ConcurrentHashMap<>();
    private final Map<String, AliyunAsrClient> aliyunAsrClients = new ConcurrentHashMap<>();
    private final Map<String, VolcAsrClient> volcAsrClients = new ConcurrentHashMap<>();
    private final Map<String, List<TtsWebSocketClient>> ttsClients = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> llmCancelled = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> isPlaying = new ConcurrentHashMap<>();
    private final Map<String, String> asrProviderMap = new ConcurrentHashMap<>();
    // 通话页可按会话覆盖的厂商 VAD 判停时长（FANO 使用本地固定 VAD，不在此配置范围）。
    private final Map<String, Integer> stepfunVadSilenceMs = new ConcurrentHashMap<>();
    private final Map<String, Integer> aliyunVadSilenceMs = new ConcurrentHashMap<>();
    private final Map<String, Integer> volcVadSilenceMs = new ConcurrentHashMap<>();
    // 每个前端会话独立加锁，避免多个音频包同时触发 ASR 重连并互相覆盖客户端。
    private final Map<String, Object> asrConnectionLocks = new ConcurrentHashMap<>();

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
        stepfunVadSilenceMs.put(session.getId(), DEFAULT_VENDOR_VAD_SILENCE_MS);
        aliyunVadSilenceMs.put(session.getId(), DEFAULT_VENDOR_VAD_SILENCE_MS);
        volcVadSilenceMs.put(session.getId(), DEFAULT_VENDOR_VAD_SILENCE_MS);

        initFanoVadState(session.getId());
        connectStepfunAsr(session);

        llmService.playGreeting(session.getId(), session, tts -> {
            ttsClients.get(session.getId()).add(tts);
            isPlaying.get(session.getId()).set(true);
        });
    }

    private StepfunWsClient.AsrEventListener buildListener(WebSocketSession session) {
        return new StepfunWsClient.AsrEventListener() {
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
        };
    }

    private void connectStepfunAsr(WebSocketSession session) throws Exception {
        StepfunWsClient asr = new StepfunWsClient(new URI(asrUrl), apiKey, session);
        asr.setVadSilenceDurationMs(getVadDuration(stepfunVadSilenceMs, session.getId()));
        asr.setListener(buildListener(session));
        if (!asr.connectBlocking(ASR_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) || !asr.isOpen()) {
            asr.close();
            throw new IllegalStateException("Stepfun ASR 连接未在超时时间内建立");
        }
        asrClients.put(session.getId(), asr);
        log.info("【ASR 连接】Stepfun ASR 已就绪，sessionId={}", session.getId());
    }

    private void connectAliyunAsr(WebSocketSession session) throws Exception {
        AliyunAsrClient asr = new AliyunAsrClient(
                aliyunAsrUrl,
                aliyunAsrApiKey,
                session,
                aliyunAsrModel,
                aliyunAsrSampleRate,
                aliyunAsrLanguageHints,
                getVadDuration(aliyunVadSilenceMs, session.getId()));
        asr.setListener(buildListener(session));
        if (!asr.connectBlocking(ASR_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) || !asr.isOpen()) {
            asr.close();
            throw new IllegalStateException("阿里云 ASR 连接未在超时时间内建立");
        }
        aliyunAsrClients.put(session.getId(), asr);
        log.info("【ASR 连接】阿里云 ASR 已就绪，sessionId={}", session.getId());
    }

    private void connectVolcAsr(WebSocketSession session) throws Exception {
        VolcAsrClient asr = new VolcAsrClient(
                volcAsrUrl, volcAsrApiKey, volcAsrResourceId, session,
                volcModelName, volcEnableItn, volcEnablePunc, volcEnableDdc, volcEnableNonstream,
                volcEndWindowSize, volcForceToSpeechTime, volcOutputZhVariant, volcEnableLid, volcResultType,
                getVadDuration(volcVadSilenceMs, session.getId()), volcBoostingTableId);
        asr.setListener(buildListener(session));
        if (!asr.connectBlocking(ASR_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) || !asr.isReady()) {
            asr.close();
            throw new IllegalStateException("火山引擎 ASR 连接未在超时时间内建立");
        }
        volcAsrClients.put(session.getId(), asr);
        log.info("【ASR 连接】火山引擎 ASR 已就绪，sessionId={}", session.getId());
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
        if (!ensureAsrClientReady(session, provider)) {
            log.warn("[ASR 音频丢弃] ASR 子连接未就绪·sessionId={}, provider={}", session.getId(), provider);
            return;
        }

        if ("fano".equals(provider)) {
            handleFanoAudioFrame(session, pcmBytes);
        } else if ("aliyun".equals(provider)) {
            AliyunAsrClient asr = aliyunAsrClients.get(session.getId());
            if (asr != null) asr.sendAudioFrame(pcmBytes);
        } else if ("volc".equals(provider)) {
            VolcAsrClient asr = volcAsrClients.get(session.getId());
            if (asr != null) asr.sendAudioFrame(pcmBytes);
        } else {
            StepfunWsClient asr = asrClients.get(session.getId());
            if (asr != null) asr.sendAudioFrame(pcmBytes);
        }
    }

    private boolean ensureAsrClientReady(WebSocketSession session, String provider) {
        String sessionId = session.getId();
        synchronized (asrConnectionLocks.computeIfAbsent(sessionId, key -> new Object())) {
            try {
                if ("fano".equals(provider)) {
                    return true;
                }

                if ("aliyun".equals(provider)) {
                    AliyunAsrClient asr = aliyunAsrClients.get(sessionId);
                    if (asr != null && asr.isOpen()) return true;
                    reconnectAliyunAsr(session);
                    asr = aliyunAsrClients.get(sessionId);
                    return asr != null && asr.isOpen();
                }

                if ("volc".equals(provider)) {
                    VolcAsrClient asr = volcAsrClients.get(sessionId);
                    if (asr != null && asr.isReady()) return true;
                    reconnectVolcAsr(session);
                    asr = volcAsrClients.get(sessionId);
                    return asr != null && asr.isReady();
                }

                StepfunWsClient asr = asrClients.get(sessionId);
                if (asr != null && asr.isOpen()) return true;

                StepfunWsClient old = asrClients.remove(sessionId);
                if (old != null && !old.isClosed()) {
                    old.close();
                }
                connectStepfunAsr(session);
                asr = asrClients.get(sessionId);
                return asr != null && asr.isOpen();
            } catch (Exception e) {
                log.error("[ASR 自愈重连失败] sessionId={}, provider={}", sessionId, provider, e);
                return false;
            }
        }
    }

    private void handleFanoAudioFrame(WebSocketSession session, byte[] pcmBytes) {
        String sessionId = session.getId();
        double rms = calcRms(pcmBytes);
        boolean isSpeech = rms >= VAD_RMS_THRESHOLD;

        AtomicBoolean speaking = fanoSpeaking.get(sessionId);
        if (speaking == null) return;

        if (!speaking.get()) {
            if (isSpeech) {
                int cnt = fanoSpeechFrames.merge(sessionId, 1, Integer::sum);
                if (cnt >= VAD_SPEECH_FRAMES) {
                    speaking.set(true);
                    fanoSilenceFrames.put(sessionId, 0);
                    fanoSpeechFrames.put(sessionId, 0);
                    log.info("【FANO VAD】检测到说话开始，sessionId={}", sessionId);
                    AtomicBoolean playing = isPlaying.get(sessionId);
                    if (playing != null && playing.get()) interruptAll(session);
                }
            } else {
                fanoSpeechFrames.put(sessionId, 0);
            }
        } else {
            List<byte[]> buf = fanoAudioBuffer.get(sessionId);
            if (buf != null) {
                int totalBytes = buf.stream().mapToInt(b -> b.length).sum();
                if (totalBytes < MAX_BUFFER_BYTES) buf.add(pcmBytes);
            }
            if (!isSpeech) {
                int cnt = fanoSilenceFrames.merge(sessionId, 1, Integer::sum);
                if (cnt >= VAD_SILENCE_FRAMES) {
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
                if ("stepfun".equals(provider) || "fano".equals(provider) || "aliyun".equals(provider) || "volc".equals(provider)) {
                    String prev = asrProviderMap.put(session.getId(), provider);
                    log.info("【ASR Provider 切换】sessionId={}，{} -> {}", session.getId(), prev, provider);
                    if ("fano".equals(provider)) {
                        initFanoVadState(session.getId());
                    }
                    if ("aliyun".equals(provider)) {
                        reconnectAliyunAsr(session);
                    }
                    if ("volc".equals(provider)) {
                        reconnectVolcAsr(session);
                    }
                }
                return;
            }

            if ("asr.vad.config".equals(type)) {
                String provider = json.getString("provider");
                Integer requested = json.getInteger("silenceDurationMs");
                if (requested == null || !("stepfun".equals(provider) || "aliyun".equals(provider) || "volc".equals(provider))) {
                    return;
                }
                int durationMs = normalizeVadDuration(requested);
                String sessionId = session.getId();
                if ("stepfun".equals(provider)) {
                    stepfunVadSilenceMs.put(sessionId, durationMs);
                    StepfunWsClient asr = asrClients.get(sessionId);
                    if (asr != null) asr.setVadSilenceDurationMs(durationMs);
                } else if ("aliyun".equals(provider)) {
                    int previous = getVadDuration(aliyunVadSilenceMs, sessionId);
                    aliyunVadSilenceMs.put(sessionId, durationMs);
                    if (durationMs != previous && "aliyun".equals(asrProviderMap.get(sessionId))) reconnectAliyunAsr(session);
                } else {
                    int previous = getVadDuration(volcVadSilenceMs, sessionId);
                    volcVadSilenceMs.put(sessionId, durationMs);
                    if (durationMs != previous && "volc".equals(asrProviderMap.get(sessionId))) reconnectVolcAsr(session);
                }
                log.info("【ASR VAD 配置更新】sessionId={}，provider={}，silenceDurationMs={}", sessionId, provider, durationMs);
                return;
            }

            if ("conversation.item.input_text.submit".equals(type)) {
                String rawText = json.getString("text");
                String transcript = rawText == null ? "" : rawText.trim();
                if (transcript.isEmpty()) {
                    log.warn("[文本提交忽略] text 为空，sessionId={}", session.getId());
                    return;
                }

                AtomicBoolean playing = isPlaying.get(session.getId());
                if (playing != null && playing.get()) {
                    log.info("[文本提交] 检测到正在播放，先执行打断，sessionId={}", session.getId());
                    interruptAll(session);
                }

                String itemId = json.getString("item_id");
                if (session.isOpen()) {
                    JSONObject ack = new JSONObject();
                    ack.put("type", "conversation.item.input_text.completed");
                    ack.put("item_id", (itemId == null || itemId.trim().isEmpty()) ? ("txt_" + System.currentTimeMillis()) : itemId);
                    ack.put("transcript", transcript);
                    synchronized (session) {
                        session.sendMessage(new TextMessage(ack.toJSONString()));
                    }
                }

                triggerLlm(session, transcript);
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
        stepfunVadSilenceMs.remove(sessionId);
        aliyunVadSilenceMs.remove(sessionId);
        volcVadSilenceMs.remove(sessionId);
        fanoAudioBuffer.remove(sessionId);
        fanoSilenceFrames.remove(sessionId);
        fanoSpeechFrames.remove(sessionId);
        fanoSpeaking.remove(sessionId);
        asrConnectionLocks.remove(sessionId);

        StepfunWsClient asr = asrClients.remove(sessionId);
        if (asr != null && !asr.isClosed()) asr.close();

        AliyunAsrClient aliyun = aliyunAsrClients.remove(sessionId);
        if (aliyun != null && !aliyun.isClosed()) {
            aliyun.sendFinishTask();
            aliyun.close();
        }

        llmService.clearState(sessionId);

        log.info("【资源清理】session 资源已全部释放，sessionId={}", sessionId);
    }

    private void reconnectAliyunAsr(WebSocketSession session) {
        String sessionId = session.getId();
        synchronized (asrConnectionLocks.computeIfAbsent(sessionId, key -> new Object())) {
            AliyunAsrClient old = aliyunAsrClients.remove(sessionId);
            if (old != null && !old.isClosed()) {
                old.sendFinishTask();
                old.close();
            }
            try {
                connectAliyunAsr(session);
            } catch (Exception e) {
                log.error("【阿里云 ASR】建立连接失败，sessionId={}", sessionId, e);
            }
        }
    }

    private void reconnectVolcAsr(WebSocketSession session) {
        String sessionId = session.getId();
        synchronized (asrConnectionLocks.computeIfAbsent(sessionId, key -> new Object())) {
            VolcAsrClient old = volcAsrClients.remove(sessionId);
            if (old != null && !old.isClosed()) {
                old.sendFinishFrame();
                old.close();
            }
            try {
                connectVolcAsr(session);
            } catch (Exception e) {
                log.error("【火山引擎 ASR】建立连接失败，sessionId={}", sessionId, e);
            }
        }
    }

    private int getVadDuration(Map<String, Integer> values, String sessionId) {
        return values.getOrDefault(sessionId, DEFAULT_VENDOR_VAD_SILENCE_MS);
    }

    private int normalizeVadDuration(int value) {
        return Math.max(MIN_VENDOR_VAD_SILENCE_MS, Math.min(MAX_VENDOR_VAD_SILENCE_MS, value));
    }

    private double calcRms(byte[] pcmBytes) {
        int samples = pcmBytes.length / 2;
        if (samples == 0) return 0;
        double sum = 0;
        for (int i = 0; i < samples; i++) {
            short sample = (short) ((pcmBytes[i * 2] & 0xFF) | (pcmBytes[i * 2 + 1] << 8));
            double normalized = sample / 32768.0;
            sum += normalized * normalized;
        }
        return Math.sqrt(sum / samples);
    }
}
