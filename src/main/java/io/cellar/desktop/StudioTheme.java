package io.cellar.desktop;

import java.awt.Color;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.UIManager;
import javax.swing.border.Border;

final class StudioTheme {
    static final Color INK = new Color(0x151515);
    static final Color MUTED = new Color(0x484842);
    static final Color CANVAS = new Color(0xf6f5ef);
    static final Color PAPER = new Color(0xfffef9);
    static final Color YELLOW = new Color(0xffdf70);
    static final Color BLUE = new Color(0xcbdcff);
    static final Color CORAL = new Color(0xffc3b1);

    private StudioTheme() {}

    static Font uiFont(int size, int style) {
        return UIManager.getFont("Label.font").deriveFont(style, (float) size);
    }

    static JButton button(String content, char key, boolean primary) {
        JButton button = new JButton(content);
        button.setMnemonic(key);
        button.setFont(uiFont(13, Font.BOLD));
        button.setBackground(primary ? YELLOW : PAPER);
        button.setForeground(INK);
        button.setBorder(boxBorder(2, 2, 5, 9));
        return button;
    }

    static Border boxBorder(int stroke, int shadow, int vertical, int horizontal) {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, shadow, shadow, INK),
            BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(INK, stroke),
                BorderFactory.createEmptyBorder(vertical, horizontal, vertical, horizontal)));
    }

    static Border cardBorder() {
        return boxBorder(2, 4, 9, 10);
    }
}
