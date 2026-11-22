package com.hingebot;

public record Node(
    String text,
    String desc,
    int x1,
    int y1,
    int x2,
    int y2,
    boolean clickable,
    String pkg
) {
    public int width() {
        return Math.max(0, x2 - x1);
    }

    public int height() {
        return Math.max(0, y2 - y1);
    }

    public int area() {
        return width() * height();
    }

    public int centerX() {
        return (x1 + x2) / 2;
    }

    public int centerY() {
        return (y1 + y2) / 2;
    }
}
