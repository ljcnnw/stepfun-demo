package org.example.asr.client;

import com.alibaba.fastjson2.JSONObject;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * 与 Stepfun TTS 服务的 WebSocket 客户端。
 * 负责：
 *   1. 连接建立后发送 tts.create 初始化合成会话
 *   2. 将待合成的文本发送给 TTS 服务
 *   3. 接收 TTS 返回的 PCM 音频块，转发给前端播放
 *   4. 合成完成或出错时通过 onDone 回调通知 LlmService 的串行队列
 */
public class TtsWebSocketClient extends WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(TtsWebSocketClient.class);

    private final WebSocketSession clientSession;
    private final String voiceId;
    private final String text;
    // TTS 服务分配的会话 ID，后续发送文本时需要带上
    private String ttsSessionId;
    // 播放完成回调，由 LlmService 的串行队列设置，触发后允许播放下一句
    private Runnable onDone;

    public TtsWebSocketClient(URI uri, String apiKey, String voiceId, String text,
                               WebSocketSession clientSession) {
        super(uri, buildHeaders(apiKey));
        this.voiceId = voiceId;
        this.text = text;
        this.clientSession = clientSession;
    }

    public void setOnDone(Runnable onDone) {
        this.onDone = onDone;
    }

    private static Map<String, String> buildHeaders(String apiKey) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        return headers;
    }

    /**
     * TTS WebSocket 连接建立，等待服务端推送 tts.connection.done 后再初始化会话。
     */
    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("【TTS 连接建立】clientSession={}", clientSession.getId());
    }

    /**
     * 接收 TTS 服务推送的事件，按类型分发处理。
     */
    @Override
    public void onMessage(String message) {
        try {
            JSONObject json = JSONObject.parseObject(message);
            String type = json.getString("type");

            // 连接就绪，获取 session_id 后发送 tts.create 初始化合成会话
            if ("tts.connection.done".equals(type)) {
                ttsSessionId = json.getJSONObject("data").getString("session_id");
                log.info("【TTS 会话就绪】ttsSessionId={}，clientSession={}", ttsSessionId, clientSession.getId());
                sendCreate();
                return;
            }

            // 合成会话创建成功，发送待合成的文本
            if ("tts.response.created".equals(type)) {
                log.info("【TTS 开始合成】text={}，clientSession={}", text, clientSession.getId());
                sendTextDelta();
                sendTextDone();
                return;
            }

            // TTS 开始合成某句话，通知前端提前标记为播放中（用于 VAD 打断判断）
            if ("tts.response.sentence.start".equals(type)) {
                String sentenceText = json.getJSONObject("data").getString("text");
                safeSend(new JSONObject() {{
                    put("type", "tts.text.delta");
                    put("text", sentenceText);
                }});
                return;
            }

            // 收到音频数据块，转发给前端播放
            if ("tts.response.audio.delta".equals(type)) {
                String audio = json.getJSONObject("data").getString("audio");
                safeSend(new JSONObject() {{
                    put("type", "tts.audio.delta");
                    put("audio", audio);
                }});
                return;
            }

            // 当前句音频全部发送完毕，通知前端和串行队列
            if ("tts.response.audio.done".equals(type)) {
                log.info("【TTS 音频完成】当前句播放完毕，clientSession={}", clientSession.getId());
                safeSend(new JSONObject() {{
                    put("type", "tts.audio.done");
                }});
                // 释放串行队列的 CountDownLatch，允许播放下一句
                if (onDone != null) {
                    onDone.run();
                }
            }

            // TTS 合成出错，同样释放队列避免阻塞
            if ("tts.response.error".equals(type)) {
                log.error("【TTS 合成错误】{}，clientSession={}", json.getJSONObject("data").getString("message"), clientSession.getId());
                if (onDone != null) {
                    onDone.run();
                }
            }

        } catch (Exception e) {
            log.error("【错误】处理 TTS 消息异常，clientSession={}", clientSession.getId(), e);
        }
    }

    /**
     * TTS 连接关闭（正常结束或被 interruptAll 强制关闭）。
     * 确保释放串行队列，避免 LlmService 的 TTS 消费线程永久阻塞。
     */
    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info("【TTS 连接关闭】code={}，reason={}，clientSession={}", code, reason, clientSession.getId());
        // 被强制关闭时也要释放队列，防止 LlmService 的 latch.await 永久阻塞
        if (onDone != null) {
            onDone.run();
            onDone = null;
        }
    }

    @Override
    public void onError(Exception ex) {
        log.error("【TTS 连接错误】clientSession={}", clientSession.getId(), ex);
    }

    /**
     * 向前端发送消息，加 synchronized 防止多个 TTS 线程并发写同一个 WebSocket 连接。
     */
    private void safeSend(JSONObject payload) {
        if (!clientSession.isOpen()) return;
        synchronized (clientSession) {
            try {
                clientSession.sendMessage(new TextMessage(payload.toJSONString()));
            } catch (Exception e) {
                log.error("【错误】向前端发送消息失败，clientSession={}", clientSession.getId(), e);
            }
        }
    }

    /**
     * 发送 tts.create，初始化 TTS 合成会话，指定音色、格式和模式。
     */
    private void sendCreate() {
        JSONObject data = new JSONObject();
        data.put("session_id", ttsSessionId);
        data.put("voice_id", voiceId);
        data.put("response_format", "pcm");   // 返回原始 PCM，前端直接播放
        data.put("sample_rate", 16000);
        data.put("mode", "sentence");          // 按句子返回音频，便于流式播放

        JSONObject msg = new JSONObject();
        msg.put("type", "tts.create");
        msg.put("data", data);
        send(msg.toJSONString());
    }

    /**
     * 发送待合成的文本内容。
     */
    private void sendTextDelta() {
        JSONObject data = new JSONObject();
        data.put("session_id", ttsSessionId);
        data.put("text", text);

        JSONObject msg = new JSONObject();
        msg.put("type", "tts.text.delta");
        msg.put("data", data);
        send(msg.toJSONString());
    }

    /**
     * 发送文本结束标记，通知 TTS 服务开始合成。
     */
    private void sendTextDone() {
        JSONObject data = new JSONObject();
        data.put("session_id", ttsSessionId);

        JSONObject msg = new JSONObject();
        msg.put("type", "tts.text.done");
        msg.put("data", data);
        send(msg.toJSONString());
    }
}
