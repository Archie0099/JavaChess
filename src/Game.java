import java.util.*;

/**
 * Game: rules engine (legality, en passant, castling, draws) + history.
 *
 * Fixes vs previous version:
 *   - redo() no longer crashes: undo() keeps moveHistory intact; only
 *     makeMove() truncates the forward branch.
 *   - SAN move list with disambiguation and +/# markers (getSANHistory()).
 *   - getPositionSnapshots() lets the AI detect repetitions.
 *   - Dead try/catch blocks removed.
 */
public class Game {
    private Board board;
    private PieceColor currentPlayer;

    private final List<Board>  boardHistory = new ArrayList<>();
    private final List<Move>   moveHistory  = new ArrayList<>();
    private final List<String> sanHistory   = new ArrayList<>();
    private int historyIndex = 0;

    // set only for the very next move after a pawn double-step
    private Position enPassantTarget = null;

    // 50-move rule: half-moves since last pawn move or capture
    private int halfmoveClock = 0;

    // threefold repetition
    private final Map<String, Integer> positionCounts = new HashMap<>();

    public Game() {
        board = new Board();
        currentPlayer = PieceColor.WHITE;

        boardHistory.add(board.deepCopy());
        historyIndex = 0;

        enPassantTarget = null;
        halfmoveClock = 0;

        positionCounts.clear();
        positionCounts.put(makePositionKey(board, currentPlayer, enPassantTarget), 1);
    }

    public Board getBoard() { return board; }
    public PieceColor getCurrentPlayer() { return currentPlayer; }

    /** Moves played up to the current point (respects undo). */
    public List<Move> getHistory() {
        return new ArrayList<>(moveHistory.subList(0, historyIndex));
    }

    /** SAN strings ("Nf3", "exd5", "O-O", "Qxf7#") up to the current point. */
    public List<String> getSANHistory() {
        return new ArrayList<>(sanHistory.subList(0, historyIndex));
    }

    public boolean canUndo() { return historyIndex > 0; }
    public boolean canRedo() { return historyIndex < boardHistory.size() - 1; }

    /**
     * Snapshots of every position from the start up to (and including) the
     * current one. Index i is the position before move i; side to move
     * alternates starting with White. Used by the AI for repetition detection.
     */
    public List<Board> getPositionSnapshots() {
        List<Board> out = new ArrayList<>(historyIndex + 1);
        for (int i = 0; i <= historyIndex && i < boardHistory.size(); i++) {
            out.add(boardHistory.get(i));
        }
        return out;
    }

    /** All legal moves for the given color. */
    public List<Move> getAllLegalMoves(PieceColor forColor) {
        List<Move> out = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Position from = new Position(r, c);
                Piece p = board.getPiece(from);
                if (p == null || p.getColor() != forColor) continue;
                out.addAll(getLegalMovesFor(from));
            }
        }
        return out;
    }

    /** Legal moves for the piece at 'from' (filters out moves that leave own king in check). */
    public List<Move> getLegalMovesFor(Position from) {
        List<Move> res = new ArrayList<>();
        Piece p = board.getPiece(from);
        if (p == null) return res;

        // 1) pseudo-legal from the piece itself
        List<Move> pseudo = p.getLegalMoves(from, board);

        // 2) add en-passant candidate (if applicable)
        if (p instanceof Pawn && enPassantTarget != null) {
            int dir = (p.getColor() == PieceColor.WHITE) ? -1 : 1;
            if (enPassantTarget.row == from.row + dir &&
                Math.abs(enPassantTarget.col - from.col) == 1) {
                Piece captured = board.getPiece(new Position(from.row, enPassantTarget.col));
                if (captured instanceof Pawn && captured.getColor() != p.getColor()) {
                    Move epMove = new Move(from, enPassantTarget, p, captured);
                    epMove.isEnPassant = true;
                    pseudo.add(epMove);
                }
            }
        }

        // 3) keep only moves that don't leave our king in check
        for (Move m : pseudo) {
            Board copy = board.deepCopy();
            applyMoveToBoard(copy, m);
            if (!copy.isKingInCheck(p.getColor())) res.add(m);
        }
        return res;
    }

    /** Make a legal move; updates clocks, en-passant target, histories, repetition map. */
    public boolean makeMove(Move move) {
        Piece mover = board.getPiece(move.from);
        if (mover == null || mover.getColor() != currentPlayer) return false;

        // Verify move is legal
        boolean found = false;
        for (Move m : getLegalMovesFor(move.from)) {
            if (moveEquals(m, move)) {
                found = true;
                break;
            }
        }
        if (!found) return false;

        // Handle promotion selection if needed
        if (move.isPromotion && move.promotion == '\0') {
            move.promotion = 'Q'; // Default to queen if not specified
        }

        // SAN (needs the position *before* the move for disambiguation)
        String san = sanBase(move, mover);

        // set new en-passant target if this is a pawn double-step
        Position newEPTarget = null;
        if (mover instanceof Pawn && Math.abs(move.from.row - move.to.row) == 2) {
            newEPTarget = new Position((move.from.row + move.to.row) / 2, move.to.col);
        }

        // capture detection BEFORE applying (for 50-move clock)
        Piece destBefore = board.getPiece(move.to);
        boolean epCaptureBefore = (mover instanceof Pawn) &&
                                  (move.from.col != move.to.col) &&
                                  (destBefore == null);

        applyMoveToBoard(board, move);

        // 50-move rule update (resets on pawn move, capture, or en passant)
        if (mover instanceof Pawn || destBefore != null || epCaptureBefore) {
            halfmoveClock = 0;
        } else {
            halfmoveClock++;
        }

        // en-passant target only valid for next move
        enPassantTarget = newEPTarget;

        // truncate forward branch (if we undid earlier) then push new state
        while (boardHistory.size() > historyIndex + 1) {
            boardHistory.remove(boardHistory.size() - 1);
        }
        while (moveHistory.size() > historyIndex) {
            moveHistory.remove(moveHistory.size() - 1);
        }
        while (sanHistory.size() > historyIndex) {
            sanHistory.remove(sanHistory.size() - 1);
        }

        // switch sides
        currentPlayer = currentPlayer.opposite();

        // finish SAN with check/mate marker (needs the position *after* the move)
        if (isInCheckmate())            san += "#";
        else if (isInCheck(currentPlayer)) san += "+";

        boardHistory.add(board.deepCopy());
        moveHistory.add(move);
        sanHistory.add(san);
        historyIndex++;

        // update repetition map
        String key = makePositionKey(board, currentPlayer, enPassantTarget);
        positionCounts.put(key, positionCounts.getOrDefault(key, 0) + 1);

        return true;
    }

    // Helper to compare moves properly including promotion
    private boolean moveEquals(Move m1, Move m2) {
        return m1.from.equals(m2.from) &&
               m1.to.equals(m2.to) &&
               m1.isPromotion == m2.isPromotion &&
               m1.promotion == m2.promotion;
    }

    // ---------------------------------------------------------------------
    //  Standard Algebraic Notation
    // ---------------------------------------------------------------------

    /** SAN without the +/# suffix; must be called before the move is applied. */
    private String sanBase(Move move, Piece mover) {
        // Castling
        if (mover instanceof King && Math.abs(move.from.col - move.to.col) == 2) {
            return (move.to.col == 6) ? "O-O" : "O-O-O";
        }

        StringBuilder sb = new StringBuilder();
        boolean isPawn = mover instanceof Pawn;
        Piece destBefore = board.getPiece(move.to);
        boolean isCapture = destBefore != null || move.isEnPassant ||
                            (isPawn && move.from.col != move.to.col);

        if (!isPawn) {
            sb.append(Character.toUpperCase(mover.getSymbol()));
            sb.append(disambiguate(move, mover));
        } else if (isCapture) {
            sb.append((char) ('a' + move.from.col));
        }

        if (isCapture) sb.append('x');
        sb.append(move.to.toAlgebraic());

        if (move.isPromotion) {
            sb.append('=').append(Character.toUpperCase(
                    move.promotion == '\0' ? 'Q' : move.promotion));
        }
        return sb.toString();
    }

    /** File/rank disambiguation when another identical piece can reach the same square. */
    private String disambiguate(Move move, Piece mover) {
        List<Position> rivals = new ArrayList<>();
        for (Move m : getAllLegalMoves(mover.getColor())) {
            if (m.to.equals(move.to) && !m.from.equals(move.from)
                    && m.movedPiece != null
                    && m.movedPiece.getClass() == mover.getClass()) {
                if (!rivals.contains(m.from)) rivals.add(m.from);
            }
        }
        if (rivals.isEmpty()) return "";

        boolean fileUnique = true, rankUnique = true;
        for (Position r : rivals) {
            if (r.col == move.from.col) fileUnique = false;
            if (r.row == move.from.row) rankUnique = false;
        }
        if (fileUnique) return String.valueOf((char) ('a' + move.from.col));
        if (rankUnique) return String.valueOf(8 - move.from.row);
        return move.from.toAlgebraic();
    }

    /** Apply a move onto a board (handles castling, promotions, en-passant). */
    private void applyMoveToBoard(Board b, Move m) {
        Piece mover = b.getPiece(m.from);
        if (mover == null) return;

        // Castling: king moves two files
        if (mover instanceof King && Math.abs(m.from.col - m.to.col) == 2) {
            int r = m.from.row;
            if (m.to.col == 6) { // king side
                b.setPiece(new Position(r,6), mover.copy());
                b.setPiece(m.from, null);
                Piece rook = b.getPiece(new Position(r,7));
                if (rook != null) {
                    b.setPiece(new Position(r,5), rook.copy());
                    b.setPiece(new Position(r,7), null);
                }
                Piece k2 = b.getPiece(new Position(r,6));
                if (k2 != null) k2.setMoved(true);
                Piece r2 = b.getPiece(new Position(r,5));
                if (r2 != null) r2.setMoved(true);
                return;
            } else if (m.to.col == 2) { // queen side
                b.setPiece(new Position(r,2), mover.copy());
                b.setPiece(m.from, null);
                Piece rook = b.getPiece(new Position(r,0));
                if (rook != null) {
                    b.setPiece(new Position(r,3), rook.copy());
                    b.setPiece(new Position(r,0), null);
                }
                Piece k2 = b.getPiece(new Position(r,2));
                if (k2 != null) k2.setMoved(true);
                Piece r2 = b.getPiece(new Position(r,3));
                if (r2 != null) r2.setMoved(true);
                return;
            }
        }

        // En-passant: pawn moves diagonally to empty square -> remove captured pawn
        Piece destBefore = b.getPiece(m.to);
        if (mover instanceof Pawn && destBefore == null && m.from.col != m.to.col) {
            int capturedRow = m.to.row + (mover.getColor() == PieceColor.WHITE ? 1 : -1);
            Position capturedPos = new Position(capturedRow, m.to.col);
            Piece movedCopy = mover.copy();
            movedCopy.setMoved(true);
            b.setPiece(m.to, movedCopy);
            b.setPiece(m.from, null);
            b.setPiece(capturedPos, null); // Remove en passant captured pawn
            return;
        }

        // Normal move/capture
        Piece movedCopy = mover.copy();
        movedCopy.setMoved(true);
        b.setPiece(m.to, movedCopy);
        b.setPiece(m.from, null);

        // Handle promotion
        if (m.isPromotion && mover instanceof Pawn && (m.to.row == 0 || m.to.row == 7)) {
            Piece promoted = createPromotedPiece(m.promotion, mover.getColor());
            promoted.setMoved(true);
            b.setPiece(m.to, promoted);
        }
    }

    // Create promoted piece based on promotion character
    private Piece createPromotedPiece(char promotion, PieceColor color) {
        switch (Character.toUpperCase(promotion)) {
            case 'Q': return new Queen(color);
            case 'R': return new Rook(color);
            case 'B': return new Bishop(color);
            case 'N': return new Knight(color);
            default:  return new Queen(color); // Default to queen
        }
    }

    /** Undo one ply. (Keeps moveHistory so redo() works.) */
    public void undo() {
        if (historyIndex == 0) return;
        historyIndex--;
        board = boardHistory.get(historyIndex).deepCopy();
        currentPlayer = currentPlayer.opposite();
        recomputeDerivedStateFromHistory();
    }

    /** Redo one ply. */
    public void redo() {
        if (historyIndex >= boardHistory.size() - 1) return;
        historyIndex++;
        board = boardHistory.get(historyIndex).deepCopy();
        currentPlayer = currentPlayer.opposite();
        recomputeDerivedStateFromHistory();
    }

    /** Rebuild en-passant target, halfmove clock and repetition map by replaying moves. */
    private void recomputeDerivedStateFromHistory() {
        Board temp = boardHistory.get(0).deepCopy();
        PieceColor side = PieceColor.WHITE;

        enPassantTarget = null;
        halfmoveClock = 0;

        positionCounts.clear();
        positionCounts.put(makePositionKey(temp, side, null), 1);

        for (int i = 0; i < historyIndex; i++) {
            Move mv = moveHistory.get(i);

            Piece mover = temp.getPiece(mv.from);
            if (mover == null) continue;

            Piece destBefore = temp.getPiece(mv.to);
            boolean epCaptureBefore = (mover instanceof Pawn) &&
                                      (mv.from.col != mv.to.col) &&
                                      (destBefore == null);

            // Set en-passant target for pawn double moves
            Position newEPTarget = null;
            if (mover instanceof Pawn && Math.abs(mv.from.row - mv.to.row) == 2) {
                newEPTarget = new Position((mv.from.row + mv.to.row) / 2, mv.to.col);
            }

            // apply on temp
            applyMoveToBoard(temp, mv);

            // 50-move clock
            if (mover instanceof Pawn || destBefore != null || epCaptureBefore) {
                halfmoveClock = 0;
            } else {
                halfmoveClock++;
            }

            enPassantTarget = newEPTarget;
            side = side.opposite();

            String key = makePositionKey(temp, side, enPassantTarget);
            positionCounts.put(key, positionCounts.getOrDefault(key, 0) + 1);
        }
    }

    // ---- Status helpers ----
    public boolean isInCheck(PieceColor color) {
        return board.isKingInCheck(color);
    }

    public boolean isInCheckmate() {
        return isInCheck(currentPlayer) && getAllLegalMoves(currentPlayer).isEmpty();
    }

    public boolean isInStalemate() {
        return !isInCheck(currentPlayer) && getAllLegalMoves(currentPlayer).isEmpty();
    }

    public boolean isDrawBy50MoveRule() {
        return halfmoveClock >= 100; // 50 full moves = 100 half-moves
    }

    public boolean isDrawByThreefold() {
        String currentKey = makePositionKey(board, currentPlayer, enPassantTarget);
        return positionCounts.getOrDefault(currentKey, 0) >= 3;
    }

    /**
     * Insufficient material: true only when neither side can deliver checkmate,
     * judged over the WHOLE board (not each side independently). Auto-draw cases:
     *   - K vs K
     *   - K + a single minor (knight or bishop) vs K
     *   - K + two knights vs K (cannot be forced — treated as a draw)
     *   - only bishops left, all on one colour (any count, either side)
     * Positions like KN vs KN, K+B vs K+N, and opposite-coloured KB vs KB are
     * NOT auto-drawn: a checkmate is still possible, so play continues.
     */
    public boolean isInsufficientMaterial() {
        int whiteMinors = 0, blackMinors = 0;
        int knights = 0, lightBishops = 0, darkBishops = 0;

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = board.getPiece(new Position(r, c));
                if (p == null || p instanceof King) continue;
                if (p instanceof Pawn || p instanceof Rook || p instanceof Queen)
                    return false;                       // a pawn/rook/queen can mate
                if (p.getColor() == PieceColor.WHITE) whiteMinors++; else blackMinors++;
                if (p instanceof Knight) knights++;
                else if ((r + c) % 2 == 0) lightBishops++; else darkBishops++;
            }
        }

        int bishops = lightBishops + darkBishops;
        int minors  = knights + bishops;

        if (minors <= 1) return true;                                   // KvK, KNvK, KBvK
        if (knights == 0 && (lightBishops == 0 || darkBishops == 0))    // bishops, one colour only
            return true;
        if (knights == 2 && bishops == 0 && (whiteMinors == 0 || blackMinors == 0))
            return true;                                                // two knights vs a bare king
        return false;                                                   // mate still possible
    }

    public boolean isGameOver() {
        return isInCheckmate() || isInStalemate() || isDrawBy50MoveRule() ||
               isDrawByThreefold() || isInsufficientMaterial();
    }

    public String getResultString() {
        if (isInCheckmate()) {
            PieceColor winner = currentPlayer.opposite();
            return (winner == PieceColor.WHITE ? "White" : "Black") + " wins by checkmate";
        } else if (isInStalemate()) {
            return "Draw by stalemate";
        } else if (isDrawBy50MoveRule()) {
            return "Draw by 50-move rule";
        } else if (isDrawByThreefold()) {
            return "Draw by threefold repetition";
        } else if (isInsufficientMaterial()) {
            return "Draw by insufficient material";
        }
        return "Game in progress";
    }

    /** "1-0", "0-1", "1/2-1/2" or "*" — for PGN export. */
    public String getPGNResult() {
        if (isInCheckmate()) {
            return currentPlayer == PieceColor.WHITE ? "0-1" : "1-0";
        }
        if (isInStalemate() || isDrawBy50MoveRule() || isDrawByThreefold()
                || isInsufficientMaterial()) {
            return "1/2-1/2";
        }
        return "*";
    }

    /**
     * Position key for repetition detection.
     * Includes: board state, side to move, castling rights, en passant target.
     */
    private String makePositionKey(Board b, PieceColor side, Position ep) {
        StringBuilder sb = new StringBuilder(512);

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = b.getPiece(new Position(r, c));
                if (p == null) {
                    sb.append('.');
                } else {
                    char symbol = p.getSymbol();
                    char pieceChar = (p.getColor() == PieceColor.WHITE) ?
                        Character.toUpperCase(symbol) : Character.toLowerCase(symbol);
                    sb.append(pieceChar);

                    // Include moved status for castling rights
                    if (p instanceof King || p instanceof Rook) {
                        sb.append(p.hasMoved() ? '1' : '0');
                    }
                }
            }
        }

        sb.append('|').append(side == PieceColor.WHITE ? 'w' : 'b');

        sb.append('|');
        if (ep == null) {
            sb.append('-');
        } else {
            sb.append(ep.toAlgebraic());
        }

        return sb.toString();
    }

    // Additional helper methods for debugging
    public int getHalfmoveClock() {
        return halfmoveClock;
    }

    public Map<String, Integer> getPositionCounts() {
        return new HashMap<>(positionCounts);
    }

    public Position getEnPassantTarget() {
        return enPassantTarget;
    }
}
