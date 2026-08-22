package com.distributedai.api.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Default local-dev provider - generates a placeholder PNG with the prompt
 * text drawn on it, entirely locally. Exists specifically so the full
 * generate -> upload -> COMPLETED pipeline can be exercised and demonstrated
 * without an OpenAI API key or any billing. Selected via ai.provider.type
 * (defaults to "mock" - see application.yml).
 */
@Component
@ConditionalOnProperty(name = "ai.provider.type", havingValue = "mock", matchIfMissing = true)
public class MockAiProvider implements AiProvider {

    @Override
    public AiGenerationResult generate(AiGenerationRequest request) {
        try {
            int width = 512;
            int height = 512;

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g.setColor(new Color(40, 44, 52));
            g.fillRect(0, 0, width, height);

            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.PLAIN, 16));
            drawWrappedText(g, "[MOCK] " + request.prompt(), 20, 40, width - 40);

            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);

            return new AiGenerationResult(baos.toByteArray(), "image/png");

        } catch (IOException ex) {
            throw new AiProviderException("Mock provider failed to generate image", ex);
        }
    }

    private void drawWrappedText(Graphics2D g, String text, int x, int y, int maxWidth) {
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        int lineHeight = fm.getHeight();

        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(candidate) > maxWidth) {
                g.drawString(line.toString(), x, y);
                y += lineHeight;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            g.drawString(line.toString(), x, y);
        }
    }
}