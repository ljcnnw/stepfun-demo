package org.example.asr.eval;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

@Component
public class EvalEvaluatorClient {
    private static final Logger log = LoggerFactory.getLogger(EvalEvaluatorClient.class);

    @Value("${asr.evaluator.url:http://localhost:8090}")
    private String evaluatorUrl;

    @Value("${asr.evaluator.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${asr.evaluator.read-timeout-ms:30000}")
    private int readTimeoutMs;

    public JSONObject score(JSONObject request) throws Exception {
        long startedAt = System.currentTimeMillis();
        log.info(
                "calling evaluator url={} mode={} passRule={} threshold={} referenceLen={} transcriptLen={} criticalTerms={}",
                evaluatorUrl + "/v1/score",
                request.getString("textMode"),
                request.getString("passRuleType"),
                request.get("passThreshold"),
                textLength(request.getString("referenceText")),
                textLength(request.getString("transcript")),
                criticalTermsCount(request.getJSONArray("criticalTerms"))
        );
        HttpURLConnection connection = open("/v1/score", "POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(request.toJSONString().getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        String response = readText(connection);
        if (status / 100 != 2) {
            log.warn(
                    "evaluator call failed status={} elapsedMs={} response={}",
                    status,
                    System.currentTimeMillis() - startedAt,
                    response
            );
            throw new IllegalStateException("评分服务失败: " + response);
        }
        JSONObject parsed = JSONObject.parseObject(response);
        log.info(
                "evaluator call succeeded status={} elapsedMs={} cer={} wer={} pass={} normalizerVersion={}",
                status,
                System.currentTimeMillis() - startedAt,
                parsed.get("cer"),
                parsed.get("wer"),
                parsed.get("pass"),
                parsed.get("normalizerVersion")
        );
        return parsed;
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(evaluatorUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        return connection;
    }

    private String readText(HttpURLConnection connection) throws Exception {
        InputStream stream = connection.getErrorStream() != null ? connection.getErrorStream() : connection.getInputStream();
        return new String(readBytes(stream), StandardCharsets.UTF_8);
    }

    private byte[] readBytes(InputStream stream) throws Exception {
        if (stream == null) return new byte[0];
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private int textLength(String value) {
        return value == null ? 0 : value.length();
    }

    private int criticalTermsCount(JSONArray terms) {
        return terms == null ? 0 : terms.size();
    }
}
