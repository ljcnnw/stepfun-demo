package org.example.asr.handler;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.example.asr.eval.EvalRunExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/asr-eval/runs")
@CrossOrigin(origins = "*")
public class EvalRunController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Value("${bench.runs.dir:./asr-eval-runs}")
    private String runsDir;

    private Path runsRoot;
    private final EvalRunExecutor runExecutor;

    public EvalRunController(EvalRunExecutor runExecutor) {
        this.runExecutor = runExecutor;
    }

    @PostConstruct
    public void init() throws IOException {
        runsRoot = Paths.get(runsDir).toAbsolutePath();
        Files.createDirectories(runsRoot);
        try {
            runExecutor.recoverInterruptedRuns(runsRoot, this::writeRun);
        } catch (Exception ignored) {
            // A malformed historical run must not block application startup.
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JSONObject> create(@RequestBody JSONObject payload) throws Exception {
        JSONObject run = normalizeRunRecord(payload, true);
        runExecutor.prepareRun(run);
        if (!runExecutor.start(run, this::writeRun)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error("已有评估任务正在运行"));
        }
        return ResponseEntity.ok(run);
    }

    @PostMapping("/{runId}/pause")
    public ResponseEntity<JSONObject> pause(@PathVariable String runId) throws IOException {
        return commandResponse(runId, runExecutor.pause(runId), "任务当前不可暂停");
    }

    @PostMapping("/{runId}/resume")
    public ResponseEntity<JSONObject> resume(@PathVariable String runId) throws IOException {
        return commandResponse(runId, runExecutor.resume(runId), "任务当前不可继续");
    }

    @PostMapping("/{runId}/stop")
    public ResponseEntity<JSONObject> stop(@PathVariable String runId) throws IOException {
        return commandResponse(runId, runExecutor.stop(runId), "任务当前不可停止");
    }

    @PostMapping("/{runId}/cases/{caseId}/rerun")
    public ResponseEntity<JSONObject> rerunCase(@PathVariable String runId, @PathVariable String caseId) throws IOException {
        Path runPath = runPath(runId);
        if (!Files.exists(runPath)) return ResponseEntity.notFound().build();
        JSONObject run = readRun(runPath);
        boolean exists = run.getJSONArray("cases").stream().anyMatch(item -> caseId.equals(((JSONObject) item).getString("id")));
        if (!exists) return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error("该任务不包含指定 case"));
        if (!runExecutor.rerun(run, caseId, this::writeRun)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error("已有评估任务正在运行"));
        }
        return ResponseEntity.ok(run);
    }

    @PostMapping("/{runId}/rescore")
    public ResponseEntity<JSONObject> rescore(@PathVariable String runId) throws Exception {
        Path runPath = runPath(runId);
        if (!Files.exists(runPath)) return ResponseEntity.notFound().build();
        JSONObject run = normalizeRunRecord(readRun(runPath), false);
        if (runExecutor.hasActiveTask()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error("已有评估任务正在运行"));
        }
        runExecutor.rescore(run);
        run.put("updatedAt", now());
        recalculateSummary(run);
        writeRun(run);
        return ResponseEntity.ok(run);
    }

    @GetMapping
    public ResponseEntity<List<JSONObject>> list() throws IOException {
        List<JSONObject> result = new ArrayList<>();
        File[] dirs = runsRoot.toFile().listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                Path runPath = dir.toPath().resolve("run.json");
                if (!Files.exists(runPath)) {
                    continue;
                }
                JSONObject run = normalizeRunRecord(readRun(runPath), false);
                result.add(toListItem(run));
            }
        }
        result.sort(Comparator.comparing(o -> o.getString("updatedAt"), Comparator.nullsLast(Comparator.reverseOrder())));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{runId}")
    public ResponseEntity<JSONObject> get(@PathVariable String runId) throws IOException {
        Path runPath = runPath(runId);
        if (!Files.exists(runPath)) {
            return ResponseEntity.notFound().build();
        }
        JSONObject run = normalizeRunRecord(readRun(runPath), false);
        return ResponseEntity.ok(run);
    }

    @PutMapping(value = "/{runId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JSONObject> update(@PathVariable String runId, @RequestBody JSONObject payload) throws IOException {
        Path runPath = runPath(runId);
        if (!Files.exists(runPath)) {
            return ResponseEntity.notFound().build();
        }
        JSONObject run = normalizeRunRecord(payload, false);
        run.put("runId", runId);
        run.put("updatedAt", now());
        if (run.getString("startedAt") == null) {
            run.put("startedAt", now());
        }
        writeRun(run);
        return ResponseEntity.ok(run);
    }

    @GetMapping("/{runId}/export")
    public ResponseEntity<byte[]> export(
            @PathVariable String runId,
            @RequestParam(value = "format", defaultValue = "json") String format) throws IOException {
        Path runPath = runPath(runId);
        if (!Files.exists(runPath)) {
            return ResponseEntity.notFound().build();
        }
        JSONObject run = readRun(runPath);
        byte[] bytes;
        String filename;
        if ("csv".equalsIgnoreCase(format)) {
            String csv = exportCsv(run);
            bytes = csv.getBytes(StandardCharsets.UTF_8);
            filename = runId + ".csv";
        } else {
            String json = run.toJSONString();
            bytes = json.getBytes(StandardCharsets.UTF_8);
            filename = runId + ".json";
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/octet-stream"))
                .body(bytes);
    }

    @DeleteMapping("/{runId}")
    public ResponseEntity<Void> delete(@PathVariable String runId) throws IOException {
        Path dir = runDir(runId);
        if (!Files.exists(dir)) {
            return ResponseEntity.notFound().build();
        }
        deleteRecursive(dir.toFile());
        return ResponseEntity.noContent().build();
    }

    private JSONObject normalizeRunRecord(JSONObject payload, boolean createIfMissing) {
        JSONObject run = payload == null ? new JSONObject() : JSONObject.parseObject(payload.toJSONString());
        if (createIfMissing && run.getString("runId") == null) {
            run.put("runId", "run_" + UUID.randomUUID().toString());
        }
        if (run.getString("name") == null || run.getString("name").trim().isEmpty()) {
            run.put("name", "评估任务 " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        }
        if (run.getString("status") == null) {
            run.put("status", "idle");
        }
        if (run.getString("evaluationMode") == null) {
            run.put("evaluationMode", "loose");
        }
        if (run.getString("startedAt") == null) {
            run.put("startedAt", now());
        }
        if (run.getString("updatedAt") == null) {
            run.put("updatedAt", now());
        }
        if (run.get("selectedVendors") == null) {
            run.put("selectedVendors", new JSONArray());
        }
        if (run.get("selectedCaseIds") == null) {
            run.put("selectedCaseIds", new JSONArray());
        }
        if (run.get("cases") == null) {
            run.put("cases", new JSONArray());
        }
        if (run.get("logs") == null) {
            run.put("logs", new JSONArray());
        }
        recalculateSummary(run);
        return run;
    }

    private JSONObject toListItem(JSONObject run) {
        JSONObject item = new JSONObject();
        item.put("runId", run.getString("runId"));
        item.put("name", run.getString("name"));
        item.put("status", run.getString("status"));
        item.put("evaluationMode", run.getString("evaluationMode"));
        item.put("selectedVendors", run.getJSONArray("selectedVendors"));
        item.put("selectedCaseIds", run.getJSONArray("selectedCaseIds"));
        item.put("startedAt", run.getString("startedAt"));
        item.put("updatedAt", run.getString("updatedAt"));
        item.put("finishedAt", run.getString("finishedAt"));
        item.put("summary", run.getJSONObject("summary"));
        return item;
    }

    private void recalculateSummary(JSONObject run) {
        JSONArray cases = run.getJSONArray("cases");
        JSONArray vendorsArray = run.getJSONArray("selectedVendors");
        List<String> vendors = new ArrayList<>();
        if (vendorsArray != null) {
            for (int i = 0; i < vendorsArray.size(); i += 1) {
                vendors.add(vendorsArray.getString(i));
            }
        }

        int totalCases = cases == null ? 0 : cases.size();
        int completedCases = 0;
        int allPassedCases = 0;
        int anyPassedCases = 0;
        int noPassedCases = 0;
        int timeoutCases = 0;
        int failedCases = 0;
        int doneVendors = 0;
        int passedVendors = 0;
        int timeoutVendors = 0;
        int failureVendors = 0;

        JSONObject vendorSummaries = new JSONObject();
        for (String vendor : vendors) {
            vendorSummaries.put(vendor, new JSONObject());
        }

        if (cases != null) {
            for (int i = 0; i < cases.size(); i += 1) {
                JSONObject caseItem = cases.getJSONObject(i);
                JSONObject caseVendors = caseItem.getJSONObject("vendors");
                boolean caseCompleted = true;
                boolean allVendorsPassed = true;
                boolean anyVendorPassed = false;
                for (String vendor : vendors) {
                    JSONObject result = caseVendors == null ? null : caseVendors.getJSONObject(vendor);
                    if (result == null) {
                        caseCompleted = false;
                        allVendorsPassed = false;
                        continue;
                    }

                    String status = result.getString("status");
                    boolean terminal = "done".equals(status) || "failed".equals(status) || "timeout".equals(status);
                    if (!terminal) {
                        caseCompleted = false;
                    }
                    if (!Boolean.TRUE.equals(result.getBoolean("pass"))) {
                        allVendorsPassed = false;
                    } else {
                        anyVendorPassed = true;
                    }
                }
                if (caseCompleted) {
                    completedCases += 1;
                }
                if (allVendorsPassed && caseCompleted) {
                    allPassedCases += 1;
                } else if (caseCompleted) {
                    if (anyVendorPassed) {
                        failedCases += 1;
                    } else {
                        noPassedCases += 1;
                    }
                }
                if (caseCompleted && anyVendorPassed) {
                    anyPassedCases += 1;
                }

                boolean caseTimedOut = false;
                for (String vendor : vendors) {
                    JSONObject result = caseVendors == null ? null : caseVendors.getJSONObject(vendor);
                    if (result == null) {
                        continue;
                    }
                    String status = result.getString("status");
                    if ("done".equals(status) || "failed".equals(status) || "timeout".equals(status)) {
                        doneVendors += 1;
                    }
                    if ("timeout".equals(status)) {
                        timeoutVendors += 1;
                        caseTimedOut = true;
                    }
                    if ("failed".equals(status)) {
                        failureVendors += 1;
                    }
                    if (Boolean.TRUE.equals(result.getBoolean("pass"))) {
                        passedVendors += 1;
                    }
                    accumulateVendorSummary(vendorSummaries.getJSONObject(vendor), result);
                }
                if (caseTimedOut) {
                    timeoutCases += 1;
                }
            }
        }

        JSONArray summaryVendors = new JSONArray();
        for (String vendor : vendors) {
            JSONObject summary = vendorSummaries.getJSONObject(vendor);
            finalizeVendorSummary(summary, totalCases);
            summary.put("vendor", vendor);
            summaryVendors.add(summary);
        }

        JSONObject summary = new JSONObject();
        summary.put("totalCases", totalCases);
        summary.put("completedCases", completedCases);
        summary.put("passedCases", allPassedCases);
        summary.put("allPassedCases", allPassedCases);
        summary.put("anyPassedCases", anyPassedCases);
        summary.put("noPassedCases", noPassedCases);
        summary.put("failedCases", failedCases);
        summary.put("timeoutCases", timeoutCases);
        summary.put("totalVendors", totalCases * vendors.size());
        summary.put("doneVendors", doneVendors);
        summary.put("passedVendors", passedVendors);
        summary.put("timeoutVendors", timeoutVendors);
        summary.put("failureVendors", failureVendors);
        summary.put("vendors", summaryVendors);
        run.put("summary", summary);
    }

    private void accumulateVendorSummary(JSONObject summary, JSONObject result) {
        if (summary == null || result == null) {
            return;
        }
        int total = summary.getIntValue("total");
        summary.put("total", total + 1);
        if (Boolean.TRUE.equals(result.getBoolean("pass"))) {
            summary.put("passed", summary.getIntValue("passed") + 1);
        }
        addMetric(summary, "cerSum", "cerCount", result.get("cer"));
        addMetric(summary, "werSum", "werCount", result.get("wer"));
        addMetric(summary, "firstLatencySum", "firstLatencyCount", result.get("firstLatencyMs"));
        addMetric(summary, "finalLatencySum", "finalLatencyCount", result.get("finalLatencyMs"));
        addMetric(summary, "entitySum", "entityCount", result.get("entityAccuracy"));
        String status = result.getString("status");
        if ("timeout".equals(status)) {
            summary.put("timeout", summary.getIntValue("timeout") + 1);
        }
        if ("failed".equals(status)) {
            summary.put("failure", summary.getIntValue("failure") + 1);
        }
    }

    private void addMetric(JSONObject summary, String sumKey, String countKey, Object value) {
        if (!(value instanceof Number)) {
            return;
        }
        summary.put(sumKey, summary.getDoubleValue(sumKey) + ((Number) value).doubleValue());
        summary.put(countKey, summary.getIntValue(countKey) + 1);
    }

    private void finalizeVendorSummary(JSONObject summary, int totalCases) {
        int total = summary.getIntValue("total");
        int passed = summary.getIntValue("passed");
        summary.put("completed", total);
        summary.put("passed", passed);
        summary.put("timeoutCount", summary.getIntValue("timeout"));
        summary.put("failureCount", summary.getIntValue("failure"));
        summary.put("passRate", total > 0 ? (double) passed / total : 0.0);
        summary.put("avgCer", avgOrZero(summary, "cerSum", "cerCount"));
        summary.put("avgWer", avgOrZero(summary, "werSum", "werCount"));
        summary.put("avgFirstLatencyMs", avgOrZero(summary, "firstLatencySum", "firstLatencyCount"));
        summary.put("avgFinalLatencyMs", avgOrZero(summary, "finalLatencySum", "finalLatencyCount"));
        summary.put("entityAccuracy", avgOrZero(summary, "entitySum", "entityCount"));
        summary.put("timeoutRate", totalCases > 0 ? (double) summary.getIntValue("timeout") / totalCases : 0.0);
        summary.put("failureRate", totalCases > 0 ? (double) summary.getIntValue("failure") / totalCases : 0.0);
        summary.remove("cerSum");
        summary.remove("werSum");
        summary.remove("cerCount");
        summary.remove("werCount");
        summary.remove("firstLatencySum");
        summary.remove("firstLatencyCount");
        summary.remove("finalLatencySum");
        summary.remove("finalLatencyCount");
        summary.remove("entitySum");
        summary.remove("entityCount");
        summary.remove("timeout");
        summary.remove("failure");
        summary.remove("total");
    }

    private Double avg(JSONObject summary, String sumKey, String countKey) {
        int count = summary.getIntValue(countKey);
        if (count <= 0) {
            return null;
        }
        return summary.getDoubleValue(sumKey) / count;
    }

    private Double avgOrZero(JSONObject summary, String sumKey, String countKey) {
        int count = summary.getIntValue(countKey);
        if (count <= 0) {
            return 0.0;
        }
        return summary.getDoubleValue(sumKey) / count;
    }

    private synchronized void writeRun(JSONObject run) {
        try {
            run.put("updatedAt", now());
            run = normalizeRunRecord(run, false);
            Path dir = runDir(run.getString("runId"));
            Files.createDirectories(dir);
            Files.write(dir.resolve("run.json"), run.toJSONString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalStateException("保存 run 失败", error);
        }
    }

    private ResponseEntity<JSONObject> commandResponse(String runId, boolean accepted, String message) throws IOException {
        if (!Files.exists(runPath(runId))) return ResponseEntity.notFound().build();
        if (!accepted) return ResponseEntity.status(HttpStatus.CONFLICT).body(error(message));
        return ResponseEntity.ok(readRun(runPath(runId)));
    }

    private JSONObject error(String message) {
        JSONObject result = new JSONObject();
        result.put("message", message);
        return result;
    }

    private JSONObject readRun(Path path) throws IOException {
        String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        return JSONObject.parseObject(json);
    }

    private Path runDir(String runId) {
        return runsRoot.resolve(runId);
    }

    private Path runPath(String runId) {
        return runDir(runId).resolve("run.json");
    }

    private String now() {
        return LocalDateTime.now().format(FMT);
    }

    private String exportCsv(JSONObject run) {
        StringBuilder sb = new StringBuilder();
        sb.append("runId,runName,caseId,caseName,sourceCaseId,noiseProfile,noiseType,targetSnrDb,vendor,status,phase,pass,passReason,scoringVersion,cer,wer,sentenceAccuracy,entityAccuracy,entityMatchedCount,entityTotalCount,firstLatencyMs,finalLatencyMs,errorMsg,transcript\n");
        JSONArray cases = run.getJSONArray("cases");
        if (cases != null) {
            for (int i = 0; i < cases.size(); i += 1) {
                JSONObject caseItem = cases.getJSONObject(i);
                JSONObject vendors = caseItem.getJSONObject("vendors");
                if (vendors == null) continue;
                for (String key : vendors.keySet()) {
                    JSONObject result = vendors.getJSONObject(key);
                    if (result == null) continue;
                    sb.append(csv(run.getString("runId"))).append(',');
                    sb.append(csv(run.getString("name"))).append(',');
                    sb.append(csv(caseItem.getString("id"))).append(',');
                    sb.append(csv(caseItem.getString("name"))).append(',');
                    sb.append(csv(caseItem.getString("sourceCaseId"))).append(',');
                    sb.append(csv(caseItem.getString("noiseProfile"))).append(',');
                    sb.append(csv(caseItem.getString("noiseType"))).append(',');
                    sb.append(csv(String.valueOf(caseItem.get("targetSnrDb")))).append(',');
                    sb.append(csv(key)).append(',');
                    sb.append(csv(result.getString("status"))).append(',');
                    sb.append(csv(result.getString("phase"))).append(',');
                    sb.append(csv(String.valueOf(result.getBoolean("pass")))).append(',');
                    sb.append(csv(result.getString("passReason"))).append(',');
                    sb.append(csv(result.getString("scoringVersion"))).append(',');
                    sb.append(csv(String.valueOf(result.get("cer")))).append(',');
                    sb.append(csv(String.valueOf(result.get("wer")))).append(',');
                    sb.append(csv(String.valueOf(result.get("sentenceAccuracy")))).append(',');
                    sb.append(csv(String.valueOf(result.get("entityAccuracy")))).append(',');
                    sb.append(csv(String.valueOf(result.get("entityMatchedCount")))).append(',');
                    sb.append(csv(String.valueOf(result.get("entityTotalCount")))).append(',');
                    sb.append(csv(String.valueOf(result.get("firstLatencyMs")))).append(',');
                    sb.append(csv(String.valueOf(result.get("finalLatencyMs")))).append(',');
                    sb.append(csv(result.getString("errorMsg"))).append(',');
                    sb.append(csv(result.getString("transcript"))).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private String csv(String input) {
        String value = input == null ? "" : input.replace("\"", "\"\"");
        return "\"" + value + "\"";
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            for (File child : Objects.requireNonNull(f.listFiles())) {
                deleteRecursive(child);
            }
        }
        f.delete();
    }
}
