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
            @RequestParam("audio") MultipartFile audio,
            @RequestParam("name") String name,
            @RequestParam(value = "note", defaultValue = "") String note,
            @RequestParam("durationSeconds") double durationSeconds) throws IOException {

        String id = UUID.randomUUID().toString();
        String originalFilename = Optional.ofNullable(audio.getOriginalFilename()).orElse("audio");
        String ext = originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf('.'))
                : ".bin";

        Path dir = Paths.get(casesDir, id);
        Files.createDirectories(dir);

        Path audioPath = dir.resolve("audio" + ext);
        audio.transferTo(audioPath.toFile());

        JSONObject meta = new JSONObject();
        meta.put("id", id);
        meta.put("name", name);
        meta.put("note", note);
        meta.put("originalFileName", originalFilename);
        meta.put("audioExt", ext);
        meta.put("durationSeconds", durationSeconds);
        meta.put("createdAt", LocalDateTime.now().format(FMT));

        Files.write(dir.resolve("meta.json"), meta.toJSONString().getBytes(StandardCharsets.UTF_8));
        log.info("【BenchCase 保存】id={}, name={}", id, name);
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
                    result.add(JSONObject.parseObject(json));
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
        Path audioPath = dir.resolve("audio" + ext);
        if (!Files.exists(audioPath)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(audioPath);
        String mimeType = Files.probeContentType(audioPath);
        if (mimeType == null) mimeType = "application/octet-stream";
        String encodedName = URLEncoder.encode(meta.getString("originalFileName"), StandardCharsets.UTF_8.name())
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedName)
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
}
