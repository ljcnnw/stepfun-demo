package org.example.asr.client;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 火山引擎 BigASR 双向流式优化版客户端（bigmodel_async）
 *
 * 协议说明：
 * - 使用自定义二进制帧协议，每帧 = 4字节header + 4字节payload_size + payload
 * - full client request: header[0x11,0x10,0x11,0x00] + Gzip压缩的JSON
 * - audio only request:  header[0x11,0x20,0x00,0x00] + 原始PCM（不压缩）
 * - 最后一帧（负包）:    header[0x11,0x22,0x00,0x00] + 空payload
 * - 服务端响应: 4字节header + 4字节sequence + 4字节payload_size + Gzip压缩的JSON
 */
public class VolcAsrClient extends WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(VolcAsrClient.class);

    // ── 协议头常量 ────────────────────────────────────────────────────
    // version=0001, header_size=0001 → byte0 = 0x11
    private static final byte PROTO_BYTE0 = 0x11;
    // msg_type=0001(Full), flags=0000 → byte1 = 0x10
    private static final byte MSG_FULL_CLIENT = 0x10;
    // msg_type=0010(AudioOnly), flags=0000 → byte1 = 0x20
    private static final byte MSG_AUDIO_ONLY = 0x20;
    // msg_type=0010(AudioOnly), flags=0010(最后一包) → byte1 = 0x22
    private static final byte MSG_AUDIO_LAST = 0x22;
    // serialization=JSON(0001), compression=Gzip(0001) → byte2 = 0x11
    private static final byte CODEC_JSON_GZIP = 0x11;
    // serialization=none(0000), compression=none(0000) → byte2 = 0x00
    private static final byte CODEC_RAW = 0x00;
    // reserved byte
    private static final byte RESERVED = 0x00;

    private final WebSocketSession clientSession;
    private final String taskId;
    private StepfunWsClient.AsrEventListener listener;

    // 识别参数（从配置注入）
    private final String modelName;
    private final boolean enableItn;
    private final boolean enablePunc;
    private final boolean enableDdc;
    private final boolean enableNonstream;
    private final int endWindowSize;
    private final int forceToSpeechTime;
    private final String outputZhVariant;
    private final boolean enableLid;
    private final String resultType;

    // 每句已发给前端的文本长度，用于计算 delta
    private String lastSentText = "";
    // 当前句子的 item_id，null 表示当前句未开始
    private String currentItemId = null;

    private final Object sendLock = new Object();
    private final CountDownLatch fullRequestSent = new CountDownLatch(1);

    public VolcAsrClient(String wsUrl, String appKey, String accessKey, String resourceId,
                         WebSocketSession clientSession,
                         String modelName, boolean enableItn, boolean enablePunc,
                         boolean enableDdc, boolean enableNonstream,
                         int endWindowSize, int forceToSpeechTime,
                         String outputZhVariant, boolean enableLid, String resultType) throws Exception {
        this(wsUrl, appKey, accessKey, resourceId, clientSession,
                modelName, enableItn, enablePunc, enableDdc, enableNonstream,
                endWindowSize, forceToSpeechTime, outputZhVariant, enableLid, resultType, endWindowSize);
    }

    public VolcAsrClient(String wsUrl, String appKey, String accessKey, String resourceId,
                         WebSocketSession clientSession,
                         String modelName, boolean enableItn, boolean enablePunc,
                         boolean enableDdc, boolean enableNonstream,
                         int configuredEndWindowSize, int forceToSpeechTime,
                         String outputZhVariant, boolean enableLid, String resultType,
                         int vadEndWindowSize) throws Exception {
        super(new URI(wsUrl));
        this.clientSession = clientSession;
        this.taskId = UUID.randomUUID().toString().replace("-", "");
        this.modelName = modelName;
        this.enableItn = enableItn;
        this.enablePunc = enablePunc;
        this.enableDdc = enableDdc;
        this.enableNonstream = enableNonstream;
        this.endWindowSize = vadEndWindowSize;
        this.forceToSpeechTime = forceToSpeechTime;
        this.outputZhVariant = outputZhVariant;
        this.enableLid = enableLid;
        this.resultType = resultType;

        // 鉴权：新版控制台使用 X-Api-Key；若使用旧版控制台，改为 X-Api-App-Key + X-Api-Access-Key
        this.addHeader("X-Api-App-Key", appKey);
        this.addHeader("X-Api-Access-Key", accessKey);
        this.addHeader("X-Api-Resource-Id", resourceId);
        this.addHeader("X-Api-Request-Id", this.taskId);
        // 固定值 -1（文档要求）
        this.addHeader("X-Api-Sequence", "-1");
    }

    public void setListener(StepfunWsClient.AsrEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("【火山引擎 ASR 连接建立】sessionId={}，taskId={}", clientSession.getId(), taskId);
        try {
            byte[] frame = buildFullClientRequest();
            synchronized (sendLock) {
                send(ByteBuffer.wrap(frame));
            }
            log.info("【火山引擎 ASR】已发送 full client request，taskId={}", taskId);
        } catch (Exception e) {
            log.error("【火山引擎 ASR】发送 full client request 失败，sessionId={}", clientSession.getId(), e);
        } finally {
            fullRequestSent.countDown();
        }
    }

    /**
     * 构造 full client request 二进制帧。
     * 格式：4字节header + 4字节payload_size(大端) + Gzip(JSON)
     */
    private byte[] buildFullClientRequest() throws Exception {
        // 构造请求 JSON
        JSONObject audio = new JSONObject();
        audio.put("format", "pcm");
        audio.put("rate", 16000);
        audio.put("bits", 16);
        audio.put("channel", 1);

        JSONObject request = new JSONObject();
        request.put("model_name", modelName);
        request.put("enable_itn", enableItn);
        request.put("enable_punc", enablePunc);
        request.put("enable_ddc", enableDdc);
        request.put("result_type", resultType);
        request.put("enable_nonstream", enableNonstream);
        request.put("end_window_size", endWindowSize);
        if (forceToSpeechTime > 0) {
            request.put("force_to_speech_time", forceToSpeechTime);
        }
        if (outputZhVariant != null && !outputZhVariant.isEmpty()) {
            request.put("output_zh_variant", outputZhVariant);
        }
        if (enableLid) {
            request.put("enable_lid", true);
        }

        JSONObject body = new JSONObject();
        body.put("audio", audio);
        body.put("request", request);

        byte[] jsonBytes = body.toJSONString().getBytes("UTF-8");
        byte[] compressed = gzip(jsonBytes);

        // header: [0x11, 0x10, 0x11, 0x00]
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + compressed.length);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put(PROTO_BYTE0);
        buf.put(MSG_FULL_CLIENT);
        buf.put(CODEC_JSON_GZIP);
        buf.put(RESERVED);
        buf.putInt(compressed.length);
        buf.put(compressed);
        return buf.array();
    }

    /**
     * 发送音频帧（原始 PCM，不压缩）。
     * 格式：4字节header + 4字节payload_size(大端) + PCM字节
     */
    public void sendAudioFrame(byte[] pcm16k) {
        if (!isOpen()) return;
        // header: [0x11, 0x20, 0x00, 0x00]
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + pcm16k.length);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put(PROTO_BYTE0);
        buf.put(MSG_AUDIO_ONLY);
        buf.put(CODEC_RAW);
        buf.put(RESERVED);
        buf.putInt(pcm16k.length);
        buf.put(pcm16k);
        buf.flip();
        synchronized (sendLock) {
            send(buf);
        }
    }

    /**
     * 发送负包（最后一帧），通知服务端音频流结束。
     * 格式：4字节header + 4字节payload_size(0)
     */
    public void sendFinishFrame() {
        if (!isOpen()) return;
        // header: [0x11, 0x22, 0x00, 0x00]，flags=0b0010表示最后一包
        ByteBuffer buf = ByteBuffer.allocate(4 + 4);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put(PROTO_BYTE0);
        buf.put(MSG_AUDIO_LAST);
        buf.put(CODEC_RAW);
        buf.put(RESERVED);
        buf.putInt(0);
        buf.flip();
        synchronized (sendLock) {
            send(buf);
        }
        log.info("【火山引擎 ASR】已发送负包（finish），sessionId={}", clientSession.getId());
    }

    public boolean awaitFullRequestSent(long timeoutMs) throws InterruptedException {
        return fullRequestSent.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onMessage(ByteBuffer bytes) {
        try {
            // 正确读取 ByteBuffer：考虑 arrayOffset 和 limit
            byte[] data;
            if (bytes.hasArray()) {
                int offset = bytes.arrayOffset() + bytes.position();
                int length = bytes.remaining();
                data = new byte[length];
                System.arraycopy(bytes.array(), offset, data, 0, length);
            } else {
                data = new byte[bytes.remaining()];
                bytes.get(data);
            }
            if (data.length < 8) return;

            // 解析 header
            byte msgTypeByte = data[1];
            int msgType = (msgTypeByte >> 4) & 0x0F;

            // 服务端错误帧 (msg_type=0b1111=15)
            // 格式：4字节header + 4字节sequence + 4字节payload_size + payload(Gzip JSON)
            if (msgType == 0x0F) {
                if (data.length >= 12) {
                    int payloadSize = ByteBuffer.wrap(data, 8, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                    if (payloadSize > 0 && data.length >= 12 + payloadSize) {
                        byte[] payload = new byte[payloadSize];
                        System.arraycopy(data, 12, payload, 0, payloadSize);
                        try {
                            // 先尝试 Gzip 解压
                            byte codecByte = data[2];
                            int compression = codecByte & 0x0F;
                            String errJson;
                            if (compression == 0x01) {
                                errJson = new String(ungzip(payload), "UTF-8");
                            } else {
                                errJson = new String(payload, "UTF-8");
                            }
                            log.error("【火山引擎 ASR 服务端错误】raw={}，header=[{},{},{},{}]，sessionId={}",
                                    errJson, String.format("0x%02X", data[0]), String.format("0x%02X", data[1]),
                                    String.format("0x%02X", data[2]), String.format("0x%02X", data[3]),
                                    clientSession.getId());
                            // 尝试解析 JSON
                            try {
                                JSONObject errObj = JSONObject.parseObject(errJson);
                                log.error("【火山引擎 ASR 服务端错误 JSON】{}，sessionId={}", errObj.toJSONString(), clientSession.getId());
                            } catch (Exception ignored) {}
                        } catch (Exception ex) {
                            // Gzip 解压失败，打印十六进制原始数据
                            StringBuilder hex = new StringBuilder();
                            for (int i = 0; i < Math.min(data.length, 64); i++) {
                                hex.append(String.format("%02X ", data[i]));
                            }
                            log.error("【火山引擎 ASR 服务端错误】原始字节={}，sessionId={}", hex, clientSession.getId());
                        }
                    } else {
                        log.error("【火山引擎 ASR 服务端错误】（无 payload），sessionId={}", clientSession.getId());
                    }
                }
                return;
            }

            // full server response (msg_type=0b1001=9)
            // 格式：4字节header + 4字节sequence + 4字节payload_size + payload
            if (msgType != 0x09) return;
            if (data.length < 12) return;

            int payloadSize = ByteBuffer.wrap(data, 8, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            if (payloadSize <= 0 || data.length < 12 + payloadSize) return;

            byte[] payload = new byte[payloadSize];
            System.arraycopy(data, 12, payload, 0, payloadSize);

            // 检查是否 Gzip 压缩（根据响应 header byte[2] 的低4位判断）
            byte codecByte = data[2];
            int compression = codecByte & 0x0F;
            String json;
            if (compression == 0x01) {
                json = new String(ungzip(payload), "UTF-8");
            } else {
                json = new String(payload, "UTF-8");
            }

            processServerResponse(json);
        } catch (Exception e) {
            log.error("【火山引擎 ASR 消息处理异常】sessionId={}", clientSession.getId(), e);
        }
    }

    @Override
    public void onMessage(String message) {
        // 协议为纯二进制，文本消息忽略
        log.warn("【火山引擎 ASR】收到意外的文本消息：{}，sessionId={}", message, clientSession.getId());
    }

    /**
     * 处理服务端响应 JSON。
     * result.utterances 每项：
     *   definite=false → 中间结果，发 delta 事件并触发打断检查
     *   definite=true  → 最终结果，发 completed 事件并触发 LLM
     */
    private void processServerResponse(String json) {
        try {
            JSONObject resp = JSONObject.parseObject(json);
            JSONObject result = resp.getJSONObject("result");
            if (result == null) return;

            JSONArray utterances = result.getJSONArray("utterances");
            if (utterances == null || utterances.isEmpty()) return;

            // 取最后一个 utterance（当前正在识别的句子）
            JSONObject utt = utterances.getJSONObject(utterances.size() - 1);
            if (utt == null) return;

            String text = utt.getString("text");
            if (text == null || text.isEmpty()) return;

            Boolean definite = utt.getBoolean("definite");
            boolean isFinal = Boolean.TRUE.equals(definite);

            if (!isFinal) {
                // ── 中间结果 ────────────────────────────────────────────────
                if (currentItemId == null) {
                    currentItemId = "volc_" + System.currentTimeMillis();
                    if (listener != null) listener.onSpeechConfirmed();
                    log.info("【火山引擎 ASR】新句开始，触发打断检查，sessionId={}", clientSession.getId());
                }

                // 计算增量 delta（全量文本减去上次已发部分）
                String delta = text.length() > lastSentText.length()
                        ? text.substring(lastSentText.length()) : "";
                lastSentText = text;

                if (!delta.isEmpty()) {
                    JSONObject deltaEvent = new JSONObject();
                    deltaEvent.put("type", "conversation.item.input_audio_transcription.delta");
                    deltaEvent.put("item_id", currentItemId);
                    deltaEvent.put("text", delta);
                    sendToClient(deltaEvent.toJSONString());
                    log.debug("【火山引擎 ASR delta】delta={}，sessionId={}", delta, clientSession.getId());
                    if (listener != null) listener.onTranscriptDelta(delta);
                }
            } else {
                // ── 最终结果 ────────────────────────────────────────────────
                String transcript = text.trim();
                if (currentItemId == null) {
                    log.info("【火山引擎 ASR】收到无中间态的最终句，sessionId={}，transcript={}", clientSession.getId(), transcript);
                }
                String itemId = currentItemId != null ? currentItemId : ("volc_" + System.currentTimeMillis());
                currentItemId = null;
                lastSentText = "";

                if (!isMeaningful(transcript)) {
                    log.info("【火山引擎 ASR 过滤】识别结果无实际内容 [{}]，sessionId={}", transcript, clientSession.getId());
                    JSONObject doneEvent = new JSONObject();
                    doneEvent.put("type", "conversation.item.input_audio_transcription.completed");
                    doneEvent.put("item_id", itemId);
                    doneEvent.put("transcript", "");
                    sendToClient(doneEvent.toJSONString());
                    return;
                }

                log.info("【火山引擎 ASR 识别完成】transcript={}，sessionId={}", transcript, clientSession.getId());
                JSONObject doneEvent = new JSONObject();
                doneEvent.put("type", "conversation.item.input_audio_transcription.completed");
                doneEvent.put("item_id", itemId);
                doneEvent.put("transcript", transcript);
                sendToClient(doneEvent.toJSONString());

                if (listener != null) {
                    listener.onUserSpeechCompleted(transcript);
                }
            }
        } catch (Exception e) {
            log.error("【火山引擎 ASR 响应解析异常】sessionId={}", clientSession.getId(), e);
        }
    }

    /**
     * 判断识别结果是否有实际内容（过滤纯标点、空白等无意义内容）。
     */
    private boolean isMeaningful(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        return text.trim().matches(".*[\\u4e00-\\u9fa5a-zA-Z0-9]+.*");
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info("【火山引擎 ASR 连接关闭】code={}，reason={}，sessionId={}", code, reason, clientSession.getId());
    }

    @Override
    public void onError(Exception ex) {
        log.error("【火山引擎 ASR 连接错误】sessionId={}", clientSession.getId(), ex);
    }

    private void sendToClient(String json) {
        if (!clientSession.isOpen()) return;
        synchronized (clientSession) {
            try {
                clientSession.sendMessage(new org.springframework.web.socket.TextMessage(json));
            } catch (Exception e) {
                log.error("【火山引擎 ASR】向前端发送消息失败，sessionId={}", clientSession.getId(), e);
            }
        }
    }

    // ── Gzip 工具方法 ─────────────────────────────────────────────────

    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
            gos.write(data);
        }
        return bos.toByteArray();
    }

    private static byte[] ungzip(byte[] data) throws Exception {
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPInputStream gis = new GZIPInputStream(bis)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = gis.read(buf)) != -1) bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
