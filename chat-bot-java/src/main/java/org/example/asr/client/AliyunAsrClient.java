package org.example.asr.client;

import com.alibaba.fastjson2.JSONObject;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.UUID;

public class AliyunAsrClient extends WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(AliyunAsrClient.class);

    private static final String MODEL = "paraformer-realtime-8k-v2";

    private final WebSocketSession clientSession;
    private final String taskId;
    private StepfunWsClient.AsrEventListener listener;

    // 当前句子的 item_id（null 表示本句尚未开始）
    private String currentItemId = null;
    // 当前句子已发送给前端的文本长度，用于计算增量
    private final StringBuilder currentSentence = new StringBuilder();

    public AliyunAsrClient(String wsUrl, String apiKey, WebSocketSession clientSession) throws Exception {
        super(new URI(wsUrl));
        this.addHeader("Authorization", "Bearer " + apiKey);
        this.clientSession = clientSession;
        this.taskId = UUID.randomUUID().toString().replace("-", "");
    }

    public void setListener(StepfunWsClient.AsrEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("【阿里云 ASR 连接建立】sessionId={}", clientSession.getId());

        JSONObject header = new JSONObject();
        header.put("action", "run-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");

        JSONObject parameters = new JSONObject();
        parameters.put("format", "pcm");
        parameters.put("sample_rate", 8000);
        parameters.put("max_sentence_silence", 1300);

        JSONObject payload = new JSONObject();
        payload.put("task_group", "audio");
        payload.put("task", "asr");
        payload.put("function", "recognition");
        payload.put("model", MODEL);
        payload.put("parameters", parameters);
        payload.put("input", new JSONObject());

        JSONObject runTask = new JSONObject();
        runTask.put("header", header);
        runTask.put("payload", payload);

        send(runTask.toJSONString());
        log.info("【阿里云 ASR】已发送 run-task，taskId={}", taskId);
    }

    @Override
    public void onMessage(String message) {
        try {
            JSONObject json = JSONObject.parseObject(message);
            JSONObject header = json.getJSONObject("header");
            if (header == null) return;
            String event = header.getString("event");

            if ("task-started".equals(event)) {
                log.info("【阿里云 ASR】task-started，sessionId={}", clientSession.getId());
                return;
            }
            if ("task-finished".equals(event)) {
                log.info("【阿里云 ASR】task-finished，sessionId={}", clientSession.getId());
                return;
            }
            if ("task-failed".equals(event)) {
                log.error("【阿里云 ASR task-failed】{}，sessionId={}", message, clientSession.getId());
                return;
            }
            if (!"result-generated".equals(event)) return;

            JSONObject payload = json.getJSONObject("payload");
            if (payload == null) return;
            JSONObject output = payload.getJSONObject("output");
            if (output == null) return;
            JSONObject sentence = output.getJSONObject("sentence");
            if (sentence == null) return;

            // 心跳包，跳过
            if (Boolean.TRUE.equals(sentence.getBoolean("heartbeat"))) return;

            String text = sentence.getString("text");
            if (text == null || text.isEmpty()) return;

            boolean sentenceEnd = Boolean.TRUE.equals(sentence.getBoolean("sentence_end"));

            if (!sentenceEnd) {
                // 中间结果：新句子开始时触发打断检查
                if (currentItemId == null) {
                    currentItemId = "aliyun_" + System.currentTimeMillis();
                    if (listener != null) listener.onSpeechConfirmed();
                }

                // Paraformer 每次返回完整句子文本，计算增量部分
                String prev = currentSentence.toString();
                currentSentence.setLength(0);
                currentSentence.append(text);
                String delta = text.length() > prev.length() ? text.substring(prev.length()) : "";
                if (!delta.isEmpty()) {
                    JSONObject deltaEvent = new JSONObject();
                    deltaEvent.put("type", "conversation.item.input_audio_transcription.delta");
                    deltaEvent.put("item_id", currentItemId);
                    deltaEvent.put("text", delta);
                    sendToClient(deltaEvent.toJSONString());
                    if (listener != null) listener.onTranscriptDelta(delta);
                }
            } else {
                // 句子识别完成
                String transcript = text.trim();
                String itemId = currentItemId != null ? currentItemId : ("aliyun_" + System.currentTimeMillis());
                currentItemId = null;
                currentSentence.setLength(0);

                if (!isMeaningful(transcript)) {
                    log.info("【阿里云 ASR 过滤】识别结果无实际内容 [{}]，sessionId={}", transcript, clientSession.getId());
                    JSONObject doneEvent = new JSONObject();
                    doneEvent.put("type", "conversation.item.input_audio_transcription.completed");
                    doneEvent.put("item_id", itemId);
                    doneEvent.put("transcript", "");
                    sendToClient(doneEvent.toJSONString());
                    return;
                }

                log.info("【阿里云 ASR 识别完成】transcript={}，sessionId={}", transcript, clientSession.getId());
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
            log.error("【阿里云 ASR 消息处理异常】sessionId={}", clientSession.getId(), e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info("【阿里云 ASR 连接关闭】code={}，reason={}，sessionId={}", code, reason, clientSession.getId());
    }

    @Override
    public void onError(Exception ex) {
        log.error("【阿里云 ASR 连接错误】sessionId={}", clientSession.getId(), ex);
    }

    /**
     * 接收 16kHz PCM，降采样为 8kHz 后发送给阿里云（取每隔一个样本）。
     */
    public void sendAudioFrame(byte[] pcm16k) {
        if (!isOpen()) return;
        int inputSamples = pcm16k.length / 2;
        int outputSamples = inputSamples / 2;
        byte[] pcm8k = new byte[outputSamples * 2];
        for (int i = 0; i < outputSamples; i++) {
            pcm8k[i * 2]     = pcm16k[i * 4];
            pcm8k[i * 2 + 1] = pcm16k[i * 4 + 1];
        }
        send(ByteBuffer.wrap(pcm8k));
    }

    /**
     * 发送 finish-task，通知阿里云音频结束。
     */
    public void sendFinishTask() {
        if (!isOpen()) return;
        JSONObject header = new JSONObject();
        header.put("action", "finish-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");

        JSONObject payload = new JSONObject();
        payload.put("input", new JSONObject());

        JSONObject finishTask = new JSONObject();
        finishTask.put("header", header);
        finishTask.put("payload", payload);

        send(finishTask.toJSONString());
    }

    private void sendToClient(String msg) {
        if (!clientSession.isOpen()) return;
        try {
            synchronized (clientSession) {
                clientSession.sendMessage(new TextMessage(msg));
            }
        } catch (Exception e) {
            log.error("【阿里云 ASR】发送前端消息失败，sessionId={}", clientSession.getId(), e);
        }
    }

    private static boolean isMeaningful(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.replaceAll("[\\p{P}\\p{Z}\\s]", "").length() > 0;
    }
}
