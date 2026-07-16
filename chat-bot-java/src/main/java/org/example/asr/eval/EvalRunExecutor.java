package org.example.asr.eval;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
public class EvalRunExecutor {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Logger log = LoggerFactory.getLogger(EvalRunExecutor.class);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicReference<TaskControl> activeTask = new AtomicReference<>();
    private final AsrVendorRunner vendorRunner;
    private final EvalEvaluatorClient evaluatorClient;

    @Value("${bench.cases.dir:./asr-test-cases}")
    private String casesDir;

    public EvalRunExecutor(AsrVendorRunner vendorRunner, EvalEvaluatorClient evaluatorClient) {
        this.vendorRunner = vendorRunner;
        this.evaluatorClient = evaluatorClient;
    }

    public synchronized void prepareRun(JSONObject run) throws Exception {
        JSONArray selectedIds = run.getJSONArray("selectedCaseIds");
        if (selectedIds == null || selectedIds.isEmpty()) throw new IllegalArgumentException("请至少选择一个已保存的 case");
        JSONArray vendors = run.getJSONArray("selectedVendors");
        if (vendors == null || vendors.isEmpty()) throw new IllegalArgumentException("请至少选择一个厂商");
        JSONObject audioPcmDataUrls = run.getJSONObject("audioPcmDataUrls");
        if (audioPcmDataUrls == null) throw new IllegalArgumentException("缺少前端解码后的 PCM 音频");

        JSONArray cases = new JSONArray();
        for (int i = 0; i < selectedIds.size(); i++) {
            String id = selectedIds.getString(i);
            JSONObject meta = readCaseMeta(id);
            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("backendId", id);
            item.put("source", "backend");
            item.put("name", meta.getString("name"));
            item.put("note", meta.getString("note"));
            item.put("caseType", valueOr(meta, "caseType", "sentence"));
            item.put("referenceText", valueOr(meta, "referenceText", ""));
            item.put("criticalTermsText", valueOr(meta, "criticalTermsText", ""));
            item.put("acceptableTextsText", valueOr(meta, "acceptableTextsText", ""));
            item.put("sourceCaseId", meta.getString("sourceCaseId"));
            item.put("noiseProfile", meta.getString("noiseProfile"));
            item.put("noiseType", meta.getString("noiseType"));
            item.put("targetSnrDb", meta.get("targetSnrDb"));
            item.put("passRuleType", valueOr(meta, "passRuleType", "cer"));
            item.put("passThreshold", meta.containsKey("passThreshold") ? meta.getDoubleValue("passThreshold") : 0.2D);
            item.put("enabled", !meta.containsKey("enabled") || meta.getBooleanValue("enabled"));
            item.put("durationSeconds", meta.getDouble("durationSeconds"));
            item.put("audioFileName", meta.getString("originalFileName"));
            item.put("backendAudioExt", meta.getString("audioExt"));
            String dataUrl = audioPcmDataUrls.getString(id);
            if (dataUrl == null || !dataUrl.contains(",")) throw new IllegalArgumentException("缺少 case PCM 音频: " + id);
            item.put("pcmDataBase64", dataUrl.substring(dataUrl.indexOf(',') + 1));
            JSONObject resultMap = new JSONObject();
            for (int j = 0; j < vendors.size(); j++) resultMap.put(vendors.getString(j), idleResult(vendors.getString(j), run.getString("evaluationMode")));
            item.put("vendors", resultMap);
            cases.add(item);
        }
        run.put("cases", cases);
        run.remove("audioPcmDataUrls");
        run.put("logs", new JSONArray());
    }

    public boolean start(JSONObject run, Consumer<JSONObject> persist) {
        TaskControl control = new TaskControl(run, persist, null);
        if (!activeTask.compareAndSet(null, control)) return false;
        run.put("status", "running");
        appendLog(run, "开始后端评估任务");
        persist.accept(run);
        executor.submit(() -> execute(control));
        return true;
    }

    public boolean rerun(JSONObject run, String caseId, Consumer<JSONObject> persist) {
        TaskControl control = new TaskControl(run, persist, caseId);
        if (!activeTask.compareAndSet(null, control)) return false;
        run.put("status", "running");
        appendLog(run, "开始重跑 case: " + caseId);
        persist.accept(run);
        executor.submit(() -> execute(control));
        return true;
    }

    public boolean hasActiveTask() {
        return activeTask.get() != null;
    }

    public synchronized void rescore(JSONObject run) throws Exception {
        if (hasActiveTask()) throw new IllegalStateException("已有评估任务正在运行");
        JSONArray cases = run.getJSONArray("cases");
        JSONArray vendors = run.getJSONArray("selectedVendors");
        if (cases == null || vendors == null) return;
        int rescored = 0;
        for (int i = 0; i < cases.size(); i++) {
            JSONObject caseItem = cases.getJSONObject(i);
            for (int j = 0; j < vendors.size(); j++) {
                JSONObject result = result(caseItem, vendors.getString(j));
                if (!"done".equals(result.getString("status"))) {
                    result.put("pass", false);
                    result.put("passReason", "运行或评分未完成");
                    continue;
                }
                JSONObject score = evaluatorClient.score(scoreRequest(caseItem, result.getString("transcript"), run.getString("evaluationMode")));
                applyStoredScore(result, score);
                rescored += 1;
            }
        }
        run.put("scoringVersion", "asr-eval-pass-v2");
        run.put("rescoredAt", now());
        appendLog(run, "已按当前规则重新评分 " + rescored + " 条厂商结果，未重新调用 ASR");
    }

    public boolean pause(String runId) {
        TaskControl control = activeTask.get();
        if (control == null || !runId.equals(control.run.getString("runId"))) return false;
        control.pauseRequested = true;
        control.run.put("status", "pausing");
        appendLog(control.run, "已请求暂停，当前厂商完成后生效");
        control.persist.accept(control.run);
        return true;
    }

    public boolean resume(String runId) {
        TaskControl control = activeTask.get();
        if (control == null || !runId.equals(control.run.getString("runId"))) return false;
        synchronized (control.monitor) {
            control.pauseRequested = false;
            control.run.put("status", "running");
            appendLog(control.run, "已继续运行");
            control.persist.accept(control.run);
            control.monitor.notifyAll();
        }
        return true;
    }

    public boolean stop(String runId) {
        TaskControl control = activeTask.get();
        if (control == null || !runId.equals(control.run.getString("runId"))) return false;
        control.stopRequested = true;
        vendorRunner.cancelCurrent();
        synchronized (control.monitor) { control.monitor.notifyAll(); }
        appendLog(control.run, "已请求停止任务");
        control.persist.accept(control.run);
        return true;
    }

    public void recoverInterruptedRuns(Path root, Consumer<JSONObject> persist) throws Exception {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.list(root)) {
            paths.filter(Files::isDirectory).forEach(dir -> {
                try {
                    Path file = dir.resolve("run.json");
                    if (!Files.exists(file)) return;
                    JSONObject run = JSONObject.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                    String status = run.getString("status");
                    if ("running".equals(status) || "pausing".equals(status) || "paused".equals(status)) {
                        run.put("status", "failed");
                        appendLog(run, "服务重启中断，未完成任务未自动续跑");
                        persist.accept(run);
                    }
                } catch (Exception ignored) { }
            });
        }
    }

    private void execute(TaskControl control) {
        try {
            JSONArray cases = control.run.getJSONArray("cases");
            for (int i = 0; i < cases.size() && !control.stopRequested; i++) {
                JSONObject caseItem = cases.getJSONObject(i);
                String caseId = caseItem.getString("id");
                if (control.onlyCaseId != null && !control.onlyCaseId.equals(caseId)) continue;
                executeCase(control, caseItem);
                if (control.onlyCaseId != null) break;
            }
            if (control.stopRequested) {
                control.run.put("status", "stopped");
                appendLog(control.run, "任务已停止");
            } else {
                control.run.put("status", "completed");
                control.run.put("finishedAt", now());
                appendLog(control.run, "任务已完成");
            }
        } catch (Exception error) {
            control.run.put("status", "failed");
            appendLog(control.run, "任务异常: " + message(error));
        } finally {
            control.persist.accept(control.run);
            activeTask.compareAndSet(control, null);
        }
    }

    private void executeCase(TaskControl control, JSONObject caseItem) throws Exception {
        String caseId = caseItem.getString("id");
        JSONArray vendors = control.run.getJSONArray("selectedVendors");
        byte[] pcm;
        try {
            appendLog(control.run, "解码 case: " + caseItem.getString("name"));
            pcm = decodePcm(caseItem);
        } catch (Exception error) {
            for (int i = 0; i < vendors.size(); i++) setFailure(caseItem, vendors.getString(i), control.run.getString("evaluationMode"), "decode", message(error), null, null, "failed");
            appendLog(control.run, "音频解码失败: " + message(error));
            control.persist.accept(control.run);
            return;
        }

        for (int i = 0; i < vendors.size() && !control.stopRequested; i++) {
            waitIfPaused(control);
            if (control.stopRequested) return;
            String vendor = vendors.getString(i);
            JSONObject result = result(caseItem, vendor);
            result.put("status", "running");
            result.put("phase", "recognizing");
            result.put("errorMsg", "");
            appendLog(control.run, "运行 " + caseItem.getString("name") + " / " + vendor);
            control.persist.accept(control.run);
            try {
                AsrVendorRunner.VendorOutcome outcome = vendorRunner.run(vendor, pcm, () -> control.stopRequested);
                if (control.stopRequested) return;
                JSONObject scoreRequest = scoreRequest(caseItem, outcome.transcript, control.run.getString("evaluationMode"));
                try {
                    log.info(
                            "run={} case={} vendor={} scoring start transcriptLen={} firstLatencyMs={} finalLatencyMs={}",
                            control.run.getString("runId"),
                            caseItem.getString("name"),
                            vendor,
                            outcome.transcript == null ? 0 : outcome.transcript.length(),
                            outcome.firstLatencyMs,
                            outcome.finalLatencyMs
                    );
                    JSONObject score = evaluatorClient.score(scoreRequest);
                    applyScore(result, score, outcome);
                    log.info(
                            "run={} case={} vendor={} scoring success cer={} wer={} entityAccuracy={} pass={} normalizerVersion={}",
                            control.run.getString("runId"),
                            caseItem.getString("name"),
                            vendor,
                            score.get("cer"),
                            score.get("wer"),
                            score.get("entityAccuracy"),
                            score.get("pass"),
                            score.get("normalizerVersion")
                    );
                    appendLog(control.run, caseItem.getString("name") + " / " + vendor + " 完成");
                } catch (Exception scoreError) {
                    log.warn(
                            "run={} case={} vendor={} scoring failed error={}",
                            control.run.getString("runId"),
                            caseItem.getString("name"),
                            vendor,
                            message(scoreError)
                    );
                    setFailure(caseItem, vendor, control.run.getString("evaluationMode"), "scoring", message(scoreError), outcome.transcript, outcome, "failed");
                    appendLog(control.run, caseItem.getString("name") + " / " + vendor + " 评分失败");
                }
            } catch (Exception recognitionError) {
                if (control.stopRequested) return;
                String status = recognitionError instanceof TimeoutException ? "timeout" : "failed";
                setFailure(caseItem, vendor, control.run.getString("evaluationMode"), "recognition", message(recognitionError), null, null, status);
                appendLog(control.run, caseItem.getString("name") + " / " + vendor + " 识别失败: " + message(recognitionError));
            }
            control.persist.accept(control.run);
        }
    }

    private void waitIfPaused(TaskControl control) throws InterruptedException {
        if (!control.pauseRequested) return;
        synchronized (control.monitor) {
            control.run.put("status", "paused");
            appendLog(control.run, "任务已暂停");
            control.persist.accept(control.run);
            while (control.pauseRequested && !control.stopRequested) control.monitor.wait();
        }
    }

    private void applyScore(JSONObject result, JSONObject score, AsrVendorRunner.VendorOutcome outcome) {
        result.put("status", "done");
        result.put("phase", "done");
        result.put("failureStage", null);
        result.put("transcript", outcome.transcript);
        result.put("firstLatencyMs", outcome.firstLatencyMs);
        result.put("finalLatencyMs", outcome.finalLatencyMs);
        for (String key : new String[] {"normalizerVersion", "scoringVersion", "referenceVariantUsed", "normalizedReference", "normalizedTranscript", "cer", "wer", "sentenceAccuracy", "entityAccuracy", "entityMatchedCount", "entityTotalCount", "entityMissedTerms", "characterSubstitutions", "characterInsertions", "characterDeletions", "wordSubstitutions", "wordInsertions", "wordDeletions", "pass", "passReason"}) {
            result.put(key, score.get(key));
        }
        result.put("errorMsg", "");
    }

    private void applyStoredScore(JSONObject result, JSONObject score) {
        for (String key : new String[] {"normalizerVersion", "scoringVersion", "referenceVariantUsed", "normalizedReference", "normalizedTranscript", "cer", "wer", "sentenceAccuracy", "entityAccuracy", "entityMatchedCount", "entityTotalCount", "entityMissedTerms", "characterSubstitutions", "characterInsertions", "characterDeletions", "wordSubstitutions", "wordInsertions", "wordDeletions", "pass", "passReason"}) {
            result.put(key, score.get(key));
        }
        result.put("failureStage", null);
        result.put("errorMsg", "");
    }

    private void setFailure(JSONObject caseItem, String vendor, String mode, String stage, String error, String transcript, AsrVendorRunner.VendorOutcome outcome, String status) {
        JSONObject result = result(caseItem, vendor);
        result.put("vendor", vendor);
        result.put("status", status);
        result.put("phase", status);
        result.put("textMode", mode);
        result.put("failureStage", stage);
        result.put("transcript", transcript == null ? "" : transcript);
        result.put("firstLatencyMs", outcome == null ? null : outcome.firstLatencyMs);
        result.put("finalLatencyMs", outcome == null ? null : outcome.finalLatencyMs);
        result.put("normalizerVersion", null);
        result.put("normalizedReference", "");
        result.put("normalizedTranscript", "");
        result.put("cer", null);
        result.put("wer", null);
        result.put("sentenceAccuracy", null);
        result.put("entityAccuracy", null);
        result.put("entityMatchedCount", null);
        result.put("entityTotalCount", null);
        result.put("entityMissedTerms", new JSONArray());
        result.put("characterSubstitutions", null);
        result.put("characterInsertions", null);
        result.put("characterDeletions", null);
        result.put("wordSubstitutions", null);
        result.put("wordInsertions", null);
        result.put("wordDeletions", null);
        result.put("pass", false);
        result.put("passReason", "运行或评分失败");
        result.put("errorMsg", error);
    }

    private JSONObject idleResult(String vendor, String mode) {
        JSONObject result = new JSONObject();
        result.put("vendor", vendor);
        result.put("status", "idle");
        result.put("phase", "queued");
        result.put("textMode", mode == null ? "loose" : mode);
        result.put("transcript", "");
        result.put("normalizedTranscript", "");
        result.put("normalizedReference", "");
        result.put("firstLatencyMs", null);
        result.put("finalLatencyMs", null);
        result.put("cer", null);
        result.put("wer", null);
        result.put("sentenceAccuracy", null);
        result.put("entityAccuracy", null);
        result.put("entityMatchedCount", null);
        result.put("entityTotalCount", null);
        result.put("entityMissedTerms", new JSONArray());
        result.put("characterSubstitutions", null);
        result.put("characterInsertions", null);
        result.put("characterDeletions", null);
        result.put("wordSubstitutions", null);
        result.put("wordInsertions", null);
        result.put("wordDeletions", null);
        result.put("pass", null);
        result.put("passReason", null);
        result.put("errorMsg", "");
        return result;
    }

    private JSONObject result(JSONObject caseItem, String vendor) {
        JSONObject vendors = caseItem.getJSONObject("vendors");
        JSONObject result = vendors.getJSONObject(vendor);
        if (result == null) {
            result = idleResult(vendor, "loose");
            vendors.put(vendor, result);
        }
        return result;
    }

    private JSONObject readCaseMeta(String id) throws Exception {
        Path meta = caseDir(id).resolve("meta.json");
        if (!Files.exists(meta)) throw new IllegalArgumentException("找不到 case: " + id);
        return JSONObject.parseObject(new String(Files.readAllBytes(meta), StandardCharsets.UTF_8));
    }

    private byte[] decodePcm(JSONObject caseItem) {
        String value = caseItem.getString("pcmDataBase64");
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Run 缺少 PCM 音频数据");
        return Base64.getDecoder().decode(value);
    }

    private Path caseDir(String id) { return Paths.get(casesDir).toAbsolutePath().resolve(id); }
    private String valueOr(JSONObject object, String key, String fallback) { String value = object.getString(key); return value == null ? fallback : value; }
    private String now() { return LocalDateTime.now().format(FMT); }
    private String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }

    private JSONArray splitTerms(String values) {
        JSONArray result = new JSONArray();
        if (values == null) return result;
        for (String term : values.split("[,，\\n]")) if (!term.trim().isEmpty()) result.add(term.trim());
        return result;
    }

    private JSONArray splitAlternatives(String values) {
        JSONArray result = new JSONArray();
        if (values == null) return result;
        for (String value : values.split("\\r?\\n")) if (!value.trim().isEmpty()) result.add(value.trim());
        return result;
    }

    private JSONObject scoreRequest(JSONObject caseItem, String transcript, String evaluationMode) {
        JSONObject request = new JSONObject();
        request.put("referenceText", caseItem.getString("referenceText"));
        request.put("transcript", transcript == null ? "" : transcript);
        request.put("textMode", evaluationMode);
        request.put("criticalTerms", splitTerms(caseItem.getString("criticalTermsText")));
        request.put("acceptableTexts", splitAlternatives(caseItem.getString("acceptableTextsText")));
        request.put("passRuleType", caseItem.getString("passRuleType"));
        request.put("passThreshold", caseItem.getDoubleValue("passThreshold"));
        return request;
    }

    private void appendLog(JSONObject run, String text) {
        JSONArray logs = run.getJSONArray("logs");
        if (logs == null) { logs = new JSONArray(); run.put("logs", logs); }
        JSONObject line = new JSONObject();
        line.put("time", now());
        line.put("text", text);
        logs.add(line);
    }

    @PreDestroy public void shutdown() { executor.shutdownNow(); }

    private static class TaskControl {
        final JSONObject run;
        final Consumer<JSONObject> persist;
        final String onlyCaseId;
        final Object monitor = new Object();
        volatile boolean pauseRequested;
        volatile boolean stopRequested;
        TaskControl(JSONObject run, Consumer<JSONObject> persist, String onlyCaseId) { this.run = run; this.persist = persist; this.onlyCaseId = onlyCaseId; }
    }
}
