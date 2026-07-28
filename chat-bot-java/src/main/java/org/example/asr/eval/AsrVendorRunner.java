package org.example.asr.eval;

import org.example.asr.client.AliyunAsrClient;
import org.example.asr.client.FanoAsrClient;
import org.example.asr.client.StepfunWsClient;
import org.example.asr.client.VolcAsrClient;
import org.java_websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class AsrVendorRunner {
    private static final Logger log = LoggerFactory.getLogger(AsrVendorRunner.class);
    private static final int FRAME_BYTES = 3200;
    private static final int FRAME_INTERVAL_MS = 100;
    private static final long SEGMENT_SETTLE_MS = 1500;
    private static final long POST_FINISH_GRACE_MS = 2000;
    private static final long PENDING_SEGMENT_GRACE_MS = 5000;

    @Value("${stepfun.api.key}") private String stepfunApiKey;
    @Value("${stepfun.asr.url}") private String stepfunAsrUrl;
    @Value("${fano.asr.url}") private String fanoAsrUrl;
    @Value("${fano.asr.token}") private String fanoAsrToken;
    @Value("${aliyun.asr.url}") private String aliyunAsrUrl;
    @Value("${aliyun.asr.api-key}") private String aliyunAsrApiKey;
    @Value("${aliyun.asr.model:paraformer-realtime-v2}") private String aliyunAsrModel;
    @Value("${aliyun.asr.sample-rate:16000}") private int aliyunAsrSampleRate;
    @Value("${aliyun.asr.language-hints:}") private String aliyunAsrLanguageHints;
    @Value("${volc.asr.url}") private String volcAsrUrl;
    @Value("${volc.asr.api-key:}") private String volcAsrApiKey;
    @Value("${volc.asr.resource-id}") private String volcAsrResourceId;
    @Value("${volc.asr.request.model-name}") private String volcModelName;
    @Value("${volc.asr.request.enable-itn}") private boolean volcEnableItn;
    @Value("${volc.asr.request.enable-punc}") private boolean volcEnablePunc;
    @Value("${volc.asr.request.enable-ddc}") private boolean volcEnableDdc;
    @Value("${volc.asr.request.enable-nonstream}") private boolean volcEnableNonstream;
    @Value("${volc.asr.request.end-window-size}") private int volcEndWindowSize;
    @Value("${volc.asr.request.force-to-speech-time}") private int volcForceToSpeechTime;
    @Value("${volc.asr.request.output-zh-variant:}") private String volcOutputZhVariant;
    @Value("${volc.asr.request.enable-lid}") private boolean volcEnableLid;
    @Value("${volc.asr.request.result-type}") private String volcResultType;
    @Value("${volc.asr.request.boosting-table-id:}") private String volcBoostingTableId;
    @Value("${asr.eval.vendor-timeout-ms:180000}") private long vendorTimeoutMs;

    private final ConcurrentMap<String, WebSocketClient> activeClients = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<String>> activeTranscripts = new ConcurrentHashMap<>();

    public VendorOutcome run(String vendor, byte[] pcm, Cancellation cancellation) throws Exception {
        long startedAt = System.currentTimeMillis();
        if ("fano".equals(vendor)) {
            checkCancelled(cancellation);
            String transcript = new FanoAsrClient(fanoAsrUrl, fanoAsrToken).recognize(pcm);
            checkCancelled(cancellation);
            if (transcript == null || transcript.trim().isEmpty()) {
                throw new IllegalStateException("FANO 未返回识别文本");
            }
            long finalLatency = System.currentTimeMillis() - startedAt;
            // FANO 为同步批量接口，不存在可独立统计的流式首包时间。
            return new VendorOutcome(transcript.trim(), null, finalLatency);
        }
        AtomicLong firstLatency = new AtomicLong(-1);
        CompletableFuture<String> terminalSignal = new CompletableFuture<>();
        String executionId = vendor + "_" + System.nanoTime();
        activeTranscripts.put(executionId, terminalSignal);
        NoopWebSocketSession session = new NoopWebSocketSession("eval_" + System.nanoTime());
        Object transcriptLock = new Object();
        List<String> transcriptSegments = new ArrayList<>();
        AtomicInteger currentSegment = new AtomicInteger(0);
        AtomicLong lastTranscriptUpdateAt = new AtomicLong(-1);
        StepfunWsClient.AsrEventListener listener = new StepfunWsClient.AsrEventListener() {
            @Override public void onSpeechConfirmed() {
                firstLatency.compareAndSet(-1, System.currentTimeMillis() - startedAt);
                int nextSegment = currentSegment.incrementAndGet();
                log.info("【评测 ASR 分段】vendor={}，事件=speech_confirmed，segmentIndex={}", vendor, nextSegment);
            }
            @Override public void onUserSpeechCompleted(String value) {
                String transcript = value == null ? "" : value.trim();
                synchronized (transcriptLock) {
                    boolean pendingNewSegment = currentSegment.get() > transcriptSegments.size();
                    boolean appendedByCompletion = false;
                    if (!pendingNewSegment) {
                        String lastSegment = transcriptSegments.isEmpty() ? null : transcriptSegments.get(transcriptSegments.size() - 1);
                        boolean sameOrRefinedSegment = lastSegment != null
                                && !lastSegment.isEmpty()
                                && (lastSegment.equals(transcript)
                                || transcript.contains(lastSegment)
                                || lastSegment.contains(transcript));
                        if (!sameOrRefinedSegment) {
                            currentSegment.incrementAndGet();
                            pendingNewSegment = true;
                            appendedByCompletion = true;
                        }
                    }
                    int segmentIndex = Math.max(currentSegment.get(), 1);
                    while (transcriptSegments.size() < segmentIndex) transcriptSegments.add("");
                    String previousValue = transcriptSegments.get(segmentIndex - 1);
                    transcriptSegments.set(segmentIndex - 1, transcript);
                    lastTranscriptUpdateAt.set(System.currentTimeMillis());
                    log.info("【评测 ASR 分段】vendor={}，事件=completed，segmentIndex={}，mode={}，previous={}，current={}",
                            vendor,
                            segmentIndex,
                            appendedByCompletion ? "append-on-completed" : "replace-or-confirm",
                            previousValue,
                            transcript);
                    transcriptLock.notifyAll();
                }
            }
            @Override public void onAsrError(String message) { terminalSignal.completeExceptionally(new IllegalStateException(message)); }
        };
        WebSocketClient client = createClient(vendor, session, listener);
        activeClients.put(executionId, client);
        try {
            client.connect();
            long connectionDeadline = System.currentTimeMillis() + 15000;
            while (!client.isOpen() && System.currentTimeMillis() < connectionDeadline) {
                checkCancelled(cancellation);
                Thread.sleep(25);
            }
            if (!client.isOpen()) throw new TimeoutException(vendor + " 建连超时");
            if ("volc".equals(vendor) && !((VolcAsrClient) client).awaitFullRequestSent(5000)) {
                throw new TimeoutException(vendor + " 初始化请求发送超时");
            }
            for (int offset = 0; offset < pcm.length; offset += FRAME_BYTES) {
                checkCancelled(cancellation);
                byte[] frame = new byte[FRAME_BYTES];
                System.arraycopy(pcm, offset, frame, 0, Math.min(FRAME_BYTES, pcm.length - offset));
                sendFrame(vendor, client, frame);
                Thread.sleep(FRAME_INTERVAL_MS);
            }
            for (int i = 0; i < 10; i++) {
                checkCancelled(cancellation);
                sendFrame(vendor, client, new byte[FRAME_BYTES]);
                Thread.sleep(FRAME_INTERVAL_MS);
            }
            long finishSentAt = System.currentTimeMillis();
            finish(vendor, client);
            log.info("【评测 ASR 收尾】vendor={}，finishSentAt={}，segments={}，currentSegment={}，lastTranscriptUpdateAt={}",
                    vendor, finishSentAt, transcriptSegments.size(), currentSegment.get(), lastTranscriptUpdateAt.get());
            String value = awaitTranscript(
                    vendor,
                    cancellation,
                    terminalSignal,
                    transcriptLock,
                    transcriptSegments,
                    currentSegment,
                    lastTranscriptUpdateAt,
                    finishSentAt
            );
            long finalLatency = System.currentTimeMillis() - startedAt;
            log.info("【评测 ASR 完成】vendor={}，transcript={}，firstLatencyMs={}，finalLatencyMs={}",
                    vendor, value, firstLatency.get() < 0 ? null : firstLatency.get(), finalLatency);
            return new VendorOutcome(value, firstLatency.get() < 0 ? null : firstLatency.get(), finalLatency);
        } finally {
            activeTranscripts.remove(executionId, terminalSignal);
            if (activeClients.remove(executionId, client)) client.close();
        }
    }

    public void cancelCurrent() {
        activeTranscripts.forEach((id, transcript) -> transcript.completeExceptionally(new InterruptedException("任务已停止")));
        activeClients.forEach((id, client) -> client.close());
    }

    private WebSocketClient createClient(String vendor, NoopWebSocketSession session, StepfunWsClient.AsrEventListener listener) throws Exception {
        if ("stepfun".equals(vendor)) {
            StepfunWsClient client = new StepfunWsClient(new URI(stepfunAsrUrl), stepfunApiKey, session);
            client.setListener(listener);
            client.setDeliverAllCompletedEvents(true);
            return client;
        }
        if ("aliyun".equals(vendor)) {
            AliyunAsrClient client = new AliyunAsrClient(
                    aliyunAsrUrl,
                    aliyunAsrApiKey,
                    session,
                    aliyunAsrModel,
                    aliyunAsrSampleRate,
                    aliyunAsrLanguageHints);
            client.setListener(listener);
            return client;
        }
        if ("volc".equals(vendor)) {
            VolcAsrClient client = new VolcAsrClient(volcAsrUrl, volcAsrApiKey, volcAsrResourceId, session,
                    volcModelName, volcEnableItn, volcEnablePunc, volcEnableDdc, volcEnableNonstream,
                    volcEndWindowSize, volcForceToSpeechTime, volcOutputZhVariant, volcEnableLid, volcResultType,
                    volcEndWindowSize, volcBoostingTableId);
            client.setListener(listener);
            return client;
        }
        throw new IllegalArgumentException("不支持的厂商: " + vendor);
    }

    private void sendFrame(String vendor, WebSocketClient client, byte[] frame) {
        if ("stepfun".equals(vendor)) ((StepfunWsClient) client).sendAudioFrame(frame);
        else if ("aliyun".equals(vendor)) ((AliyunAsrClient) client).sendAudioFrame(frame);
        else ((VolcAsrClient) client).sendAudioFrame(frame);
    }

    private void finish(String vendor, WebSocketClient client) {
        if ("stepfun".equals(vendor)) ((StepfunWsClient) client).sendCommit();
        else if ("aliyun".equals(vendor)) ((AliyunAsrClient) client).sendFinishTask();
        else ((VolcAsrClient) client).sendFinishFrame();
    }

    private void checkCancelled(Cancellation cancellation) throws InterruptedException {
        if (cancellation.isCancelled()) throw new InterruptedException("任务已停止");
    }

    private String awaitTranscript(
            String vendor,
            Cancellation cancellation,
            CompletableFuture<String> terminalSignal,
            Object transcriptLock,
            List<String> transcriptSegments,
            AtomicInteger currentSegment,
            AtomicLong lastTranscriptUpdateAt,
            long finishSentAt
    ) throws Exception {
        long deadline = System.currentTimeMillis() + vendorTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            checkCancelled(cancellation);
            if (terminalSignal.isCompletedExceptionally()) {
                try {
                    terminalSignal.join();
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    throw new IllegalStateException(cause == null ? error.getMessage() : cause.getMessage(), cause);
                }
            }
            synchronized (transcriptLock) {
                long now = System.currentTimeMillis();
                long lastUpdate = lastTranscriptUpdateAt.get();
                boolean pendingSegment = currentSegment.get() > transcriptSegments.size();
                boolean hasPostFinishTranscript = lastUpdate >= finishSentAt;
                long postFinishElapsed = now - finishSentAt;

                if (lastUpdate > 0
                        && now - lastUpdate >= SEGMENT_SETTLE_MS
                        && !pendingSegment
                        && (hasPostFinishTranscript || postFinishElapsed >= POST_FINISH_GRACE_MS)) {
                    log.info("【评测 ASR 收尾完成】vendor={}，reason={}，segments={}，currentSegment={}，lastUpdate={}，postFinishElapsedMs={}",
                            vendor,
                            hasPostFinishTranscript ? "post-finish-settled" : "grace-expired-without-new-transcript",
                            transcriptSegments.size(),
                            currentSegment.get(),
                            lastUpdate,
                            postFinishElapsed);
                    return joinSegments(transcriptSegments);
                }
                if (pendingSegment && postFinishElapsed >= PENDING_SEGMENT_GRACE_MS) {
                    log.warn("【评测 ASR 收尾超时】vendor={}，reason=pending-segment-grace-expired，segments={}，currentSegment={}，lastUpdate={}，postFinishElapsedMs={}",
                            vendor, transcriptSegments.size(), currentSegment.get(), lastUpdate, postFinishElapsed);
                    return joinSegments(transcriptSegments);
                }

                long waitForSettle = lastUpdate > 0 ? Math.max(100, SEGMENT_SETTLE_MS - (now - lastUpdate)) : 200;
                long waitForPostFinishGrace = pendingSegment
                        ? Math.max(100, PENDING_SEGMENT_GRACE_MS - postFinishElapsed)
                        : Math.max(100, POST_FINISH_GRACE_MS - postFinishElapsed);
                long waitMs = Math.min(Math.min(waitForSettle, waitForPostFinishGrace), deadline - now);
                if (waitMs > 0) transcriptLock.wait(waitMs);
            }
        }
        if (!transcriptSegments.isEmpty()) return joinSegments(transcriptSegments);
        throw new TimeoutException(vendor + " 识别超时");
    }

    private String joinSegments(List<String> transcriptSegments) {
        StringBuilder builder = new StringBuilder();
        String previous = null;
        for (String segment : transcriptSegments) {
            if (segment == null || segment.isEmpty()) continue;
            if (segment.equals(previous)) continue;
            builder.append(segment);
            previous = segment;
        }
        return builder.toString();
    }

    public interface Cancellation { boolean isCancelled(); }

    public static class VendorOutcome {
        public final String transcript;
        public final Long firstLatencyMs;
        public final long finalLatencyMs;
        VendorOutcome(String transcript, Long firstLatencyMs, long finalLatencyMs) {
            this.transcript = transcript;
            this.firstLatencyMs = firstLatencyMs;
            this.finalLatencyMs = finalLatencyMs;
        }
    }
}
