package quiz.gui.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;

import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;
import quiz.gui.ui.components.AppLogo;
import quiz.gui.ui.components.ThemedButton;
import quiz.gui.ui.components.ThemedLabel;

/**
 * The first window of the application: lets the user pick a {@link Theme}.
 *
 * <p>Each theme is shown as a card with a small preview painted in that theme's own
 * colours. The dialog itself uses whatever theme is current (the caller applies
 * {@link Theme#DEFAULT} first).</p>
 *
 * <p>Keyboard: Tab moves between controls, Left/Right or Space choose a card, Enter
 * confirms, Escape cancels. Double-clicking a card confirms it.</p>
 *
 * <p>Use {@link #showDialog()}; it must be called on the event dispatch thread.</p>
 */
public final class ThemeSelectionDialog extends JDialog {

    private static final String WINDOW_TITLE = "Choose a Theme";

    private final List<ThemeOptionCard> cards = new ArrayList<>();
    private final ButtonGroup group = new ButtonGroup();
    private final ThemedButton continueButton = new ThemedButton("Continue", ThemedButton.Variant.PRIMARY);
    private final ThemedButton cancelButton = new ThemedButton("Cancel", ThemedButton.Variant.SECONDARY);

    /** The confirmed theme; stays null if the dialog was cancelled or closed. */
    private Theme confirmedTheme;

    /**
     * Shows the dialog and waits until the user confirms or cancels.
     *
     * @return the chosen theme, or empty if the user cancelled or closed the dialog
     */
    public static Optional<Theme> showDialog() {
        ThemeSelectionDialog dialog = new ThemeSelectionDialog(Theme.DEFAULT);
        dialog.setVisible(true); // blocks here (modal) until the dialog is disposed
        return Optional.ofNullable(dialog.confirmedTheme);
    }

    private ThemeSelectionDialog(Theme initialSelection) {
        // A null owner with an explicit modality gives a real top-level window, so it has
        // its own taskbar entry while it is the only window of the application.
        super((Window) null, WINDOW_TITLE, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); // closing = cancelling
        setIconImages(AppLogo.iconImages());
        setResizable(false);

        JPanel content = new JPanel(new BorderLayout(0, ThemeManager.SPACE_XL));
        content.setBackground(ThemeManager.current().getBackground());
        content.setBorder(BorderFactory.createEmptyBorder(
                ThemeManager.SPACE_XXL, ThemeManager.SPACE_XXL, ThemeManager.SPACE_XL, ThemeManager.SPACE_XXL));
        content.add(createHeader(), BorderLayout.NORTH);
        content.add(createCards(initialSelection), BorderLayout.CENTER);
        content.add(createButtons(), BorderLayout.SOUTH);
        setContentPane(content);

        installKeyboardShortcuts();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                continueButton.requestFocusInWindow();
            }
        });

        pack();
        setLocationRelativeTo(null); // centre on screen
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private JComponent createHeader() {
        JPanel header = new JPanel(new BorderLayout(ThemeManager.SPACE_L, 0));
        header.setOpaque(false);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(new ThemedLabel("Choose your theme", ThemedLabel.Style.TITLE));
        text.add(Box.createVerticalStrut(ThemeManager.SPACE_XS));
        text.add(new ThemedLabel("Pick a look for the quiz. It stays the same until you close the application.",
                ThemedLabel.Style.SECONDARY));

        header.add(new AppLogo(48), BorderLayout.WEST);
        header.add(text, BorderLayout.CENTER);
        return header;
    }

    private JComponent createCards(Theme initialSelection) {
        JPanel row = new JPanel(new GridLayout(1, 0, ThemeManager.SPACE_L, 0));
        row.setOpaque(false);
        for (Theme theme : Theme.values()) {
            ThemeOptionCard card = new ThemeOptionCard(theme);
            card.setSelected(theme == initialSelection);
            group.add(card);
            cards.add(card);
            row.add(card);
        }
        return row;
    }

    private JComponent createButtons() {
        continueButton.addActionListener(e -> confirm());
        cancelButton.addActionListener(e -> cancel());
        getRootPane().setDefaultButton(continueButton); // Enter = Continue

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(Box.createHorizontalGlue());
        buttons.add(cancelButton);
        buttons.add(Box.createHorizontalStrut(ThemeManager.SPACE_S));
        buttons.add(continueButton);
        return buttons;
    }

    private void installKeyboardShortcuts() {
        JComponent root = getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        root.getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancel();
            }
        });
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void confirm() {
        confirmedTheme = selectedTheme();
        dispose();
    }

    private void cancel() {
        confirmedTheme = null;
        dispose();
    }

    private Theme selectedTheme() {
        for (ThemeOptionCard card : cards) {
            if (card.isSelected()) {
                return card.theme;
            }
        }
        return Theme.DEFAULT;
    }

    /** Selects and focuses the card {@code offset} places away from the selected one (wrapping). */
    private void moveSelection(int offset) {
        int index = cards.indexOf(cardFor(selectedTheme()));
        ThemeOptionCard next = cards.get(Math.floorMod(index + offset, cards.size()));
        next.setSelected(true);
        next.requestFocusInWindow();
    }

    private ThemeOptionCard cardFor(Theme theme) {
        for (ThemeOptionCard card : cards) {
            if (card.theme == theme) {
                return card;
            }
        }
        return cards.getFirst();
    }

    // ------------------------------------------------------------------
    // Theme card
    // ------------------------------------------------------------------

    /**
     * One selectable theme. It is a toggle button (so it works with the keyboard, screen
     * readers and a {@link ButtonGroup}) that contains a preview and two labels.
     */
    private final class ThemeOptionCard extends JToggleButton {

        private static final int SELECTED_BORDER = 2;

        private final Theme theme;
        private final Theme dialogTheme = ThemeManager.current();

        ThemeOptionCard(Theme theme) {
            this.theme = theme;
            setLayout(new BorderLayout(0, ThemeManager.SPACE_M));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(
                    ThemeManager.SPACE_L, ThemeManager.SPACE_L, ThemeManager.SPACE_L, ThemeManager.SPACE_L));
            getAccessibleContext().setAccessibleName(theme.getDisplayName());
            getAccessibleContext().setAccessibleDescription(theme.getDescription());

            JPanel nameRow = new JPanel(new BorderLayout(ThemeManager.SPACE_S, 0));
            nameRow.setOpaque(false);
            nameRow.add(new ThemedLabel(theme.getDisplayName(), ThemedLabel.Style.HEADING), BorderLayout.CENTER);
            nameRow.add(new SelectionIndicator(), BorderLayout.EAST);

            JPanel text = new JPanel(new BorderLayout(0, ThemeManager.SPACE_XS));
            text.setOpaque(false);
            text.add(nameRow, BorderLayout.NORTH);
            text.add(new ThemedLabel(theme.getDescription(), ThemedLabel.Style.SECONDARY), BorderLayout.CENTER);

            add(new ThemePreview(theme), BorderLayout.CENTER);
            add(text, BorderLayout.SOUTH);

            addItemListener(e -> repaint()); // redraw the outline and indicator when (de)selected
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        confirm();
                    }
                }
            });
            bindArrowKey(KeyEvent.VK_LEFT, "previousTheme", -1);
            bindArrowKey(KeyEvent.VK_RIGHT, "nextTheme", 1);
        }

        private void bindArrowKey(int keyCode, String name, int offset) {
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, 0), name);
            getActionMap().put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    moveSelection(offset);
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = ThemeManager.CORNER_RADIUS + 4;
                int w = getWidth();
                int h = getHeight();

                // Outline (drawn as a filled shape under the fill):
                //   selected -> accent; keyboard focus -> one pixel thicker; otherwise -> border colour.
                int outline = 1;
                Color outlineColor = dialogTheme.getBorder();
                if (isSelected()) {
                    outline = SELECTED_BORDER;
                    outlineColor = dialogTheme.getAccent();
                } else if (isFocusOwner()) {
                    outline = SELECTED_BORDER;
                    outlineColor = dialogTheme.getTextSecondary();
                }
                if (isFocusOwner()) {
                    outline++;
                }
                g2.setColor(outlineColor);
                g2.fillRoundRect(0, 0, w, h, arc, arc);

                g2.setColor(getModel().isRollover() && !isSelected() ? dialogTheme.getHover() : dialogTheme.getSurface());
                g2.fillRoundRect(outline, outline, w - 2 * outline, h - 2 * outline, arc - outline, arc - outline);
            } finally {
                g2.dispose();
            }
        }

        /** A radio-style circle that is filled while the card is selected. */
        private final class SelectionIndicator extends JComponent {

            private static final int SIZE = 18;

            SelectionIndicator() {
                setPreferredSize(new Dimension(SIZE, SIZE));
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int x = (getWidth() - SIZE) / 2;
                    int y = (getHeight() - SIZE) / 2;
                    if (isSelected()) {
                        g2.setColor(dialogTheme.getAccent());
                        g2.fillOval(x, y, SIZE, SIZE);
                        g2.setColor(dialogTheme.getOnAccent());
                        g2.fillOval(x + 5, y + 5, SIZE - 10, SIZE - 10);
                    } else {
                        g2.setColor(dialogTheme.getTextSecondary());
                        g2.setStroke(new BasicStroke(1.5f));
                        g2.drawOval(x + 1, y + 1, SIZE - 3, SIZE - 3);
                    }
                } finally {
                    g2.dispose();
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Theme preview
    // ------------------------------------------------------------------

    /**
     * A miniature, purely decorative window painted in the given theme's colours:
     * a header bar, a question card with text lines, and four answer rows.
     */
    private static final class ThemePreview extends JComponent {

        private final Theme theme;

        ThemePreview(Theme theme) {
            this.theme = theme;
            setPreferredSize(new Dimension(260, 160));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();

                // Window
                g2.setColor(theme.getBorder());
                g2.fillRoundRect(0, 0, w, h, 12, 12);
                g2.setColor(theme.getBackground());
                g2.fillRoundRect(1, 1, w - 2, h - 2, 11, 11);

                // Header bar with logo dot and title line
                int headerHeight = 24;
                g2.setColor(theme.getSurface());
                g2.fillRoundRect(1, 1, w - 2, headerHeight, 11, 11);
                g2.fillRect(1, headerHeight / 2, w - 2, headerHeight / 2 + 1);
                g2.setColor(theme.getBorder());
                g2.drawLine(1, headerHeight + 1, w - 2, headerHeight + 1);
                g2.setColor(theme.getAccent());
                g2.fillRoundRect(10, 7, 12, 12, 4, 4);
                bar(g2, theme.getTextPrimary(), 28, 11, 60, 4);

                // Question card
                int cardX = 12;
                int cardY = headerHeight + 12;
                int cardW = w - 24;
                int cardH = h - cardY - 12;
                g2.setColor(theme.getBorder());
                g2.fillRoundRect(cardX, cardY, cardW, cardH, 10, 10);
                g2.setColor(theme.getSurface());
                g2.fillRoundRect(cardX + 1, cardY + 1, cardW - 2, cardH - 2, 9, 9);
                bar(g2, theme.getTextPrimary(), cardX + 12, cardY + 12, cardW * 3 / 5, 6);
                bar(g2, theme.getTextSecondary(), cardX + 12, cardY + 24, cardW * 2 / 5, 4);

                // Four answers in a 2x2 grid; the first one looks "selected"
                int gap = 8;
                int answerTop = cardY + 38;
                int answerW = (cardW - 24 - gap) / 2;
                int answerH = Math.max(10, (cardY + cardH - 10 - answerTop - gap) / 2);
                for (int i = 0; i < 4; i++) {
                    int ax = cardX + 12 + (i % 2) * (answerW + gap);
                    int ay = answerTop + (i / 2) * (answerH + gap);
                    boolean selected = i == 0;
                    g2.setColor(selected ? theme.getAccent() : theme.getBorder());
                    g2.fillRoundRect(ax, ay, answerW, answerH, 8, 8);
                    if (!selected) {
                        g2.setColor(theme.getInputBackground());
                        g2.fillRoundRect(ax + 1, ay + 1, answerW - 2, answerH - 2, 7, 7);
                    }
                    bar(g2, selected ? theme.getOnAccent() : theme.getTextSecondary(),
                            ax + 8, ay + answerH / 2 - 2, answerW / 2, 4);
                }
            } finally {
                g2.dispose();
            }
        }

        /** A rounded line standing in for a line of text. */
        private static void bar(Graphics2D g2, Color color, int x, int y, int width, int height) {
            g2.setColor(color);
            g2.fillRoundRect(x, y, width, height, height, height);
        }
    }
}
