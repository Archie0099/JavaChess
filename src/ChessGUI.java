import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chess GUI.
 *
 * Improvements over the original:
 *   - AI search runs on a background thread (SwingWorker) — the window never
 *     freezes while the engine thinks; a status bar shows "AI is thinking…".
 *   - Clicks and controls are blocked while the AI is thinking.
 *   - Undo/Redo step TWO plies in Human-vs-AI mode, so it is always the
 *     human's turn afterwards (the old version undid one ply and stalled).
 *   - Resign asks for confirmation and starts a fresh game with the same
 *     dialogs (the old version silently dropped the AI).
 *   - Move list shows real SAN ("Nf3", "exd5", "O-O", "Qxf7#") from Game.
 *   - Piece icons are scaled once and cached (the old version rescaled all
 *     32 images on every repaint).
 *
 * Piece images live in ./resources/ as Chess_<sym><l|d>t60.png.
 */
public class ChessGUI extends JFrame {
    private Game game;
    private ImprovedAI ai = null;
    private boolean aiPlaysWhite = false;
    private boolean thinking = false;

    private final JButton[][] squares = new JButton[8][8];
    private final int TILE = 72;
    private Position selected = null;
    private List<Move> highlighted = null;
    private Position lastFrom = null, lastTo = null;

    private final DefaultListModel<String> moveListModel = new DefaultListModel<>();
    private JList<String> moveList;
    private JButton backBtn, fwdBtn, aiBtn, resignBtn;
    private JLabel statusLabel;

    private final Map<String, ImageIcon> iconCache = new HashMap<>();

    public ChessGUI() {
        setTitle("Java Chess");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        buildUi();
        pack();
        setLocationRelativeTo(null);
        setVisible(true);

        // Paint the starting position immediately, then ask for the mode in a
        // separate event so the board is on screen before the modal dialog
        // opens. A modal dialog shown inline here (before the first paint)
        // never lets the frame draw under CheerpJ in the browser, leaving a
        // blank window; this ordering avoids that and is harmless on desktop.
        game = new Game();
        refresh();
        updateNavButtons();
        SwingUtilities.invokeLater(this::newGame);
    }

    // ---------------------------------------------------------------------
    //  UI construction (runs once)
    // ---------------------------------------------------------------------

    private void buildUi() {
        JPanel leftLabels = new JPanel(new GridLayout(8, 1));
        leftLabels.setPreferredSize(new Dimension(24, TILE * 8));
        for (int r = 0; r < 8; r++)
            leftLabels.add(new JLabel(String.valueOf(8 - r), SwingConstants.CENTER));

        JPanel boardPanel = new JPanel(new GridLayout(8, 8));
        boardPanel.setPreferredSize(new Dimension(TILE * 8, TILE * 8));
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                JButton b = new JButton();
                b.setPreferredSize(new Dimension(TILE, TILE));
                b.setMargin(new Insets(0, 0, 0, 0));
                b.setFocusPainted(false);
                final int rr = r, cc = c;
                b.addActionListener(e -> onClick(rr, cc));
                b.setHorizontalTextPosition(SwingConstants.CENTER);
                b.setVerticalTextPosition(SwingConstants.CENTER);
                squares[r][c] = b;
                boardPanel.add(b);
            }
        }

        JPanel bottomFiles = new JPanel(new GridLayout(1, 8));
        bottomFiles.setPreferredSize(new Dimension(TILE * 8, 20));
        for (char f = 'a'; f <= 'h'; f++)
            bottomFiles.add(new JLabel(String.valueOf(f), SwingConstants.CENTER));

        JPanel center = new JPanel(new BorderLayout());
        center.add(leftLabels, BorderLayout.WEST);
        center.add(boardPanel, BorderLayout.CENTER);
        center.add(bottomFiles, BorderLayout.SOUTH);

        moveList = new JList<>(moveListModel);
        moveList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        JScrollPane moveScroll = new JScrollPane(moveList);
        moveScroll.setPreferredSize(new Dimension(220, TILE * 8));

        backBtn   = new JButton("\u25C0");   // left triangle (escape: same on any javac encoding)
        fwdBtn    = new JButton("\u25B6");   // right triangle
        aiBtn     = new JButton("AI Move");
        resignBtn = new JButton("Resign");
        backBtn.addActionListener(e -> { if (!thinking) doUndo(); });
        fwdBtn.addActionListener(e -> { if (!thinking) doRedo(); });
        aiBtn.addActionListener(e -> { if (!thinking) startAiMove(); });
        resignBtn.addActionListener(e -> { if (!thinking) doResign(); });

        JPanel ctrl = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
        ctrl.add(backBtn);
        ctrl.add(fwdBtn);
        ctrl.add(aiBtn);
        ctrl.add(resignBtn);

        statusLabel = new JLabel(" ", SwingConstants.CENTER);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC));

        JPanel south = new JPanel(new BorderLayout());
        south.add(statusLabel, BorderLayout.NORTH);
        south.add(ctrl, BorderLayout.SOUTH);

        add(center, BorderLayout.CENTER);
        add(moveScroll, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);
    }

    // ---------------------------------------------------------------------
    //  Game lifecycle
    // ---------------------------------------------------------------------

    /** Show mode/difficulty dialogs and reset all state for a fresh game. */
    private void newGame() {
        String[] modes = {"Human vs Human", "Play vs AI (You White)", "Play vs AI (You Black)"};
        int sel = JOptionPane.showOptionDialog(this, "Choose mode:", "New Game",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, modes, modes[0]);
        if (sel < 0) sel = 0;

        ai = null;
        aiPlaysWhite = false;
        if (sel == 1 || sel == 2) {
            String[] dif = {"Easy", "Medium", "Hard"};
            int d = JOptionPane.showOptionDialog(this, "Choose difficulty:", "AI Difficulty",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, dif, dif[1]);
            int aiDepth = (d == 0 ? 1 : (d == 2 ? 3 : 2));
            setStatus("Loading opening book...");
            ai = new ImprovedAI(aiDepth);
            aiPlaysWhite = (sel == 2);
        }

        game = new Game();
        selected = null;
        highlighted = null;
        lastFrom = lastTo = null;
        moveListModel.clear();
        setStatus(" ");
        refresh();
        updateNavButtons();

        if (ai != null && aiPlaysWhite) startAiMove();
    }

    private void doResign() {
        if (game.isGameOver()) { newGame(); return; }
        int ok = JOptionPane.showConfirmDialog(this,
                game.getCurrentPlayer() + " resigns — " + game.getCurrentPlayer().opposite()
                        + " wins.\nStart a new game?",
                "Resign", JOptionPane.OK_CANCEL_OPTION);
        if (ok == JOptionPane.OK_OPTION) newGame();
    }

    // ---------------------------------------------------------------------
    //  Undo / redo (two plies against the AI so it stays the human's turn)
    // ---------------------------------------------------------------------

    /** When the AI plays White its first move can't be undone (the game would
     *  be stuck waiting on the AI at move 0). */
    private int undoFloor() {
        return (ai != null && aiPlaysWhite) ? 1 : 0;
    }

    private void doUndo() {
        int floor = undoFloor();
        if (game.getHistory().size() <= floor) return;
        game.undo();
        if (ai != null && isAiTurn() && game.getHistory().size() > floor) game.undo();
        afterNavigation();
    }

    private void doRedo() {
        if (!game.canRedo()) return;
        game.redo();
        if (ai != null && isAiTurn() && game.canRedo()) game.redo();
        afterNavigation();
        // redo landed on the AI's turn with nothing left to redo (e.g. the game
        // had ended on a human move) — let the engine take over again
        if (ai != null && isAiTurn() && !game.canRedo() && !game.isGameOver())
            startAiMove();
    }

    private void afterNavigation() {
        selected = null;
        highlighted = null;
        List<Move> h = game.getHistory();
        if (h.isEmpty()) { lastFrom = lastTo = null; }
        else { Move m = h.get(h.size() - 1); lastFrom = m.from; lastTo = m.to; }
        refresh();
        updateMoveList();
        updateNavButtons();
    }

    private boolean isAiTurn() {
        if (ai == null) return false;
        return (aiPlaysWhite && game.getCurrentPlayer() == PieceColor.WHITE)
            || (!aiPlaysWhite && game.getCurrentPlayer() == PieceColor.BLACK);
    }

    // ---------------------------------------------------------------------
    //  Human move handling
    // ---------------------------------------------------------------------

    private void onClick(int r, int c) {
        if (thinking) return;                       // engine busy
        if (ai != null && isAiTurn()) return;       // not your turn
        if (game.isGameOver()) return;

        Position pos = new Position(r, c);
        Piece p = game.getBoard().getPiece(pos);

        if (selected == null) {
            if (p != null && p.getColor() == game.getCurrentPlayer()) {
                selected = pos;
                highlighted = game.getLegalMovesFor(pos);
            }
        } else {
            Move chosen = null;
            for (Move m : game.getLegalMovesFor(selected)) {
                if (m.to.equals(pos)) { chosen = m; break; }
            }

            if (chosen != null) {
                if (chosen.isPromotion) {
                    String[] pieces = {"Queen", "Rook", "Bishop", "Knight"};
                    int choice = JOptionPane.showOptionDialog(this,
                            "Choose promotion piece:", "Pawn Promotion",
                            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                            null, pieces, pieces[0]);
                    if (choice < 0) {          // dialog closed/cancelled: abort the move
                        selected = null;
                        highlighted = null;
                        refresh();
                        return;
                    }
                    char[] promotionChars = {'Q', 'R', 'B', 'N'};
                    chosen.promotion = promotionChars[choice];
                }

                if (game.makeMove(chosen)) {
                    lastFrom = chosen.from;
                    lastTo = chosen.to;
                    selected = null;
                    highlighted = null;
                    refresh();
                    updateMoveList();
                    updateNavButtons();
                    showGameEndDialogIfNeeded();
                    if (!game.isGameOver() && ai != null && isAiTurn()) startAiMove();
                }
            } else {
                if (p != null && p.getColor() == game.getCurrentPlayer()) {
                    selected = pos;
                    highlighted = game.getLegalMovesFor(pos);
                } else {
                    selected = null;
                    highlighted = null;
                }
            }
        }
        refresh();
    }

    // ---------------------------------------------------------------------
    //  AI move on a background thread
    // ---------------------------------------------------------------------

    private void startAiMove() {
        if (ai == null) {
            JOptionPane.showMessageDialog(this, "No AI in this game mode.");
            return;
        }
        if (game.isGameOver() || thinking) return;

        thinking = true;
        setControlsEnabled(false);
        setStatus("AI is thinking...");

        new SwingWorker<Move, Void>() {
            @Override protected Move doInBackground() {
                return ai.findBestMove(game);
            }
            @Override protected void done() {
                Move m = null;
                try { m = get(); } catch (Exception ignored) {}
                thinking = false;
                setControlsEnabled(true);
                setStatus(" ");
                if (m != null && game.makeMove(m)) {
                    lastFrom = m.from;
                    lastTo = m.to;
                }
                selected = null;
                highlighted = null;
                refresh();
                updateMoveList();
                updateNavButtons();
                showGameEndDialogIfNeeded();
            }
        }.execute();
    }

    private void setControlsEnabled(boolean on) {
        aiBtn.setEnabled(on);
        resignBtn.setEnabled(on);
        if (on) updateNavButtons();
        else { backBtn.setEnabled(false); fwdBtn.setEnabled(false); }
    }

    private void setStatus(String s) {
        statusLabel.setText(s == null || s.isEmpty() ? " " : s);
        statusLabel.paintImmediately(statusLabel.getBounds());
    }

    // ---------------------------------------------------------------------
    //  Dialogs / rendering
    // ---------------------------------------------------------------------

    private void showGameEndDialogIfNeeded() {
        if (!game.isGameOver()) return;
        String msg = capitalizeResultString(game.getResultString());
        Object[] opts = {"New Game", "Close"};
        int pick = JOptionPane.showOptionDialog(this, msg, "Game Over",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, opts, opts[0]);
        if (pick == 0) newGame();
    }

    private String capitalizeResultString(String s) {
        if (s == null) return null;
        s = s.replaceAll("checkmate", "Checkmate");
        s = s.replaceAll("stalemate", "Stalemate");
        s = s.replaceAll("draw", "Draw");
        return s;
    }

    private void refresh() {
        Border captureBorder = BorderFactory.createLineBorder(Color.RED, 3);
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                JButton btn = squares[r][c];
                Piece p = game.getBoard().getPiece(new Position(r, c));

                Color base = ((r + c) % 2 == 0) ? new Color(240, 217, 181) : new Color(181, 136, 99);
                btn.setBackground(base);
                btn.setBorder(UIManager.getBorder("Button.border"));
                btn.setText("");
                btn.setFont(new Font("SansSerif", Font.BOLD, 28));
                btn.setForeground(Color.GREEN.darker());

                if (lastFrom != null && lastTo != null
                        && ((lastFrom.row == r && lastFrom.col == c)
                         || (lastTo.row == r && lastTo.col == c))) {
                    btn.setBackground(new Color(106, 159, 181));
                }

                if (selected != null && selected.row == r && selected.col == c) {
                    btn.setBackground(new Color(255, 255, 128));
                }

                if (highlighted != null) {
                    for (Move m : highlighted) {
                        if (m.to.row == r && m.to.col == c) {
                            if (game.getBoard().getPiece(m.to) == null) {
                                btn.setText("\u25CF");
                                btn.setForeground(new Color(20, 180, 0));
                                btn.setFont(new Font("SansSerif", Font.BOLD, 36));
                            } else {
                                btn.setBorder(captureBorder);
                            }
                        }
                    }
                }

                if (p != null) {
                    ImageIcon icon = pieceIcon(p);
                    if (icon != null) { btn.setIcon(icon); btn.setText(""); }
                    else {
                        btn.setIcon(null);
                        btn.setText(String.valueOf(p.getSymbol()));
                        btn.setForeground(Color.BLACK);
                    }
                } else {
                    btn.setIcon(null);
                }
            }
        }
    }

    /** Pre-scaled icons, loaded and scaled once, then cached. */
    private ImageIcon pieceIcon(Piece p) {
        char s = Character.toLowerCase(p.getSymbol());
        String col = (p.getColor() == PieceColor.WHITE) ? "l" : "d";
        String key = s + col;
        if (iconCache.containsKey(key)) return iconCache.get(key);

        String filename = "Chess_" + s + col + "t60.png";
        int sz = TILE - 12;
        ImageIcon scaled = null;
        // Decode the PNG with ImageIO and rescale once into a BufferedImage. This
        // is faster and more reliable than ImageIcon + getScaledInstance, which
        // renders slowly and sometimes not at all under CheerpJ in the browser.
        try {
            java.io.InputStream in = getClass().getResourceAsStream("/resources/" + filename);
            if (in == null) {
                File f = new File("resources" + File.separator + filename);
                if (f.exists()) in = new java.io.FileInputStream(f);
            }
            if (in != null) {
                java.awt.image.BufferedImage src;
                try { src = javax.imageio.ImageIO.read(in); }
                finally { in.close(); }
                if (src != null) {
                    java.awt.image.BufferedImage dst =
                            new java.awt.image.BufferedImage(sz, sz, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = dst.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.drawImage(src, 0, 0, sz, sz, null);
                    g.dispose();
                    scaled = new ImageIcon(dst);
                }
            }
        } catch (Exception ex) {
            scaled = null;   // refresh() falls back to a letter glyph
        }
        iconCache.put(key, scaled);   // cache nulls too: avoids re-probing missing files
        return scaled;
    }

    private void updateMoveList() {
        moveListModel.clear();
        List<String> san = game.getSANHistory();
        for (int i = 0; i < san.size(); i += 2) {
            String row = (i / 2 + 1) + ". " + san.get(i);
            if (i + 1 < san.size()) row += "  " + san.get(i + 1);
            moveListModel.addElement(row);
        }
        if (!moveListModel.isEmpty())
            moveList.ensureIndexIsVisible(moveListModel.size() - 1);
    }

    private void updateNavButtons() {
        backBtn.setEnabled(game.getHistory().size() > undoFloor());
        fwdBtn.setEnabled(game.canRedo());
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ChessGUI::new);
    }
}
