package org.example.asr.client;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class FanoAsrClient {

    private static final Logger log = LoggerFactory.getLogger(FanoAsrClient.class);

    private final String apiUrl;
    private final String token;

    public FanoAsrClient(String apiUrl, String token) {
        this.apiUrl = apiUrl;
        this.token = token;
    }

    /**
     * 将 PCM 字节数组（16kHz, 16bit, mono）发送给 FANO 识别，返回识别文本。
     * 返回 null 表示识别失败或无结果。
     */
    public String recognize(byte[] pcmBytes) {
        try {
            byte[] wavBytes = toWav(pcmBytes, 16000, 1, 16);
            String audioBase64 = Base64.getEncoder().encodeToString(wavBytes);

            JSONObject diarizationConfig = new JSONObject();
            diarizationConfig.put("disableSpeakerDiarization", true);

            JSONObject config = new JSONObject();
            config.put("encoding", "LINEAR16");
            config.put("sampleRateHertz", 16000);
            config.put("languageCode", "yue-x-auto");
            config.put("audioChannelCount", 1);
            config.put("maxAlternatives", 1);
            config.put("enableAutomaticPunctuation", false);
            config.put("enableSepareteRecognitionPerChannel", false);
            config.put("diarizationConfig", diarizationConfig);

            JSONObject audio = new JSONObject();
            audio.put("content", audioBase64);

            JSONObject body = new JSONObject();
            body.put("config", config);
            body.put("audio", audio);

            byte[] bodyBytes = body.toJSONString().getBytes(StandardCharsets.UTF_8);
            JSONObject logBody = new JSONObject();
            logBody.put("config", config);
            logBody.put("audio", new JSONObject() {{ put("content", "[base64, length=" + audioBase64.length() + ", pcmBytes=" + pcmBytes.length + ", wavBytes=" + wavBytes.length + "]"); }});
            log.info("【FANO ASR】请求报文：{}", logBody.toJSONString());

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", token);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                StringBuilder errSb = new StringBuilder();
                try (BufferedReader errReader = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = errReader.readLine()) != null) errSb.append(line);
                } catch (Exception ignored) {}
                log.error("【FANO ASR】HTTP 错误，code={}，响应体：{}", code, errSb);
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }

            String respBody = sb.toString();
            log.info("【FANO ASR】响应报文：{}", respBody);
            JSONObject resp = JSONObject.parseObject(respBody);
            JSONArray results = resp.getJSONArray("results");
            if (results == null || results.isEmpty()) return null;

            JSONArray alternatives = results.getJSONObject(0).getJSONArray("alternatives");
            if (alternatives == null || alternatives.isEmpty()) return null;

            String transcript = alternatives.getJSONObject(0).getString("transcript");
            log.info("【FANO ASR】识别结果：{}", transcript);
            return transcript;
        } catch (Exception e) {
            log.error("【FANO ASR】识别异常", e);
            return null;
        }
    }

    /** 将裸 PCM 数据包装成标准 WAV 格式字节数组 */
    private byte[] toWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int dataLen = pcm.length;
        int totalLen = 44 + dataLen;
        byte[] wav = new byte[totalLen];
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        System.arraycopy("RIFF".getBytes(), 0, wav, 0, 4);
        putInt(wav, 4, totalLen - 8);
        System.arraycopy("WAVE".getBytes(), 0, wav, 8, 4);
        System.arraycopy("fmt ".getBytes(), 0, wav, 12, 4);
        putInt(wav, 16, 16);
        putShort(wav, 20, (short) 1);
        putShort(wav, 22, (short) channels);
        putInt(wav, 24, sampleRate);
        putInt(wav, 28, byteRate);
        putShort(wav, 32, (short) blockAlign);
        putShort(wav, 34, (short) bitsPerSample);
        System.arraycopy("data".getBytes(), 0, wav, 36, 4);
        putInt(wav, 40, dataLen);
        System.arraycopy(pcm, 0, wav, 44, dataLen);
        return wav;
    }

    private void putInt(byte[] buf, int offset, int val) {
        buf[offset]     = (byte) (val & 0xFF);
        buf[offset + 1] = (byte) ((val >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((val >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((val >> 24) & 0xFF);
    }

    private void putShort(byte[] buf, int offset, short val) {
        buf[offset]     = (byte) (val & 0xFF);
        buf[offset + 1] = (byte) ((val >> 8) & 0xFF);
    }
}
