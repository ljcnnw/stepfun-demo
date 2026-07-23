package org.example.asr.handler;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.example.asr.client.AliyunAsrClient;
import org.example.asr.client.FanoAsrClient;
import org.example.asr.client.StepfunWsClient;
import org.example.asr.client.VolcAsrClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ASR 基准测试 WebSocket 处理器（/asr-bench 端点）
 *
 * 支持两种测试模式：
 * - ptt (Push-to-Talk): 前端按住说话，松开后 bench.stop 触发识别
 * - call: 持续监听，切句完全由各流式 ASR 自身 VAD 控制；
 *         FANO 例外，因其为批量 HTTP API，仍用 RMS VAD 决定切句时机
 *
 * 同一份音频广播给所有选中的 ASR，公平对比识别结果和延迟。
 *
 * Call 模式下的切句机制：
 * - 流式 ASR（Stepfun/Aliyun/Volc）：音频持续发送，由 ASR 自身 VAD 决定句子边界
 *   - onSpeechConfirmed → 通知前端 bench.vad speech_start（仅首次），初始化句子计时
 *   - onUserSpeechCompleted → 通知前端 bench.done，触发 FANO 批量识别
 * - FANO：用 RMS VAD 检测说话/静音，缓冲音频，检测到句尾后批量发送识别
 */
@Component
public class BenchWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BenchWebSocketHandler.class);

    // FANO 专用 RMS VAD 参数（仅用于 call 模式下 FANO 的切句）
    private static final double FANO_VAD_RMS_THRESHOLD = 0.015;
    private static final int FANO_VAD_SPEECH_FRAMES = 2;
    private static final int FANO_VAD_SILENCE_FRAMES = 10;

    // FANO 批量识别线程池
    private final ExecutorService fanoExecutor = Executors.newCachedThreadPool();

    // Stepfun ASR
    @Value("${stepfun.api.key}")
    private String stepfunApiKey;

    @Value("${stepfun.asr.url}")
    private String stepfunAsrUrl;

    // 阿里云 ASR
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

    // 火山引擎 ASR
    @Value("${volc.asr.url}")
    private String volcAsrUrl;

    @Value("${volc.asr.app-key}")
    private String volcAsrAppKey;

    @Value("${volc.asr.access-key}")
    private String volcAsrAccessKey;

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

    // FANO ASR
    @Value("${fano.asr.url}")
    private String fanoAsrUrl;

    @Value("${fano.asr.token}")
    private String fanoAsrToken;

    // 每个 session 的状态
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    // ── 内部状态类 ────────────────────────────────────────────────────

    private static class SessionState {
        String mode = "ptt"; // "ptt" | "call"
        List<String> providers = new ArrayList<>();
        WebSocketSession session; // back-reference for reconnect

        // 各 provider 的 ASR client
        StepfunWsClient stepfunClient;
        AliyunAsrClient aliyunClient;
        VolcAsrClient volcClient;
        FanoAsrClient fanoClient;

        // 每句测试的开始时间（毫秒）
        long sentenceStartMs = 0;
        // 当前句子 ID
        String currentItemId = null;
        // call 模式下是否已通知前端 speech_start（避免多个 ASR 重复触发）
        boolean speechStartNotified = false;
        // call 模式下是否已切句（任一 ASR 完成即触发，防止重复 speech_end）
        boolean sentenceEnded = false;

        // FANO 专用 RMS VAD 状态（call 模式）
        boolean fanoVadSpeaking = false;
        int fanoVadSpeechFrames = 0;
        int fanoVadSilenceFrames = 0;
        // FANO 音频缓冲（说话期间积累，句尾发送批量识别）
        List<byte[]> fanoBuffer = new ArrayList<>();

        // 各 provider 的独立句子开始时间（ms），用于避免多 provider 竞争导致 0ms
        Map<String, Long> providerStartMs = new ConcurrentHashMap<>();
        // 各 provider 最终结果
        Map<String, String> transcriptMap = new ConcurrentHashMap<>();
        // 已完成的 provider 集合
        Set<String> doneProviders = Collections.synchronizedSet(new HashSet<>());
    }

    // ── WebSocket 生命周期 ────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("【Bench 连接建立】sessionId={}", session.getId());
        SessionState s = new SessionState();
        s.session = session;
        sessions.put(session.getId(), s);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JSONObject json = JSONObject.parseObject(message.getPayload());
            String type = json.getString("type");

            if ("bench.config".equals(type)) {
                handleConfig(session, json);
                return;
            }

            if ("bench.call.start".equals(type)) {
                handleCallStart(session);
                return;
            }

            if ("bench.call.stop".equals(type)) {
                handleCallStop(session);
                return;
            }

            if ("bench.stop".equals(type)) {
                handleStop(session);
            }
        } catch (Exception e) {
            log.error("【Bench】处理文本消息异常，sessionId={}", session.getId(), e);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SessionState state = sessions.get(session.getId());
        if (state == null || state.providers.isEmpty()) return;

        ByteBuffer payload = message.getPayload();
        byte[] pcm = new byte[payload.remaining()];
        payload.get(pcm);

        if ("call".equals(state.mode)) {
            handleCallModeFrame(session, state, pcm);
        } else {
            // PTT 模式：直接广播给所有 ASR
            broadcastAudio(state, pcm);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("【Bench 连接断开】sessionId={}", session.getId());
        cleanupSession(session.getId());
    }

    // ── 配置处理 ─────────────────────────────────────────────────────

    private void handleConfig(WebSocketSession session, JSONObject json) {
        SessionState state = sessions.get(session.getId());
        if (state == null) return;

        // 清理旧连接
        cleanupAsrClients(state);
        state.providers.clear();
        state.transcriptMap.clear();
        state.doneProviders.clear();
        state.sentenceStartMs = 0;
        state.currentItemId = null;
        state.speechStartNotified = false;

        state.mode = json.getString("mode") != null ? json.getString("mode") : "ptt";

        JSONArray providersArr = json.getJSONArray("providers");
        if (providersArr != null) {
            for (int i = 0; i < providersArr.size(); i++) {
                state.providers.add(providersArr.getString(i));
            }
        }

        log.info("【Bench 配置】mode={}, providers={}, sessionId={}", state.mode, state.providers, session.getId());

        // PTT 模式下提前连接流式 ASR（PTT 模式没有 call.start/stop，连接复用整个会话）
        // Call 模式懒连接：等 bench.call.start 收到后再建立连接，避免空闲超时
        if ("ptt".equals(state.mode)) {
            for (String provider : state.providers) {
                try {
                    connectProvider(session, state, provider);
                } catch (Exception e) {
                    log.error("【Bench】连接 {} 失败，sessionId={}", provider, session.getId(), e);
                }
            }
        } else {
            // call 模式：只初始化 FANO 客户端（HTTP，无连接超时问题）
            if (state.providers.contains("fano")) {
                state.fanoClient = new FanoAsrClient(fanoAsrUrl, fanoAsrToken);
            }
        }

        // 通知前端配置成功
        sendToClient(session, buildJson("bench.ready", null));
    }

    private void connectProvider(WebSocketSession session, SessionState state, String provider) throws Exception {
        switch (provider) {
            case "stepfun":
                StepfunWsClient stepfun = new StepfunWsClient(new URI(stepfunAsrUrl), stepfunApiKey, session);
                stepfun.setListener(buildStreamingListener(session, state, "stepfun"));
                stepfun.connect();
                state.stepfunClient = stepfun;
                log.info("【Bench】Stepfun ASR 已连接，sessionId={}", session.getId());
                break;

            case "aliyun":
                AliyunAsrClient aliyun = new AliyunAsrClient(
                        aliyunAsrUrl,
                        aliyunAsrApiKey,
                        session,
                        aliyunAsrModel,
                        aliyunAsrSampleRate,
                        aliyunAsrLanguageHints);
                aliyun.setListener(buildStreamingListener(session, state, "aliyun"));
                aliyun.connect();
                state.aliyunClient = aliyun;
                log.info("【Bench】阿里云 ASR 已连接，sessionId={}", session.getId());
                break;

            case "volc":
                VolcAsrClient volc = new VolcAsrClient(
                        volcAsrUrl, volcAsrAppKey, volcAsrAccessKey, volcAsrResourceId, session,
                        volcModelName, volcEnableItn, volcEnablePunc, volcEnableDdc, volcEnableNonstream,
                        volcEndWindowSize, volcForceToSpeechTime, volcOutputZhVariant, volcEnableLid, volcResultType);
                volc.setListener(buildStreamingListener(session, state, "volc"));
                volc.connect();
                state.volcClient = volc;
                log.info("【Bench】火山引擎 ASR 已连接，sessionId={}", session.getId());
                break;

            case "fano":
                // FANO 是批量 HTTP API，只需创建 client 实例，无需预先连接
                state.fanoClient = new FanoAsrClient(fanoAsrUrl, fanoAsrToken);
                log.info("【Bench】FANO ASR 客户端已创建，sessionId={}", session.getId());
                break;
        }
    }

    /**
     * 为流式 ASR（Stepfun/Aliyun/Volc）构建事件监听器。
     *
     * call 模式下的切句机制：
     * - onSpeechConfirmed：ASR 自身 VAD 检测到说话开始/首字 → 通知前端 speech_start（仅首次）
     * - onUserSpeechCompleted：ASR 识别一句完成 → 推送结果，触发 FANO 批量识别，重置下一句状态
     */
    private StepfunWsClient.AsrEventListener buildStreamingListener(WebSocketSession session, SessionState state, String provider) {
        // 每个 provider 独立保存本句的 itemId 和开始时间快照
        // 解决问题：call 模式下其他 provider 先完成并重置 state 后，豆包等慢速 provider 仍能
        // 正确找到 itemId 和开始时间，不会永远卡在"识别中"
        final long[] localStartMs = {0};
        final String[] localItemId = {null};

        return new StepfunWsClient.AsrEventListener() {
            @Override
            public void onSpeechConfirmed() {
                long now = System.currentTimeMillis();

                // 每个 provider 独立记录自己的开始时间（避免多 provider 竞争导致 0ms）
                state.providerStartMs.putIfAbsent(provider, now);
                // 同步到闭包局部变量，确保 state 被 clear 后仍能访问
                localStartMs[0] = state.providerStartMs.get(provider);

                // call 模式：首个 ASR 检测到说话时通知前端 speech_start
                if ("call".equals(state.mode) && !state.speechStartNotified) {
                    state.speechStartNotified = true;
                    state.sentenceStartMs = now;
                    state.currentItemId = "bench_" + now;
                    log.info("【Bench call】{} 触发 speech_start，sessionId={}", provider, session.getId());
                    JSONObject vadEvt = new JSONObject();
                    vadEvt.put("type", "bench.vad");
                    vadEvt.put("event", "speech_start");
                    vadEvt.put("item_id", state.currentItemId);
                    sendToClient(session, vadEvt.toJSONString());
                }

                // 缓存当前句子 itemId 到局部变量（state.currentItemId 可能被其他线程清空）
                if (state.currentItemId != null) {
                    localItemId[0] = state.currentItemId;
                }

                // 通知前端该 provider 进入识别状态
                JSONObject statusEvt = new JSONObject();
                statusEvt.put("type", "bench.status");
                statusEvt.put("provider", provider);
                statusEvt.put("status", "recognizing");
                statusEvt.put("item_id", localItemId[0]);
                sendToClient(session, statusEvt.toJSONString());
            }

            @Override
            public void onTranscriptDelta(String delta) {
                if (delta == null || delta.isEmpty()) return;
                // 优先用局部缓存的 itemId，防止 state 被清空后为 null
                String itemId = localItemId[0] != null ? localItemId[0] : state.currentItemId;
                JSONObject evt = new JSONObject();
                evt.put("type", "bench.transcript.delta");
                evt.put("provider", provider);
                evt.put("delta", delta);
                evt.put("item_id", itemId);
                sendToClient(session, evt.toJSONString());
            }

            @Override
            public void onAsrError(String errMsg) {
                log.warn("【Bench】{} ASR 错误：{}，sessionId={}", provider, errMsg, session.getId());
                JSONObject evt = new JSONObject();
                evt.put("type", "bench.status");
                evt.put("provider", provider);
                evt.put("status", "error");
                evt.put("item_id", localItemId[0]);
                evt.put("message", errMsg);
                sendToClient(session, evt.toJSONString());
            }

            @Override
            public void onUserSpeechCompleted(String transcript) {
                // 优先使用局部缓存的开始时间和 itemId（state 可能已被其他 provider 重置）
                long providerStart = localStartMs[0] > 0 ? localStartMs[0]
                        : state.providerStartMs.getOrDefault(provider, state.sentenceStartMs);
                String itemId = localItemId[0] != null ? localItemId[0] : state.currentItemId;

                long totalMs = providerStart > 0 ? System.currentTimeMillis() - providerStart : 0;

                log.info("【Bench】{} 识别完成：{}，耗时 {}ms，sessionId={}", provider, transcript, totalMs, session.getId());

                state.transcriptMap.put(provider, transcript);
                state.doneProviders.add(provider);

                JSONObject evt = new JSONObject();
                evt.put("type", "bench.done");
                evt.put("provider", provider);
                evt.put("transcript", transcript);
                evt.put("total_ms", totalMs);
                evt.put("item_id", itemId);
                sendToClient(session, evt.toJSONString());

                // 通知前端该 provider 完成
                JSONObject statusEvt = new JSONObject();
                statusEvt.put("type", "bench.status");
                statusEvt.put("provider", provider);
                statusEvt.put("status", "done");
                statusEvt.put("item_id", itemId);
                sendToClient(session, statusEvt.toJSONString());

                // call 模式：流式 ASR 完成一句时，触发 FANO 批量识别（如果 FANO 也在测试中）
                if ("call".equals(state.mode) && state.providers.contains("fano")
                        && state.fanoClient != null && !state.doneProviders.contains("fano")) {
                    long streamingDoneCount = state.doneProviders.stream()
                            .filter(p -> !p.equals("fano"))
                            .count();
                    if (streamingDoneCount == 1) {
                        triggerFanoBatchRecognition(session, state);
                    }
                }

                // call 模式：任一流式 ASR 完成即切句（通知前端 speech_end），但不立刻清状态
                // 状态必须等所有 provider 都完成后再清，否则后续 provider 拿不到开始时间，导致 0ms
                if ("call".equals(state.mode) && !state.sentenceEnded) {
                    state.sentenceEnded = true;
                    log.info("【Bench call】{} 首个完成，切句，sessionId={}", provider, session.getId());
                    sendToClient(session, buildJson("bench.vad", "speech_end"));
                }

                // call 模式：所有 provider 都完成后才重置状态，等待下一句
                if ("call".equals(state.mode)) {
                    long nonFanoTotal = state.providers.stream().filter(p -> !p.equals("fano")).count();
                    long nonFanoDone = state.doneProviders.stream().filter(p -> !p.equals("fano")).count();
                    boolean fanoSelected = state.providers.contains("fano");
                    boolean fanoDone = !fanoSelected || state.doneProviders.contains("fano");
                    boolean allDone = (nonFanoDone >= nonFanoTotal) && fanoDone;
                    if (allDone) {
                        log.info("【Bench call】所有 provider 完成，重置状态，sessionId={}", session.getId());
                        state.currentItemId = null;
                        state.speechStartNotified = false;
                        state.sentenceEnded = false;
                        state.providerStartMs.clear();
                        state.transcriptMap.clear();
                        state.doneProviders.clear();
                        state.fanoBuffer.clear();
                        state.fanoVadSpeaking = false;
                        state.fanoVadSpeechFrames = 0;
                        state.fanoVadSilenceFrames = 0;
                        state.sentenceStartMs = 0;
                    }
                }
            }
        };
    }

    // ── 音频广播 ─────────────────────────────────────────────────────

    /**
     * PTT 模式：广播音频给所有 ASR（包括 FANO 缓冲）
     */
    private void broadcastAudio(SessionState state, byte[] pcm) {
        long now = System.currentTimeMillis();
        if (state.sentenceStartMs == 0) {
            state.sentenceStartMs = now;
            if (state.currentItemId == null) {
                state.currentItemId = "bench_" + now;
            }
            // PTT 模式：首帧音频时为所有 provider 同时记录开始时间
            for (String p : state.providers) {
                state.providerStartMs.putIfAbsent(p, now);
            }
        }

        for (String provider : state.providers) {
            sendAudioToProvider(state, provider, pcm);
        }
    }

    private void sendAudioToProvider(SessionState state, String provider, byte[] pcm) {
        try {
            switch (provider) {
                case "stepfun":
                    if (state.stepfunClient != null) state.stepfunClient.sendAudioFrame(pcm);
                    break;
                case "aliyun":
                    if (state.aliyunClient != null) state.aliyunClient.sendAudioFrame(pcm);
                    break;
                case "volc":
                    if (state.volcClient != null) state.volcClient.sendAudioFrame(pcm);
                    break;
                case "fano":
                    state.fanoBuffer.add(pcm);
                    break;
            }
        } catch (Exception e) {
            log.error("【Bench】向 {} 发送音频失败", provider, e);
        }
    }

    private void reconnectProvider(SessionState state, String provider) {
        try {
            connectProvider(state.session, state, provider);
        } catch (Exception e) {
            log.error("【Bench】重连 {} 失败，sessionId={}", provider, state.session.getId(), e);
        }
    }

    // ── PTT 停止 ──────────────────────────────────────────────────────

    private void handleStop(WebSocketSession session) {
        SessionState state = sessions.get(session.getId());
        if (state == null) return;

        log.info("【Bench PTT 停止】发送结束帧，sessionId={}", session.getId());

        // 向所有流式 ASR 发送结束信号
        if (state.volcClient != null) {
            state.volcClient.sendFinishFrame();
        }
        if (state.aliyunClient != null) {
            state.aliyunClient.sendFinishTask();
        }
        // Stepfun 不需要显式结束帧，VAD 自动检测

        // PTT 模式下触发 FANO 批量识别
        if (state.providers.contains("fano") && state.fanoClient != null && !state.fanoBuffer.isEmpty()) {
            triggerFanoBatchRecognition(session, state);
        }
    }

    // ── Call 模式开始/停止 ────────────────────────────────────────────

    private void handleCallStart(WebSocketSession session) {
        SessionState state = sessions.get(session.getId());
        if (state == null) return;

        log.info("【Bench Call 开始】建立流式 ASR 连接，sessionId={}", session.getId());

        // 强制清理旧连接并重置状态（处理上一轮 callStop 异步等待未完成的情况）
        cleanupAsrClients(state);
        resetCallState(state);

        // FANO 是 HTTP 客户端，cleanupAsrClients 会将其置 null，此处重新创建
        if (state.providers.contains("fano")) {
            state.fanoClient = new FanoAsrClient(fanoAsrUrl, fanoAsrToken);
        }

        // 建立流式 ASR 连接（此时用户已开始录音，连接建立后即可接收音频）
        for (String provider : state.providers) {
            if (!"fano".equals(provider)) {
                try {
                    connectProvider(session, state, provider);
                } catch (Exception e) {
                    log.error("【Bench】Call 模式连接 {} 失败，sessionId={}", provider, session.getId(), e);
                }
            }
        }
    }

    private void handleCallStop(WebSocketSession session) {
        SessionState state = sessions.get(session.getId());
        if (state == null) return;

        log.info("【Bench Call 停止】发送结束帧，等待 ASR 返回最后一句，sessionId={}", session.getId());

        // 先发结束帧，让 ASR 有机会返回最后一句识别结果
        try {
            if (state.volcClient != null && state.volcClient.isOpen()) {
                state.volcClient.sendFinishFrame();
            }
            if (state.aliyunClient != null && state.aliyunClient.isOpen()) {
                state.aliyunClient.sendFinishTask();
            }
            // Stepfun：发送 commit 强制提交缓冲区，触发 VAD 结束检测
            if (state.stepfunClient != null && state.stepfunClient.isOpen()) {
                state.stepfunClient.sendCommit();
            }
        } catch (Exception e) {
            log.warn("【Bench Call 停止】发送结束帧失败", e);
        }

        // 如果当前有未完成的句子（speechStartNotified 说明 VAD 已触发，但还没收到 done），
        // 等待最多 5s 让 ASR 返回结果；否则直接清理
        if (state.speechStartNotified && !state.doneProviders.containsAll(
                state.providers.stream().filter(p -> !"fano".equals(p)).collect(java.util.stream.Collectors.toList()))) {
            fanoExecutor.submit(() -> {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                log.info("【Bench Call 停止】超时等待结束，关闭连接，sessionId={}", session.getId());
                cleanupAsrClients(state);
                resetCallState(state);
            });
        } else {
            cleanupAsrClients(state);
            resetCallState(state);
        }
    }

    private void resetCallState(SessionState state) {

        // 重置句子状态
        state.sentenceStartMs = 0;
        state.currentItemId = null;
        state.speechStartNotified = false;
        state.sentenceEnded = false;
        state.transcriptMap.clear();
        state.doneProviders.clear();
        state.fanoBuffer.clear();
        state.fanoVadSpeaking = false;
        state.fanoVadSpeechFrames = 0;
        state.fanoVadSilenceFrames = 0;
    }


    // ── Call 模式音频处理 ─────────────────────────────────────────────

    /**
     * Call 模式：音频帧处理
     * - 直接广播给所有流式 ASR（由 ASR 自身 VAD 切句）
     * - 对 FANO 维护独立的 RMS VAD 缓冲
     */
    private void handleCallModeFrame(WebSocketSession session, SessionState state, byte[] pcm) {
        // 1. 直接广播给所有流式 ASR（Stepfun/Aliyun/Volc）
        for (String provider : state.providers) {
            if (!provider.equals("fano")) {
                sendAudioToProvider(state, provider, pcm);
            }
        }

        // 2. FANO 专用 RMS VAD：决定 FANO 的切句时机
        if (state.providers.contains("fano") && state.fanoClient != null) {
            handleFanoVad(session, state, pcm);
        }
    }

    /**
     * FANO 专用 RMS VAD（仅在 call 模式且选中 FANO 时使用）
     */
    private void handleFanoVad(WebSocketSession session, SessionState state, byte[] pcm) {
        double rms = calcRms(pcm);
        boolean isSpeech = rms >= FANO_VAD_RMS_THRESHOLD;

        if (!state.fanoVadSpeaking) {
            if (isSpeech) {
                state.fanoVadSpeechFrames++;
                if (state.fanoVadSpeechFrames >= FANO_VAD_SPEECH_FRAMES) {
                    state.fanoVadSpeaking = true;
                    state.fanoVadSilenceFrames = 0;
                    state.fanoVadSpeechFrames = 0;
                    state.fanoBuffer.clear();
                    log.info("【Bench FANO VAD】检测到说话开始，开始缓冲，sessionId={}", session.getId());
                }
            } else {
                state.fanoVadSpeechFrames = 0;
            }
        } else {
            // 说话中：积累到 fanoBuffer
            state.fanoBuffer.add(pcm);

            if (!isSpeech) {
                state.fanoVadSilenceFrames++;
                if (state.fanoVadSilenceFrames >= FANO_VAD_SILENCE_FRAMES) {
                    state.fanoVadSpeaking = false;
                    state.fanoVadSilenceFrames = 0;
                    log.info("【Bench FANO VAD】检测到说话结束，触发批量识别，sessionId={}", session.getId());
                    triggerFanoBatchRecognition(session, state);
                    state.fanoBuffer = new ArrayList<>();
                }
            } else {
                state.fanoVadSilenceFrames = 0;
            }
        }
    }

    // ── FANO 批量识别 ──────────────────────────────────────────────────

    private void triggerFanoBatchRecognition(WebSocketSession session, SessionState state) {
        if (state.fanoClient == null || state.fanoBuffer.isEmpty()) return;

        // 合并所有缓冲帧
        int totalBytes = state.fanoBuffer.stream().mapToInt(b -> b.length).sum();
        if (totalBytes == 0) return;

        byte[] pcmData = new byte[totalBytes];
        int offset = 0;
        for (byte[] chunk : state.fanoBuffer) {
            System.arraycopy(chunk, 0, pcmData, offset, chunk.length);
            offset += chunk.length;
        }

        final byte[] finalPcm = pcmData;
        final String itemId = state.currentItemId;
        final long startMs = state.sentenceStartMs;
        final FanoAsrClient client = state.fanoClient;

        log.info("【Bench FANO】提交批量识别，pcm={}bytes，sessionId={}", totalBytes, session.getId());

        // 通知前端 FANO 开始识别
        JSONObject recognizingEvt = new JSONObject();
        recognizingEvt.put("type", "bench.status");
        recognizingEvt.put("provider", "fano");
        recognizingEvt.put("status", "recognizing");
        recognizingEvt.put("item_id", itemId);
        sendToClient(session, recognizingEvt.toJSONString());

        fanoExecutor.submit(() -> {
            long fanoStartMs = startMs > 0 ? startMs : System.currentTimeMillis();
            String transcript = client.recognize(finalPcm);
            if (transcript == null) transcript = "";

            long totalMs = System.currentTimeMillis() - fanoStartMs;
            log.info("【Bench FANO】识别完成：{}，耗时 {}ms，sessionId={}", transcript, totalMs, session.getId());

            state.doneProviders.add("fano");

            JSONObject evt = new JSONObject();
            evt.put("type", "bench.done");
            evt.put("provider", "fano");
            evt.put("transcript", transcript);
            evt.put("total_ms", totalMs);
            evt.put("item_id", itemId);
            sendToClient(session, evt.toJSONString());

            JSONObject doneStatusEvt = new JSONObject();
            doneStatusEvt.put("type", "bench.status");
            doneStatusEvt.put("provider", "fano");
            doneStatusEvt.put("status", "done");
            doneStatusEvt.put("item_id", itemId);
            sendToClient(session, doneStatusEvt.toJSONString());
        });
    }

    // ── 工具方法 ──────────────────────────────────────────────────────

    private String buildJson(String type, String event) {
        JSONObject obj = new JSONObject();
        obj.put("type", type);
        if (event != null) obj.put("event", event);
        return obj.toJSONString();
    }

    private void sendToClient(WebSocketSession session, String json) {
        if (!session.isOpen()) return;
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(json));
            } catch (Exception e) {
                log.error("【Bench】向前端发送消息失败，sessionId={}", session.getId(), e);
            }
        }
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

    private void cleanupAsrClients(SessionState state) {
        try {
            if (state.stepfunClient != null && !state.stepfunClient.isClosed()) state.stepfunClient.close();
            if (state.aliyunClient != null && !state.aliyunClient.isClosed()) {
                state.aliyunClient.sendFinishTask();
                state.aliyunClient.close();
            }
            if (state.volcClient != null && !state.volcClient.isClosed()) {
                state.volcClient.sendFinishFrame();
                state.volcClient.close();
            }
        } catch (Exception e) {
            log.error("【Bench】清理 ASR client 异常", e);
        }
        state.stepfunClient = null;
        state.aliyunClient = null;
        state.volcClient = null;
        state.fanoClient = null;
        state.fanoBuffer.clear();
    }

    private void cleanupSession(String sessionId) {
        SessionState state = sessions.remove(sessionId);
        if (state != null) cleanupAsrClients(state);
    }
}
