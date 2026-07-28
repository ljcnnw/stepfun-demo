package org.example.asr.client;

import com.alibaba.fastjson2.JSONObject;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 与 Stepfun ASR 服务的 WebSocket 客户端。
 * 负责：
 *   1. 连接建立后发送 session.update 配置音频格式和 VAD 参数
 *   2. 持续将前端发来的 PCM 音频帧以 base64 格式转发给 ASR
 *   3. 接收 ASR 事件并转发给前端，同时在识别完成时回调上层逻辑
 */
public class StepfunWsClient extends WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(StepfunWsClient.class);
    /**
     * Stepfun 流式 ASR 通过 prompt 注入领域热词，没有单独的 hotwords/weight 字段。
     * 此词表与证券交易通话场景保持一致，包含粤语读法、股票代码读法及交易流程用语。
     */
    private static final String SECURITIES_HOTWORD_PROMPT = ""
            + "这是香港证券交易客服场景。优先准确识别粤语、股票代码、证券名称、买入、卖出、沽货、股数、价格、港元等术语；保留用户原意。"
            + "热词包括：我要买股票、我要卖股票、买股票、卖股票、买入、卖出、查询购买力、查询持仓、查询报价、查询订单、查看订单、撤单、改单、修改订单、继续下单、结束、转人工、证券账户、股票交易权限、交易留痕、录音、同意、不同意、对、不对、是、不是、继续、确认提交、修改、验证码、短信验证码、一次性验证码、指纹验证、人脸验证、PU、HKIDR、香港投资者识别制度、港股、非港股、香港交易所、股票名称、股票代码、订单类型、市价单、限价单、继续市价单、改限价单、当日有效、撤销前有效、有效期、购买力、持仓、报价、可卖数量、可买数量、交易时间、竞价单、价格提醒、腾讯、腾讯控股、0700、腾讯 0700、腾讯零七零零、零七零零、港币、HKD、100股、200股、100、200、320港币、321港币、320、321、订单号、A123456、S654321、已受理、可用资金不足、可卖数量不足、交易受限、继续下一笔。"
            + "如果用户说的是普通话，最后输出简体中文；如果用户说的是粤语，最后输出香港繁体。";

    private final WebSocketSession clientSession;
    private final AtomicLong eventCounter = new AtomicLong(0);
    /**
     * Stepfun 的流式事件在部分会话中会重复携带“截至当前的全文”，而不是纯增量。
     * 这里按 item_id 保存上次全文，将其转换为前端和 Bench 链路都能安全追加的真实增量。
     */
    private final Map<String, String> partialTranscriptByItemId = new HashMap<>();
    private AsrEventListener listener;
    private boolean deliverAllCompletedEvents;
    private volatile int vadSilenceDurationMs = 1000;
    // 每轮对话只允许触发一次 LLM
    // full_rerun_on_commit 会对同一句话产生多个 completed 事件（item_id 不同），需防止重复触发
    private final AtomicBoolean llmFiredThisTurn = new AtomicBoolean(false);

    /**
     * ASR 事件回调接口，由 AsrWebSocketHandler 实现。
     */
    public interface AsrEventListener {
        // VAD 确认用户真实说话（收到首个 ASR delta）时触发，用于打断当前 TTS
        void onSpeechConfirmed();
        // 用户说完一句有效内容后触发，transcript 为识别结果
        void onUserSpeechCompleted(String transcript);
        // 识别增量文本（流式输出），默认空实现，Bench 测试时覆盖
        default void onTranscriptDelta(String delta) {}
        // ASR 发生错误，errMsg 为错误描述，默认空实现
        default void onAsrError(String errMsg) {}
    }

    public StepfunWsClient(URI uri, String apiKey, WebSocketSession clientSession) {
        super(uri, buildHeaders(apiKey));
        this.clientSession = clientSession;
    }

    public void setListener(AsrEventListener listener) {
        this.listener = listener;
    }

    /**
     * 评估模式需要接收 full_rerun_on_commit 产生的最终 completed 事件；
     * 普通对话仍保持每轮只触发一次的默认行为。
     */
    public void setDeliverAllCompletedEvents(boolean deliverAllCompletedEvents) {
        this.deliverAllCompletedEvents = deliverAllCompletedEvents;
    }

    /**
     * 更新服务端 VAD 判停时长。连接已建立时立即发送 session.update；
     * 尚未建连时保存配置，待 onOpen 中首次 session.update 一并生效。
     */
    public void setVadSilenceDurationMs(int vadSilenceDurationMs) {
        if (this.vadSilenceDurationMs == vadSilenceDurationMs) {
            return;
        }
        this.vadSilenceDurationMs = vadSilenceDurationMs;
        if (isOpen()) {
            sendSessionUpdate();
        }
    }

    private static Map<String, String> buildHeaders(String apiKey) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        return headers;
    }

    /**
     * ASR 连接建立后，发送 session.update 配置音频格式、转录模型和 VAD 参数。
     */
    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("【ASR 连接建立】已连接 Stepfun ASR，sessionId={}", clientSession.getId());
        sendSessionUpdate();
    }

    /**
     * 接收 ASR 服务推送的事件，按类型分发处理。
     */
    @Override
    public void onMessage(String message) {
        try {
            JSONObject json = JSONObject.parseObject(message);
            String type = json.getString("type");

            log.debug("【ASR 事件】type={}", type);

            // 转录增量和 VAD 检测到说话开始：直接转发给前端
            if ("conversation.item.input_audio_transcription.delta".equals(type)
                    || "input_audio_buffer.speech_started".equals(type)) {
                if ("input_audio_buffer.speech_started".equals(type)) {
                    log.info("【VAD 检测】用户开始说话，sessionId={}", clientSession.getId());
                    llmFiredThisTurn.set(false);
                    if (listener != null) {
                        listener.onSpeechConfirmed();
                    }
                }
                if ("conversation.item.input_audio_transcription.delta".equals(type)) {
                    String deltaText = toIncrementalDelta(json.getString("item_id"), json.getString("text"));
                    // 通知 Bench 流式增量（普通 ASR 模式下默认空实现，无副作用）
                    if (listener != null && isMeaningful(deltaText)) {
                        listener.onTranscriptDelta(deltaText);
                    }
                    // 已经发送过的全文无需再次推给前端，避免通话气泡反复追加。
                    if (!isMeaningful(deltaText)) {
                        return;
                    }
                    json.put("text", deltaText);
                    message = json.toJSONString();
                }
                // 转发给前端，前端据此更新 UI 状态
                if (clientSession.isOpen()) {
                    synchronized (clientSession) {
                        clientSession.sendMessage(new TextMessage(message));
                    }
                }
                return;
            }

            // 转录完成事件：提取文本，过滤无效内容，触发 LLM
            if ("conversation.item.input_audio_transcription.completed".equals(type)) {
                clearPartialTranscript(json.getString("item_id"));
                String transcript = json.getString("transcript");
                // 部分情况下 transcript 在 content 数组里
                if ((transcript == null || transcript.isEmpty()) && json.containsKey("content")) {
                    transcript = json.getJSONArray("content")
                            .getJSONObject(0).getString("transcript");
                }

                // 过滤纯标点（如"。"），这类结果没有实际内容，不触发 LLM 也不展示
                if (!isMeaningful(transcript)) {
                    log.info("【ASR 过滤】识别结果无实际内容，已忽略：[{}]，sessionId={}", transcript, clientSession.getId());
                    // 仍需通知前端清理对应的临时气泡（transcript 置空，前端收到后删除该消息）
                    String itemId = json.getString("item_id");
                    if (itemId != null && clientSession.isOpen()) {
                        JSONObject notify = new JSONObject();
                        notify.put("type", "conversation.item.input_audio_transcription.completed");
                        notify.put("item_id", itemId);
                        notify.put("transcript", "");
                        synchronized (clientSession) {
                            clientSession.sendMessage(new TextMessage(notify.toJSONString()));
                        }
                    }
                    return;
                }

                log.info("【ASR 识别完成】transcript={}，sessionId={}", transcript, clientSession.getId());
                // 将完整识别结果转发给前端，前端据此 finalize 用户气泡
                if (clientSession.isOpen()) {
                    synchronized (clientSession) {
                        clientSession.sendMessage(new TextMessage(message));
                    }
                }

                // compareAndSet 保证每轮只触发一次 LLM
                // full_rerun_on_commit 会产生多个 completed 事件，第一个有效的触发后其余忽略
                if (listener != null && (deliverAllCompletedEvents || llmFiredThisTurn.compareAndSet(false, true))) {
                    log.info("【触发 LLM】本轮首次有效识别，开始推理，transcript={}，sessionId={}", transcript, clientSession.getId());
                    listener.onUserSpeechCompleted(transcript);
                } else {
                    log.info("【重复识别】本轮已触发过 LLM，忽略此次 completed 事件，sessionId={}", clientSession.getId());
                }
            }
        } catch (Exception e) {
            log.error("【错误】处理 ASR 消息异常，sessionId={}", clientSession.getId(), e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info("【ASR 连接关闭】code={}，reason={}，remote={}，sessionId={}", code, reason, remote, clientSession.getId());
    }

    @Override
    public void onError(Exception ex) {
        log.error("【ASR 连接错误】sessionId={}", clientSession.getId(), ex);
    }

    /**
     * 判断识别结果是否有实际内容（去掉标点和空白后还有文字）。
     */
    private static boolean isMeaningful(String transcript) {
        if (transcript == null || transcript.isEmpty()) return false;
        return transcript.replaceAll("[\\p{P}\\p{Z}\\s]", "").length() > 0;
    }

    /**
     * 将 Stepfun 可能返回的累计中间文本转为增量。
     *
     * 正常的纯增量事件不满足前缀关系，会原样透传；累计文本则只保留新增后缀。
     * 识别引擎发生回改、当前文本反而变短时不追加旧内容，等待后续结果或 completed 覆盖即可。
     */
    private String toIncrementalDelta(String itemId, String text) {
        if (text == null || text.isEmpty() || itemId == null || itemId.isEmpty()) {
            return text;
        }

        synchronized (partialTranscriptByItemId) {
            String previous = partialTranscriptByItemId.get(itemId);
            if (previous == null || previous.isEmpty()) {
                partialTranscriptByItemId.put(itemId, text);
                return text;
            }
            if (text.equals(previous)) {
                return "";
            }
            if (text.startsWith(previous)) {
                partialTranscriptByItemId.put(itemId, text);
                return text.substring(previous.length());
            }
            if (previous.startsWith(text)) {
                partialTranscriptByItemId.put(itemId, text);
                return "";
            }

            // 无前缀关系时按协议的纯增量语义处理，避免误丢失识别引擎新增的文本。
            partialTranscriptByItemId.put(itemId, previous + text);
            return text;
        }
    }

    private void clearPartialTranscript(String itemId) {
        if (itemId == null || itemId.isEmpty()) return;
        synchronized (partialTranscriptByItemId) {
            partialTranscriptByItemId.remove(itemId);
        }
    }

    /**
     * 将 PCM 音频帧以 base64 编码发送给 ASR 服务。
     */
    public void sendAudioFrame(byte[] pcmBytes) {
        if (!isOpen()) return;
        String base64Audio = Base64.getEncoder().encodeToString(pcmBytes);
        JSONObject msg = new JSONObject();
        msg.put("event_id", "event_" + eventCounter.incrementAndGet());
        msg.put("type", "input_audio_buffer.append");
        msg.put("audio", base64Audio);
        send(msg.toJSONString());
    }

    /**
     * 强制提交当前音频缓冲区，触发 ASR 对已发送音频进行识别。
     * 用于文件播放结束后通知 Stepfun ASR 音频已结束，不再等待 VAD 静音检测。
     */
    public void sendCommit() {
        if (!isOpen()) return;
        JSONObject msg = new JSONObject();
        msg.put("event_id", "event_" + eventCounter.incrementAndGet());
        msg.put("type", "input_audio_buffer.commit");
        send(msg.toJSONString());
        log.info("【Stepfun】发送 input_audio_buffer.commit，强制触发识别");
    }

    /**
     * 发送 session.update 配置音频格式、转录模型和 VAD 参数。
     * 在连接建立后调用一次。
     */
    private void sendSessionUpdate() {
        // 音频格式：16kHz、16bit、单声道 PCM
        JSONObject formatObj = new JSONObject();
        formatObj.put("type", "pcm");
        formatObj.put("codec", "pcm_s16le");
        formatObj.put("rate", 16000);
        formatObj.put("bits", 16);
        formatObj.put("channel", 1);

        // 转录配置：使用流式 ASR 模型，开启数字规范化
        // full_rerun_on_commit=true：VAD 检测到说话结束后对整句重新识别，提升准确率（会产生多个 completed 事件）
        JSONObject transcription = new JSONObject();
        transcription.put("model", "stepaudio-2.5-asr-stream");
        transcription.put("language", "zh");
        transcription.put("prompt", SECURITIES_HOTWORD_PROMPT);
        transcription.put("full_rerun_on_commit", true);
        transcription.put("enable_itn", true);

        // VAD 配置：服务端 VAD，静音达到配置时长后认为说话结束
        JSONObject vad = new JSONObject();
        vad.put("type", "server_vad");
        vad.put("silence_duration_ms", vadSilenceDurationMs);
        vad.put("threshold", 0.8);

        JSONObject inputAudio = new JSONObject();
        inputAudio.put("format", formatObj);
        inputAudio.put("transcription", transcription);
        inputAudio.put("turn_detection", vad);

        JSONObject audio = new JSONObject();
        audio.put("input", inputAudio);

        JSONObject session = new JSONObject();
        session.put("audio", audio);

        JSONObject msg = new JSONObject();
        msg.put("event_id", "event_" + eventCounter.incrementAndGet());
        msg.put("type", "session.update");
        msg.put("session", session);

        send(msg.toJSONString());
        log.info("【ASR 配置】已发送 session.update，sessionId={}", clientSession.getId());
    }
}
