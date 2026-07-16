package org.example.asr.handler;

import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/asr-bench/cases")
@CrossOrigin(origins = "*")
public class BenchCaseController {

    private static final Logger log = LoggerFactory.getLogger(BenchCaseController.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Value("${bench.cases.dir:./asr-test-cases}")
    private String casesDir;

    @PostConstruct
    public void init() throws IOException {
        // 将相对路径转为绝对路径，避免 Tomcat 将 transferTo 解析到临时工作目录
        casesDir = Paths.get(casesDir).toAbsolutePath().toString();
        Files.createDirectories(Paths.get(casesDir));
        log.info("【BenchCase】存储目录: {}", casesDir);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JSONObject> upload(
            @RequestParam(value = "audio", required = false) MultipartFile audio,
            @RequestParam("name") String name,
            @RequestParam(value = "note", defaultValue = "") String note,
            @RequestParam("durationSeconds") double durationSeconds,
            @RequestParam(value = "caseType", required = false) String caseType,
            @RequestParam(value = "referenceText", required = false) String referenceText,
            @RequestParam(value = "criticalTermsText", required = false) String criticalTermsText,
            @RequestParam(value = "acceptableTextsText", required = false) String acceptableTextsText,
            @RequestParam(value = "passRuleType", required = false) String passRuleType,
            @RequestParam(value = "passThreshold", required = false) Double passThreshold,
            @RequestParam(value = "enabled", required = false) Boolean enabled) throws IOException {

        String id = UUID.randomUUID().toString();
        Path dir = Paths.get(casesDir, id);
        Files.createDirectories(dir);

        boolean hasAudio = audio != null && !audio.isEmpty();
        String originalFilename = "";
        String ext = "";
        if (hasAudio) {
            originalFilename = Optional.ofNullable(audio.getOriginalFilename()).orElse("audio");
            ext = getAudioExtension(originalFilename);
            audio.transferTo(dir.resolve("audio" + ext).toFile());
        }

        JSONObject meta = new JSONObject();
        meta.put("id", id);
        meta.put("name", name);
        meta.put("note", note);
        meta.put("originalFileName", originalFilename);
        meta.put("audioExt", ext);
        meta.put("hasAudio", hasAudio);
        meta.put("durationSeconds", durationSeconds);
        meta.put("createdAt", LocalDateTime.now().format(FMT));
        applyOptionalFields(meta, caseType, referenceText, criticalTermsText, acceptableTextsText, passRuleType, passThreshold, enabled);

        Files.write(dir.resolve("meta.json"), meta.toJSONString().getBytes(StandardCharsets.UTF_8));
        log.info("【BenchCase 保存】id={}, name={}, hasAudio={}", id, name, hasAudio);
        return ResponseEntity.ok(meta);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JSONObject> update(
            @PathVariable String id,
            @RequestBody JSONObject payload) throws IOException {
        Path dir = Paths.get(casesDir, id);
        Path metaPath = dir.resolve("meta.json");
        if (!Files.exists(metaPath)) {
            return ResponseEntity.notFound().build();
        }

        String json = new String(Files.readAllBytes(metaPath), StandardCharsets.UTF_8);
        JSONObject meta = JSONObject.parseObject(json);

        copyMutableFields(meta, payload);

        Files.write(metaPath, meta.toJSONString().getBytes(StandardCharsets.UTF_8));
        decorateAudioState(dir, meta);
        log.info("【BenchCase 更新】id={}, name={}, hasAudio={}", id, meta.getString("name"), meta.getBooleanValue("hasAudio"));
        return ResponseEntity.ok(meta);
    }

    @PutMapping(value = "/{id}/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JSONObject> updateAudio(
            @PathVariable String id,
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(value = "durationSeconds", required = false) Double durationSeconds) throws IOException {
        if (audio.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        Path dir = Paths.get(casesDir, id);
        Path metaPath = dir.resolve("meta.json");
        if (!Files.exists(metaPath)) {
            return ResponseEntity.notFound().build();
        }

        JSONObject meta = JSONObject.parseObject(new String(Files.readAllBytes(metaPath), StandardCharsets.UTF_8));
        String previousExt = Optional.ofNullable(meta.getString("audioExt")).orElse("");
        String originalFilename = Optional.ofNullable(audio.getOriginalFilename()).orElse("audio");
        String ext = getAudioExtension(originalFilename);
        Path audioPath = dir.resolve("audio" + ext);
        audio.transferTo(audioPath.toFile());

        if (!previousExt.isEmpty() && !previousExt.equals(ext)) {
            Files.deleteIfExists(dir.resolve("audio" + previousExt));
        }

        meta.put("originalFileName", originalFilename);
        meta.put("audioExt", ext);
        meta.put("hasAudio", true);
        if (durationSeconds != null) {
            meta.put("durationSeconds", durationSeconds);
        }
        Files.write(metaPath, meta.toJSONString().getBytes(StandardCharsets.UTF_8));
        log.info("【BenchCase 音频更新】id={}, fileName={}, durationSeconds={}", id, originalFilename, durationSeconds);
        return ResponseEntity.ok(meta);
    }

    @GetMapping
    public ResponseEntity<List<JSONObject>> list() throws IOException {
        Path root = Paths.get(casesDir);
        List<JSONObject> result = new ArrayList<>();
        File[] dirs = root.toFile().listFiles(File::isDirectory);
        if (dirs != null) {
            for (File d : dirs) {
                Path metaPath = d.toPath().resolve("meta.json");
                if (Files.exists(metaPath)) {
                    String json = new String(Files.readAllBytes(metaPath), StandardCharsets.UTF_8);
                    JSONObject meta = JSONObject.parseObject(json);
                    decorateAudioState(d.toPath(), meta);
                    result.add(meta);
                }
            }
        }
        result.sort(Comparator.comparing(
                o -> o.getString("createdAt"),
                Comparator.reverseOrder()));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/audio")
    public ResponseEntity<Resource> download(@PathVariable String id) throws IOException {
        Path dir = Paths.get(casesDir, id);
        Path metaPath = dir.resolve("meta.json");
        if (!Files.exists(metaPath)) {
            return ResponseEntity.notFound().build();
        }
        String json = new String(Files.readAllBytes(metaPath), StandardCharsets.UTF_8);
        JSONObject meta = JSONObject.parseObject(json);
        String ext = meta.getString("audioExt");
        if (ext == null || ext.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path audioPath = dir.resolve("audio" + ext);
        if (!Files.exists(audioPath)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(audioPath);
        String mimeType = mediaTypeForExtension(ext);
        String originalFileName = Optional.ofNullable(meta.getString("originalFileName")).orElse("audio" + ext);
        String encodedName = URLEncoder.encode(originalFileName, StandardCharsets.UTF_8.name())
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.parseMediaType(mimeType))
                .body(resource);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        Path dir = Paths.get(casesDir, id);
        if (!Files.exists(dir)) {
            return ResponseEntity.notFound().build();
        }
        deleteRecursive(dir.toFile());
        log.info("【BenchCase 删除】id={}", id);
        return ResponseEntity.noContent().build();
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            for (File child : Objects.requireNonNull(f.listFiles())) deleteRecursive(child);
        }
        f.delete();
    }

    private void applyOptionalFields(
            JSONObject meta,
            String caseType,
            String referenceText,
            String criticalTermsText,
            String acceptableTextsText,
            String passRuleType,
            Double passThreshold,
            Boolean enabled) {
        if (caseType != null) meta.put("caseType", caseType);
        if (referenceText != null) meta.put("referenceText", referenceText);
        if (criticalTermsText != null) meta.put("criticalTermsText", criticalTermsText);
        if (acceptableTextsText != null) meta.put("acceptableTextsText", acceptableTextsText);
        if (passRuleType != null) meta.put("passRuleType", passRuleType);
        if (passThreshold != null) meta.put("passThreshold", passThreshold);
        if (enabled != null) meta.put("enabled", enabled);
    }

    private void copyMutableFields(JSONObject meta, JSONObject payload) {
        if (payload == null) return;

        putIfPresent(meta, payload, "name");
        putIfPresent(meta, payload, "note");
        putIfPresent(meta, payload, "caseType");
        putIfPresent(meta, payload, "referenceText");
        putIfPresent(meta, payload, "criticalTermsText");
        putIfPresent(meta, payload, "acceptableTextsText");
        putIfPresent(meta, payload, "passRuleType");
        putIfPresent(meta, payload, "passThreshold");
        putIfPresent(meta, payload, "enabled");
        putIfPresent(meta, payload, "durationSeconds");
    }

    private void putIfPresent(JSONObject meta, JSONObject payload, String key) {
        if (!payload.containsKey(key)) return;
        Object value = payload.get(key);
        meta.put(key, value);
    }

    private String getAudioExtension(String originalFilename) {
        if (originalFilename == null || !originalFilename.contains(".")) return ".bin";
        String ext = originalFilename.substring(originalFilename.lastIndexOf('.'));
        return ext.matches("\\.[A-Za-z0-9]{1,10}") ? ext : ".bin";
    }

    private void decorateAudioState(Path dir, JSONObject meta) {
        String ext = meta.getString("audioExt");
        boolean hasAudio = ext != null && !ext.isEmpty() && Files.exists(dir.resolve("audio" + ext));
        meta.put("hasAudio", hasAudio);
        if (!hasAudio) {
            meta.put("originalFileName", "");
            meta.put("audioExt", "");
        }
    }

    private String mediaTypeForExtension(String extension) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if (".webm".equals(ext)) return "audio/webm";
        if (".mp3".equals(ext)) return "audio/mpeg";
        if (".wav".equals(ext)) return "audio/wav";
        if (".ogg".equals(ext)) return "audio/ogg";
        if (".m4a".equals(ext) || ".mp4".equals(ext)) return "audio/mp4";
        return "application/octet-stream";
    }
}
