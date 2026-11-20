# COMP3011 Assignment 3 - Video Player with Subtitles

This JavaFX video player extends the Assignment 2 frame processors with speech-to-text subtitles. It starts the local subtitle API at `http://localhost:8080` when the player launches. Audio is extracted in a background worker and sent in 15-second WAV chunks, so video and audio playback do not wait for transcription.

## Attribution and AI assistance

The Assignment 2 starter code was supplied by Adelaide University; its source headers identify Simon Ratcliffe and acknowledge collaboration with GPT-5.6 Terra. GitHub Copilot also assisted Mingxuan Li with parts of this Assignment 3 project, including the subtitle client/server and frame-rendering pipeline, command-line and GUI subtitle controls, playback integration, and cross-platform Maven configuration. Mingxuan reviewed and adapted AI-generated suggestions, integrated the components, and remains responsible for the submitted implementation and explaining its design and behavior. Relevant source and build comments identify these AI-assisted areas; the starter code and third-party libraries are separate contributions.

## Build and run

Use JDK 25 or later and Maven:

```sh
mvn clean package
mvn javafx:run -Djavafx.args="--subtitle-frames path/to/video.mp4"
```

The `-S` / `--subtitle-frames` option enables subtitles at launch. Subtitles can also be toggled during playback with the **CC** button or `s`; the button starts off when `-S` is omitted. Existing frame processor options remain available.

## OpenAI configuration and API

Provide the API key as `OPENAI_API_KEY`, or place it in `key.txt` in the process working directory. Alternatively, set `OPENAI_API_KEY_FILE` to a key file path. Do not commit the key file.

The player embeds the HTTP service; to run it separately, start `comp3011.SubtitleServer` from the built `*-all.jar`:

```sh
java --add-modules=jdk.httpserver -cp target/VideoPlayerSingleThread-0.1-all.jar comp3011.SubtitleServer
```

`POST /api/v1/subtitles` accepts a PCM WAV body and an optional `offset` query parameter in seconds. The server requests Whisper `verbose_json` with word and segment timestamps, filters segments where `no_speech_prob > 0.6` and `avg_logprob < -1.0`, and returns cue timings in seconds. `GET /api/v1/global/usage` returns cumulative successfully transcribed audio duration in seconds as `{"usage": 0.0}`.
