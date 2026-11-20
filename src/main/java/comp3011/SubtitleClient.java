package comp3011;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

/**
 * Extracts audio and submits short WAV chunks without blocking video playback.
 *
 * <p>AI-assistance note: GitHub Copilot assisted Mingxuan Li with the chunked
 * extraction, concurrency, and HTTP response parsing in this class. Mingxuan
 * reviewed and adapted the implementation and is responsible for integration,
 * testing, and final code decisions.</p>
 */
public class SubtitleClient implements AutoCloseable {
    private static final String SUBTITLE_ENDPOINT = "http://localhost:8080/api/v1/subtitles";
    private static final int CHUNK_SECONDS = 15;
    private static final int MAX_CHUNK_BYTES = 20 * 1024 * 1024;

    private final ExecutorService extractionExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "subtitle-audio-extraction");
        thread.setDaemon(true);
        return thread;
    });
    private final ThreadPoolExecutor transcriptionExecutor = new ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2),
            task -> {
                Thread thread = new Thread(task, "subtitle-http-client");
                thread.setDaemon(true);
                return thread;
            },
            new CallerRunsPolicy());
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AudioPlayer sampleConverter = new AudioPlayer();
    private final AtomicLong generation = new AtomicLong();

    private volatile Future<?> activeTask;
    private final List<Future<?>> transcriptionTasks = new ArrayList<>();

    public synchronized void requestSubtitles(
            java.io.File mediaFile,
            Consumer<List<SubtitleCue>> cuesReceived,
            Consumer<String> statusChanged) {
        cancelCurrentRequest();
        long requestGeneration = generation.incrementAndGet();
        Consumer<String> currentStatus = status -> {
            if (requestGeneration == generation.get()) {
                statusChanged.accept(status);
            }
        };
        activeTask = extractionExecutor.submit(() -> {
            try {
                extractAndTranscribe(mediaFile, requestGeneration, cuesReceived, currentStatus);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (requestGeneration == generation.get()) {
                    System.err.println("Subtitle transcription failed: " + e.getMessage());
                    currentStatus.accept("Subtitles unavailable: " + e.getMessage());
                }
            }
        });
    }

    @Override
    public synchronized void close() {
        cancelCurrentRequest();
        extractionExecutor.shutdownNow();
        transcriptionExecutor.shutdownNow();
    }

    public synchronized void cancel() {
        cancelCurrentRequest();
    }

    private void cancelCurrentRequest() {
        generation.incrementAndGet();
        Future<?> task = activeTask;
        if (task != null) {
            task.cancel(true);
            activeTask = null;
        }
        for (Future<?> transcriptionTask : transcriptionTasks) {
            transcriptionTask.cancel(true);
        }
        transcriptionTasks.clear();
    }

    private void extractAndTranscribe(
            java.io.File mediaFile,
            long requestGeneration,
            Consumer<List<SubtitleCue>> cuesReceived,
            Consumer<String> statusChanged) throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(mediaFile);
        try {
            grabber.setImageMode(org.bytedeco.javacv.FrameGrabber.ImageMode.RAW);
            grabber.setSampleMode(org.bytedeco.javacv.FrameGrabber.SampleMode.SHORT);
            grabber.start();
            if (!grabber.hasAudio()) {
                statusChanged.accept("No audio track available for subtitles");
                return;
            }

            int sampleRate = grabber.getSampleRate();
            int channels = grabber.getAudioChannels();
            if (sampleRate <= 0 || channels <= 0) {
                throw new IOException("Media audio has invalid sample-rate or channel information");
            }

            long bytesPerChunk = (long) sampleRate * channels * Short.BYTES * CHUNK_SECONDS;
            int chunkLimit = (int) Math.min(bytesPerChunk, MAX_CHUNK_BYTES);
            ByteArrayOutputStream chunk = new ByteArrayOutputStream(chunkLimit);
            long chunkStartUs = -1;
            Frame audioFrame;
            while (!Thread.currentThread().isInterrupted() && (audioFrame = grabber.grabSamples()) != null) {
                byte[] samples = sampleConverter.copySamples(audioFrame);
                if (samples.length == 0) {
                    continue;
                }
                if (chunkStartUs < 0) {
                    chunkStartUs = grabber.getTimestamp();
                }
                chunk.write(samples);
                if (chunk.size() >= chunkLimit) {
                    submitChunk(chunk.toByteArray(), sampleRate, channels, chunkStartUs,
                            requestGeneration, cuesReceived, statusChanged);
                    chunk.reset();
                    chunkStartUs = -1;
                }
            }

            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            if (chunk.size() > 0) {
                submitChunk(chunk.toByteArray(), sampleRate, channels, chunkStartUs,
                        requestGeneration, cuesReceived, statusChanged);
            }
        } finally {
            try {
                grabber.stop();
            } finally {
                grabber.close();
            }
        }
    }

    private void submitChunk(
            byte[] pcm,
            int sampleRate,
            int channels,
            long offsetUs,
            long requestGeneration,
            Consumer<List<SubtitleCue>> cuesReceived,
            Consumer<String> statusChanged) throws Exception {
        if (requestGeneration != generation.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }

        byte[] wav = toWav(pcm, sampleRate, channels);
        URI endpoint = URI.create(SUBTITLE_ENDPOINT + "?offset="
                + (Math.max(0, offsetUs) / 1_000_000.0));
        Future<?> task = transcriptionExecutor.submit(() -> {
            try {
                HttpResponse<byte[]> response = requestWithRetry(endpoint, wav, requestGeneration, statusChanged);
                if (response == null) {
                    return;
                }

                JsonNode root = objectMapper.readTree(response.body());
                List<SubtitleCue> cues = new ArrayList<>();
                JsonNode cueNodes = root.path("cues");
                if (!cueNodes.isArray()) {
                    throw new IOException("Local subtitle API response is missing its cues array");
                }
                for (JsonNode cue : cueNodes) {
                    double start = cue.path("start").asDouble(Double.NaN);
                    double end = cue.path("end").asDouble(Double.NaN);
                    String text = cue.path("text").asText("");
                    if (Double.isFinite(start) && Double.isFinite(end) && end > start && !text.isBlank()) {
                        cues.add(new SubtitleCue(
                                Math.round(start * 1_000_000),
                                Math.round(end * 1_000_000),
                                text));
                    }
                }
                if (requestGeneration == generation.get() && !cues.isEmpty()) {
                    cuesReceived.accept(List.copyOf(cues));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (requestGeneration == generation.get()) {
                    System.err.println("Subtitle transcription failed: " + e.getMessage());
                    statusChanged.accept("Subtitles unavailable: " + e.getMessage());
                }
            }
        });
        synchronized (this) {
            if (requestGeneration == generation.get()) {
                transcriptionTasks.removeIf(Future::isDone);
                transcriptionTasks.add(task);
            } else {
                task.cancel(true);
            }
        }
    }

    private HttpResponse<byte[]> requestWithRetry(
            URI endpoint,
            byte[] wav,
            long requestGeneration,
            Consumer<String> statusChanged) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "audio/wav")
                .POST(HttpRequest.BodyPublishers.ofByteArray(wav))
                .build();
        int retryDelaySeconds = 1;
        boolean reportedWaiting = false;
        while (requestGeneration == generation.get() && !Thread.currentThread().isInterrupted()) {
            HttpResponse<byte[]> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                if (requestGeneration != generation.get()) {
                    return null;
                }
                if (!reportedWaiting) {
                    statusChanged.accept("Subtitle service unavailable; retrying");
                    reportedWaiting = true;
                }
                Thread.sleep(retryDelaySeconds * 1_000L);
                retryDelaySeconds = Math.min(30, retryDelaySeconds * 2);
                continue;
            }

            if (response.statusCode() == 200) {
                return response;
            }
            String message = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
            if (response.statusCode() == 503 && message.contains("OPENAI_API_KEY")) {
                throw new IOException("Subtitle service is missing its OpenAI API key. "
                        + "Set OPENAI_API_KEY or place key.txt beside the application.");
            }
            if (response.statusCode() < 500 && response.statusCode() != 429) {
                throw new IOException(
                        "Local subtitle API returned HTTP " + response.statusCode() + ": " + message);
            }
            if (!reportedWaiting) {
                statusChanged.accept("Subtitle service unavailable; retrying");
                reportedWaiting = true;
            }

            Thread.sleep(retryDelaySeconds * 1_000L);
            retryDelaySeconds = Math.min(30, retryDelaySeconds * 2);
        }
        return null;
    }

    private byte[] toWav(byte[] pcm, int sampleRate, int channels) throws IOException {
        int blockAlign = channels * Short.BYTES;
        ByteArrayOutputStream wav = new ByteArrayOutputStream(pcm.length + 44);
        wav.write("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeLittleEndian(wav, pcm.length + 36L, 4);
        wav.write("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeLittleEndian(wav, 16, 4);
        writeLittleEndian(wav, 1, 2);
        writeLittleEndian(wav, channels, 2);
        writeLittleEndian(wav, sampleRate, 4);
        writeLittleEndian(wav, (long) sampleRate * blockAlign, 4);
        writeLittleEndian(wav, blockAlign, 2);
        writeLittleEndian(wav, 16, 2);
        wav.write("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeLittleEndian(wav, pcm.length, 4);
        wav.write(pcm);
        return wav.toByteArray();
    }

    private void writeLittleEndian(ByteArrayOutputStream output, long value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            output.write((int) (value >>> (8 * i)) & 0xff);
        }
    }
}
