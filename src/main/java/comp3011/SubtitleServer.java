package comp3011;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Local HTTP bridge between the player and OpenAI's Whisper transcription API.
 */
public class SubtitleServer implements AutoCloseable {
    private static final int PORT = 8080;
    private static final int MAX_AUDIO_BYTES = 24 * 1024 * 1024;
    private static final URI TRANSCRIPTION_URI = URI.create("https://api.openai.com/v1/audio/transcriptions");

    private final HttpServer server;
    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong usageMicroseconds = new AtomicLong();

    private SubtitleServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
        executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "subtitle-http-request");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        server.createContext("/api/v1/subtitles", this::handleSubtitles);
        server.createContext("/api/v1/global/usage", this::handleUsage);
    }

    public static SubtitleServer start() throws IOException {
        SubtitleServer subtitleServer = new SubtitleServer();
        subtitleServer.server.start();
        System.out.println("Subtitle API listening at http://localhost:" + PORT);
        return subtitleServer;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        SubtitleServer subtitleServer = start();
        Runtime.getRuntime().addShutdownHook(new Thread(subtitleServer::close, "subtitle-server-shutdown"));
        new java.util.concurrent.CountDownLatch(1).await();
    }

    private void handleUsage(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, error("Only GET is supported"));
                return;
            }
            ObjectNode response = objectMapper.createObjectNode();
            response.put("usage", usageMicroseconds.get() / 1_000_000.0);
            sendJson(exchange, 200, response);
        } finally {
            exchange.close();
        }
    }

    private void handleSubtitles(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, error("Only POST is supported"));
                return;
            }
            byte[] audio = readAudio(exchange);
            double offsetSeconds = readOffset(exchange);
            long audioDurationUs = wavDurationMicroseconds(audio);

            String apiKey = loadApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                sendJson(exchange, 503, error(
                        "Set OPENAI_API_KEY or place the key in key.txt (or OPENAI_API_KEY_FILE)"));
                return;
            }

            JsonNode transcription = transcribe(apiKey, audio);
            usageMicroseconds.addAndGet(audioDurationUs);
            sendJson(exchange, 200, buildSubtitleResponse(transcription, offsetSeconds));
        } catch (BadRequestException e) {
            sendJson(exchange, 400, error(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJson(exchange, 502, error("Transcription request was interrupted"));
        } catch (OpenAiApiException e) {
            System.err.println("OpenAI transcription request failed: " + e.getMessage());
            int status = e.statusCode() >= 400 && e.statusCode() < 500 ? e.statusCode() : 502;
            sendJson(exchange, status, error("OpenAI transcription failed: " + e.getMessage()));
        } catch (Exception e) {
            System.err.println("Subtitle API request failed: " + e.getMessage());
            sendJson(exchange, 502, error("Transcription failed: " + e.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private byte[] readAudio(HttpExchange exchange) throws IOException, BadRequestException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("audio/")) {
            throw new BadRequestException("Content-Type must identify an audio file");
        }
        try (var input = exchange.getRequestBody(); var audio = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (audio.size() + count > MAX_AUDIO_BYTES) {
                    throw new BadRequestException("Audio chunk exceeds the 24 MiB upload limit");
                }
                audio.write(buffer, 0, count);
            }
            byte[] bytes = audio.toByteArray();
            if (bytes.length < 44 || bytes[0] != 'R' || bytes[1] != 'I'
                    || bytes[2] != 'F' || bytes[3] != 'F') {
                throw new BadRequestException("Request body must be a PCM WAV audio file");
            }
            return bytes;
        }
    }

    private double readOffset(HttpExchange exchange) throws BadRequestException {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return 0;
        }
        for (String parameter : query.split("&")) {
            String[] pair = parameter.split("=", 2);
            if ("offset".equals(decodeQueryParameter(pair[0]))) {
                try {
                    double offset = Double.parseDouble(pair.length == 2
                            ? decodeQueryParameter(pair[1])
                            : "0");
                    if (Double.isFinite(offset) && offset >= 0) {
                        return offset;
                    }
                } catch (NumberFormatException ignored) {
                    throw new BadRequestException("Subtitle offset must be a non-negative number");
                }
                throw new BadRequestException("Subtitle offset must be a non-negative number");
            }
        }
        return 0;
    }

    private String decodeQueryParameter(String value) throws BadRequestException {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Subtitle query parameters must be URL encoded");
        }
    }

    private JsonNode transcribe(String apiKey, byte[] audio) throws Exception {
        String boundary = "----COMP3011-" + java.util.UUID.randomUUID();
        byte[] body = multipartBody(boundary, audio);
        HttpRequest request = HttpRequest.newBuilder(TRANSCRIPTION_URI)
                .timeout(Duration.ofMinutes(3))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        JsonNode responseJson = objectMapper.readTree(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = responseJson.path("error").path("message").asText("HTTP " + response.statusCode());
            throw new OpenAiApiException(response.statusCode(),
                    "OpenAI returned HTTP " + response.statusCode() + ": " + message);
        }
        if (!responseJson.path("segments").isArray()) {
            throw new IOException("OpenAI response did not contain timestamped segments");
        }
        return responseJson;
    }

    private byte[] multipartBody(String boundary, byte[] audio) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(audio.length + 1024);
        writeField(body, boundary, "model", "whisper-1");
        writeField(body, boundary, "response_format", "verbose_json");
        writeField(body, boundary, "timestamp_granularities[]", "word");
        writeField(body, boundary, "timestamp_granularities[]", "segment");
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(audio);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    private void writeField(ByteArrayOutputStream body, String boundary, String name, String value)
            throws IOException {
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private ObjectNode buildSubtitleResponse(JsonNode transcription, double offsetSeconds) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode segments = response.putArray("segments");
        ArrayNode cues = response.putArray("cues");

        for (JsonNode segment : transcription.path("segments")) {
            double start = segment.path("start").asDouble(0) + offsetSeconds;
            double end = segment.path("end").asDouble(0) + offsetSeconds;
            String text = segment.path("text").asText("").strip();
            double noSpeechProb = segment.path("no_speech_prob").asDouble(0);
            double avgLogProb = segment.path("avg_logprob").asDouble(0);

            ObjectNode timedSegment = objectMapper.createObjectNode();
            timedSegment.put("start", start);
            timedSegment.put("end", end);
            timedSegment.put("text", text);
            timedSegment.put("noSpeechProb", noSpeechProb);
            timedSegment.put("avgLogProb", avgLogProb);
            ArrayNode words = timedSegment.putArray("words");
            for (JsonNode word : segment.path("words")) {
                ObjectNode timedWord = words.addObject();
                timedWord.put("start", word.path("start").asDouble(0) + offsetSeconds);
                timedWord.put("end", word.path("end").asDouble(0) + offsetSeconds);
                timedWord.put("text", word.path("word").asText(""));
            }
            segments.add(timedSegment);

            if (!(noSpeechProb > 0.6 && avgLogProb < -1.0) && end > start && !text.isBlank()) {
                ObjectNode cue = cues.addObject();
                cue.put("start", start);
                cue.put("end", end);
                cue.put("text", text);
            }
        }
        response.put("text", transcription.path("text").asText(""));
        return response;
    }

    private long wavDurationMicroseconds(byte[] wav) throws BadRequestException {
        if (wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E') {
            throw new BadRequestException("Request body must be a valid WAV audio file");
        }
        long sampleRate = readLittleEndian(wav, 24, 4);
        long channels = readLittleEndian(wav, 22, 2);
        long bitsPerSample = readLittleEndian(wav, 34, 2);
        long dataLength = readLittleEndian(wav, 40, 4);
        long bytesPerSecond = sampleRate * channels * bitsPerSample / 8;
        long audioFormat = readLittleEndian(wav, 20, 2);
        if (audioFormat != 1 || bitsPerSample != 16 || channels <= 0 || sampleRate <= 0
                || bytesPerSecond <= 0 || dataLength > wav.length - 44L) {
            throw new BadRequestException("WAV audio has invalid format information");
        }
        return dataLength * 1_000_000L / bytesPerSecond;
    }

    private long readLittleEndian(byte[] source, int offset, int byteCount) {
        long result = 0;
        for (int i = 0; i < byteCount; i++) {
            result |= (long) Byte.toUnsignedInt(source[offset + i]) << (8 * i);
        }
        return result;
    }

    private String loadApiKey() throws IOException {
        String configuredKey = System.getenv("OPENAI_API_KEY");
        if (configuredKey != null && !configuredKey.isBlank()) {
            return configuredKey.strip();
        }

        String configuredFile = System.getenv("OPENAI_API_KEY_FILE");
        if (configuredFile != null && !configuredFile.isBlank()) {
            return readKeyFile(Path.of(configuredFile));
        }
        String key = readKeyFile(Path.of("key.txt"));
        if (key != null) {
            return key;
        }

        Path volumes = Path.of("/Volumes");
        if (Files.isDirectory(volumes)) {
            try (var mountedVolumes = Files.list(volumes)) {
                for (Path volume : mountedVolumes.toList()) {
                    key = readKeyFile(volume.resolve("key.txt"));
                    if (key != null) {
                        return key;
                    }
                }
            }
        }
        return null;
    }

    private String readKeyFile(Path keyFile) throws IOException {
        if (!Files.isRegularFile(keyFile)) {
            return null;
        }
        String key = Files.readString(keyFile, StandardCharsets.UTF_8).strip();
        if (key.startsWith("OPENAI_API_KEY=")) {
            key = key.substring("OPENAI_API_KEY=".length()).strip();
        }
        return key;
    }

    private ObjectNode error(String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("error", message);
        return response;
    }

    private void sendJson(HttpExchange exchange, int status, JsonNode response) throws IOException {
        byte[] body = objectMapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static final class BadRequestException extends Exception {
        BadRequestException(String message) {
            super(message);
        }
    }

    private static final class OpenAiApiException extends IOException {
        private final int statusCode;

        OpenAiApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }
}
