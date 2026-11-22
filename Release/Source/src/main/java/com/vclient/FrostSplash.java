package com.vclient;

import javafx.animation.AnimationTimer;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.Random;

/**
 * The start-up screen: a blizzard rushes across, the VSMP snowflake freezes into place, ice cracks spread from it,
 * then the splash fades into the launcher. Pure drawing, no files or network, so it can never delay start-up
 * by more than its few seconds.
 */
final class FrostSplash {
    private static final double SECONDS = 2.6;
    private static final int WIDTH = 560;
    private static final int HEIGHT = 340;

    private FrostSplash() {}

    /** Shows the splash and calls {@code then} on the FX thread when it ends (immediately if it cannot be shown). */
    static void play(Runnable then) {
        Stage splash = new Stage(StageStyle.UNDECORATED);
        Canvas canvas = new Canvas(WIDTH, HEIGHT);
        splash.setScene(new Scene(new StackPane(canvas), WIDTH, HEIGHT, Color.web("#071321")));
        splash.setAlwaysOnTop(true);
        splash.centerOnScreen();
        Random random = new Random(42);
        double[][] flakes = new double[160][4]; // x, y, speed, size
        for (double[] flake : flakes) {
            flake[0] = random.nextDouble() * WIDTH;
            flake[1] = random.nextDouble() * HEIGHT;
            flake[2] = 120 + random.nextDouble() * 520;
            flake[3] = 1 + random.nextDouble() * 3.4;
        }
        double[][] cracks = crackLines(random);
        splash.show();
        new AnimationTimer() {
            private long start;

            @Override
            public void handle(long now) {
                if (start == 0) start = now;
                double time = (now - start) / 1e9;
                draw(canvas.getGraphicsContext2D(), time, flakes, cracks);
                if (time >= SECONDS) {
                    stop();
                    splash.close();
                    then.run();
                }
            }
        }.start();
    }

    private static double[][] crackLines(Random random) {
        double[][] lines = new double[26][4];
        double cx = WIDTH / 2.0;
        double cy = HEIGHT / 2.0 - 18;
        for (double[] line : lines) {
            double angle = random.nextDouble() * Math.PI * 2;
            double from = 52 + random.nextDouble() * 30;
            double length = 40 + random.nextDouble() * 120;
            line[0] = cx + Math.cos(angle) * from;
            line[1] = cy + Math.sin(angle) * from;
            line[2] = cx + Math.cos(angle + (random.nextDouble() - 0.5) * 0.6) * (from + length);
            line[3] = cy + Math.sin(angle + (random.nextDouble() - 0.5) * 0.6) * (from + length);
        }
        return lines;
    }

    private static void draw(GraphicsContext g, double time, double[][] flakes, double[][] cracks) {
        g.setFill(Color.web("#071321"));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        // Blizzard: snow streaks flying left to right, calming as the logo freezes.
        double calm = Math.min(1, Math.max(0, (time - 1.0) / 1.4));
        for (double[] flake : flakes) {
            double speed = flake[2] * (1.0 - 0.8 * calm);
            double x = (flake[0] + time * speed) % (WIDTH + 40) - 20;
            double y = (flake[1] + time * (40 + flake[2] * 0.18)) % HEIGHT;
            g.setStroke(Color.rgb(226, 247, 255, 0.35 + 0.4 * Math.min(1, flake[3] / 3)));
            g.setLineWidth(flake[3] * 0.7);
            double streak = (1.0 - calm) * flake[2] * 0.045;
            g.strokeLine(x - streak, y - streak * 0.25, x, y);
        }

        double cx = WIDTH / 2.0;
        double cy = HEIGHT / 2.0 - 18;
        double freeze = Math.min(1, Math.max(0, (time - 0.5) / 1.2));
        // Cold glow behind the crystal.
        for (int layer = 8; layer >= 1; layer--) {
            double radius = 40 + layer * 16;
            g.setFill(Color.rgb(0, 207, 255, 0.012 + 0.012 * freeze * (9 - layer) / 8));
            g.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
        }
        // The snowflake draws itself arm by arm.
        g.setLineCap(StrokeLineCap.ROUND);
        g.setStroke(Color.web("#B8F4FF", 0.35 + 0.65 * freeze));
        g.setLineWidth(5);
        double arm = 62 * freeze;
        for (int index = 0; index < 6; index++) {
            double angle = Math.toRadians(index * 60 - 90);
            double dx = Math.cos(angle);
            double dy = Math.sin(angle);
            g.strokeLine(cx, cy, cx + dx * arm, cy + dy * arm);
            double bx = cx + dx * arm * 0.62;
            double by = cy + dy * arm * 0.62;
            for (int side = -1; side <= 1; side += 2) {
                double branch = angle + side * Math.toRadians(50);
                g.strokeLine(bx, by, bx + Math.cos(branch) * arm * 0.3, by + Math.sin(branch) * arm * 0.3);
            }
        }
        g.setFill(Color.web("#00CFFF"));
        g.fillOval(cx - 5, cy - 5, 10, 10);
        // Ice cracks spread outward once it is frozen.
        double spread = Math.min(1, Math.max(0, (time - 1.3) / 0.9));
        g.setStroke(Color.web("#7CEBFF", 0.55 * spread));
        g.setLineWidth(1.2);
        for (double[] crack : cracks) {
            g.strokeLine(crack[0], crack[1], crack[0] + (crack[2] - crack[0]) * spread, crack[1] + (crack[3] - crack[1]) * spread);
        }
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(Color.web("#eaf9ff", Math.min(1, freeze * 1.4)));
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 26));
        g.fillText("VYRN", cx, HEIGHT - 66);
        g.setFill(Color.web("#7CEBFF", Math.min(1, spread)));
        g.setFont(Font.font("Segoe UI", 13));
        g.fillText("Play Beyond The Frost.", cx, HEIGHT - 42);
        // Fade out.
        double fade = Math.min(1, Math.max(0, (time - (SECONDS - 0.4)) / 0.4));
        if (fade > 0) {
            g.setFill(Color.rgb(7, 19, 33, fade));
            g.fillRect(0, 0, WIDTH, HEIGHT);
        }
    }
}
