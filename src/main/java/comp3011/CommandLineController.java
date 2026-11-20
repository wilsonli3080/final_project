/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 *
 * <p>AI-assistance note: GitHub Copilot assisted Mingxuan Li with the subtitle
 * command-line option and its launch-state handling. Mingxuan reviewed and
 * adapted the suggestion for this player's existing argument parser.</p>
 */
public class CommandLineController {
    private final String[] args;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean subtitleRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;
    private final List<FrameProcessor> frameProcessors = new ArrayList<>();

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public List<FrameProcessor> getFrameProcessors() {
        return List.copyOf(frameProcessors);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return (helpRequested || errorMessage == null) ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isSubtitleRequested() {
        return subtitleRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    public boolean shouldLaunchApplication() {
        return !helpRequested && errorMessage == null && videoFile != null;
    }

    private void parse() {
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                helpRequested = true;
            }
        }

        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                continue;
            }

            if ("-a".equals(arg) || "--audio".equals(arg)) {
                audioRequested = true;
            } else if ("-x".equals(arg) || "--maximise".equals(arg)) {
                maximiseRequested = true;
            } else if ("-1".equals(arg) || "--monitor-1".equals(arg)) {
                setDisplayId(1);
            } else if ("-2".equals(arg) || "--monitor-2".equals(arg)) {
                setDisplayId(2);
            } else if (arg.startsWith("-")) {
                if (arg.length() > 2 && !arg.startsWith("--")) {
                    for (int i = 1; i < arg.length(); i++) {
                        if (!acceptEffectFlag(arg.charAt(i))) {
                            if (helpRequested) {
                                continue;
                            }
                            errorMessage = "Unknown option: " + arg;
                            return;
                        }
                    }
                } else if (!acceptLongOrShortEffectFlag(arg)) {
                    if (helpRequested) {
                        continue;
                    }
                    errorMessage = "Unknown option: " + arg;
                    return;
                }
            } else {
                videoFiles.add(arg);
            }
        }

        if (helpRequested) {
            errorMessage = null;
            return;
        }

        if (videoFiles.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else {
            errorMessage = "No video file specified.";
        }
    }

    private boolean acceptLongOrShortEffectFlag(String arg) {
        if ("-n".equals(arg) || "--number-frames".equals(arg)) {
            frameProcessors.add(new FrameNumberer());
            return true;
        }
        if ("-s".equals(arg) || "--scratch-frames".equals(arg)) {
            frameProcessors.add(new FrameScratcher());
            return true;
        }
        if ("-f".equals(arg) || "--flicker-frames".equals(arg)) {
            frameProcessors.add(new FrameFlickerer());
            return true;
        }
        if ("-w".equals(arg) || "--black-and-white".equals(arg)) {
            frameProcessors.add(new FrameBlackAndWhiter());
            return true;
        }
        if ("-y".equals(arg) || "--yellow-frames".equals(arg)) {
            frameProcessors.add(new FrameYellower());
            return true;
        }
        if ("-v".equals(arg) || "--vignette-frames".equals(arg)) {
            frameProcessors.add(new FrameVignetter());
            return true;
        }
        if ("-d".equals(arg) || "--dust-frames".equals(arg)) {
            frameProcessors.add(new FrameDuster());
            return true;
        }
        if ("-j".equals(arg) || "--jitter-frames".equals(arg)) {
            frameProcessors.add(new FrameJitterer());
            return true;
        }
        if ("-m".equals(arg) || "--mottle-frames".equals(arg)) {
            frameProcessors.add(new FrameMottler());
            return true;
        }
        if ("-b".equals(arg) || "--bleed-frames".equals(arg)) {
            frameProcessors.add(new FrameBleeder());
            return true;
        }
        if ("-p".equals(arg) || "--pepper-frames".equals(arg)) {
            frameProcessors.add(new FramePepperer());
            return true;
        }
        if ("-S".equals(arg) || "--subtitle-frames".equals(arg)) {
            subtitleRequested = true;
            frameProcessors.add(new SubtitleFrameProcessor());
            return true;
        }
        return false;
    }

    private boolean acceptEffectFlag(char flag) {
        switch (flag) {
            case 'n':
                return addEffect(new FrameNumberer());
            case 's':
                return addEffect(new FrameScratcher());
            case 'f':
                return addEffect(new FrameFlickerer());
            case 'w':
                return addEffect(new FrameBlackAndWhiter());
            case 'y':
                return addEffect(new FrameYellower());
            case 'v':
                return addEffect(new FrameVignetter());
            case 'd':
                return addEffect(new FrameDuster());
            case 'j':
                return addEffect(new FrameJitterer());
            case 'm':
                return addEffect(new FrameMottler());
            case 'b':
                return addEffect(new FrameBleeder());
            case 'p':
                return addEffect(new FramePepperer());
            case 'S':
                subtitleRequested = true;
                return addEffect(new SubtitleFrameProcessor());
            default:
                return false;
        }
    }

    private boolean addEffect(FrameProcessor processor) {
        frameProcessors.add(processor);
        return true;
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        System.out.println("Frame processors:");
        System.out.println("  -n, --number-frames    Render the frame number onto each frame");
        System.out.println("  -s, --scratch-frames   Render vertical film scratches");
        System.out.println("  -f, --flicker-frames   Randomly dim frames");
        System.out.println("  -w, --black-and-white  Convert frames to black and white");
        System.out.println("  -y, --yellow-frames    Apply a warmer colour temperature");
        System.out.println("  -v, --vignette-frames  Darken the frame edges");
        System.out.println("  -d, --dust-frames      Render dust and hair marks");
        System.out.println("  -j, --jitter-frames    Randomly displace frames by a few pixels");
        System.out.println("  -m, --mottle-frames    Add cloudy emulsion mottling");
        System.out.println("  -b, --bleed-frames     Bleed light into frames");
        System.out.println("  -p, --pepper-frames    Pepper frames with dark spots/blotches");
        System.out.println("  -S, --subtitle-frames  Render speech-to-text subtitles onto frames");
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
}
