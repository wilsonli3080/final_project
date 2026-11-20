package comp3011;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.bytedeco.javacv.Frame;

/**
 * Renders time-matched subtitle cues directly onto decoded video frames.
 *
 * <p>AI-assistance note: GitHub Copilot assisted with subtitle wrapping,
 * timing, and frame-overlay implementation in this class. The implementation
 * was reviewed and adapted for this project by the student.</p>
 */
public class SubtitleFrameProcessor extends FrameProcessor {
    private static final int MAX_LINE_LENGTH = 42;
    private static final long MIN_DISPLAY_US = 1_000_000L;
    private static final long MAX_DISPLAY_US = 7_000_000L;

    private volatile List<SubtitleCue> cues = List.of();
    private volatile boolean enabled;
    private String renderedText;
    private BufferedImage overlay;
    private int[] overlayPixels;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public synchronized void clearCues() {
        cues = List.of();
        renderedText = null;
        overlay = null;
        overlayPixels = null;
    }

    public synchronized void addCues(List<SubtitleCue> newCues) {
        if (newCues == null || newCues.isEmpty()) {
            return;
        }

        List<SubtitleCue> updated = new ArrayList<>(cues);
        for (SubtitleCue cue : newCues) {
            updated.addAll(splitCue(cue));
        }
        updated.sort(Comparator.comparingLong(SubtitleCue::startUs));
        cues = List.copyOf(updated);
    }

    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (!enabled || frame.image == null || frame.image.length == 0
                || !(frame.image[0] instanceof ByteBuffer pixels)
                || videoInfo.imageChannels() < 3
                || (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE)) {
            return;
        }

        String text = cueAt(info.timestampUs());
        if (text == null) {
            renderedText = null;
            overlay = null;
            overlayPixels = null;
            return;
        }

        if (!text.equals(renderedText)) {
            createOverlay(text, videoInfo.imageHeight());
        }
        if (overlay == null || overlayPixels == null) {
            return;
        }

        int width = videoInfo.imageWidth();
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();
        int xOffset = Math.max(0, (width - overlay.getWidth()) / 2);
        int yOffset = videoInfo.imageHeight() - overlay.getHeight()
                - Math.max(12, videoInfo.imageHeight() / 30);
        yOffset = Math.max(0, yOffset);

        for (int y = 0; y < overlay.getHeight() && yOffset + y < videoInfo.imageHeight(); y++) {
            int row = y * overlay.getWidth();
            int frameRow = (yOffset + y) * stride;
            for (int x = 0; x < overlay.getWidth() && xOffset + x < width; x++) {
                int argb = overlayPixels[row + x];
                int alpha = argb >>> 24;
                if (alpha == 0) {
                    continue;
                }

                int pixel = frameRow + (xOffset + x) * channels;
                pixels.put(pixel, blend(pixels.get(pixel), argb & 0xff, alpha));
                pixels.put(pixel + 1, blend(pixels.get(pixel + 1), (argb >>> 8) & 0xff, alpha));
                pixels.put(pixel + 2, blend(pixels.get(pixel + 2), (argb >>> 16) & 0xff, alpha));
            }
        }
    }

    private String cueAt(long timestampUs) {
        List<SubtitleCue> snapshot = cues;
        int low = 0;
        int high = snapshot.size() - 1;
        int candidate = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (snapshot.get(middle).startUs() <= timestampUs) {
                candidate = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        if (candidate < 0) {
            return null;
        }

        SubtitleCue cue = snapshot.get(candidate);
        return timestampUs < cue.endUs() ? cue.text() : null;
    }

    private List<SubtitleCue> splitCue(SubtitleCue cue) {
        String text = cue.text() == null ? "" : cue.text().strip().replaceAll("\\s+", " ");
        if (text.isEmpty() || cue.endUs() <= cue.startUs()) {
            return List.of();
        }

        List<String> lines = wrap(text);
        List<String> pages = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += 2) {
            pages.add(String.join("\n", lines.subList(i, Math.min(i + 2, lines.size()))));
        }

        long durationUs = cue.endUs() - cue.startUs();
        long displayDurationUs = Math.max(durationUs, MIN_DISPLAY_US * pages.size());
        long pageDurationUs = Math.min(MAX_DISPLAY_US, displayDurationUs / pages.size());
        List<SubtitleCue> result = new ArrayList<>(pages.size());
        for (int i = 0; i < pages.size(); i++) {
            long startUs = cue.startUs() + pageDurationUs * i;
            result.add(new SubtitleCue(startUs, startUs + pageDurationUs, pages.get(i)));
        }
        return result;
    }

    private List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        String remaining = text;
        while (!remaining.isEmpty()) {
            if (remaining.length() <= MAX_LINE_LENGTH) {
                lines.add(remaining);
                break;
            }

            int breakAt = -1;
            for (int i = MAX_LINE_LENGTH; i >= Math.max(1, MAX_LINE_LENGTH / 2); i--) {
                char character = remaining.charAt(i - 1);
                if (".,!?;:".indexOf(character) >= 0) {
                    breakAt = i;
                    break;
                }
            }
            if (breakAt <= 0) {
                for (int i = MAX_LINE_LENGTH; i >= Math.max(1, MAX_LINE_LENGTH / 2); i--) {
                    if (Character.isWhitespace(remaining.charAt(i - 1))) {
                        breakAt = i;
                        break;
                    }
                }
            }
            if (breakAt <= 0) {
                breakAt = remaining.lastIndexOf(' ', MAX_LINE_LENGTH);
            }
            if (breakAt <= 0) {
                breakAt = MAX_LINE_LENGTH;
            }

            lines.add(remaining.substring(0, breakAt).strip());
            remaining = remaining.substring(breakAt).stripLeading();
        }
        return lines;
    }

    private void createOverlay(String text, int frameHeight) {
        renderedText = text;
        String[] lines = text.split("\\n", -1);
        int fontSize = Math.max(18, frameHeight / 20);
        Font font = new Font("SansSerif", Font.BOLD, fontSize);

        BufferedImage measureImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D measureGraphics = measureImage.createGraphics();
        measureGraphics.setFont(font);
        FontMetrics metrics = measureGraphics.getFontMetrics();
        int textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, metrics.stringWidth(line));
        }
        int padding = Math.max(8, fontSize / 3);
        int availableWidth = Math.max(1, videoInfo.imageWidth() - 2 * padding);
        if (textWidth > availableWidth) {
            fontSize = Math.max(12, fontSize * availableWidth / textWidth);
            font = new Font("SansSerif", Font.BOLD, fontSize);
            measureGraphics.setFont(font);
            metrics = measureGraphics.getFontMetrics();
            textWidth = 0;
            for (String line : lines) {
                textWidth = Math.max(textWidth, metrics.stringWidth(line));
            }
            padding = Math.max(6, fontSize / 3);
        }
        int lineHeight = metrics.getHeight();
        measureGraphics.dispose();

        overlay = new BufferedImage(textWidth + 2 * padding, lines.length * lineHeight + 2 * padding,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = overlay.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setFont(font);
        graphics.setStroke(new BasicStroke(Math.max(2.5f, fontSize * 0.12f),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        for (int i = 0; i < lines.length; i++) {
            FontMetrics lineMetrics = graphics.getFontMetrics();
            int lineWidth = lineMetrics.stringWidth(lines[i]);
            int x = (overlay.getWidth() - lineWidth) / 2;
            int baseline = padding + lineMetrics.getAscent() + i * lineHeight;
            GlyphVector glyph = font.createGlyphVector(graphics.getFontRenderContext(), lines[i]);
            var outline = glyph.getOutline(x, baseline);
            graphics.setColor(java.awt.Color.BLACK);
            graphics.draw(outline);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fill(outline);
        }
        graphics.dispose();
        overlayPixels = overlay.getRGB(0, 0, overlay.getWidth(), overlay.getHeight(), null, 0, overlay.getWidth());
    }

    private byte blend(byte original, int foreground, int alpha) {
        int background = Byte.toUnsignedInt(original);
        return (byte) ((foreground * alpha + background * (255 - alpha) + 127) / 255);
    }
}
