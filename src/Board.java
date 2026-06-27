// Board.java
public class Board {
    private Piece[][] board;

    public Board() {
        board = new Piece[8][8];
        setupStartingPosition();
    }

    private void setupStartingPosition() {
        // black back rank
        board[0][0] = new Rook(PieceColor.BLACK);
        board[0][1] = new Knight(PieceColor.BLACK);
        board[0][2] = new Bishop(PieceColor.BLACK);
        board[0][3] = new Queen(PieceColor.BLACK);
        board[0][4] = new King(PieceColor.BLACK);
        board[0][5] = new Bishop(PieceColor.BLACK);
        board[0][6] = new Knight(PieceColor.BLACK);
        board[0][7] = new Rook(PieceColor.BLACK);
        for (int c = 0; c < 8; c++) board[1][c] = new Pawn(PieceColor.BLACK);

        // empty rows
        for (int r = 2; r <= 5; r++) for (int c = 0; c < 8; c++) board[r][c] = null;

        // white pawns & back rank
        for (int c = 0; c < 8; c++) board[6][c] = new Pawn(PieceColor.WHITE);
        board[7][0] = new Rook(PieceColor.WHITE);
        board[7][1] = new Knight(PieceColor.WHITE);
        board[7][2] = new Bishop(PieceColor.WHITE);
        board[7][3] = new Queen(PieceColor.WHITE);
        board[7][4] = new King(PieceColor.WHITE);
        board[7][5] = new Bishop(PieceColor.WHITE);
        board[7][6] = new Knight(PieceColor.WHITE);
        board[7][7] = new Rook(PieceColor.WHITE);
    }

    public Piece getPiece(Position p) {
        return board[p.row][p.col];
    }

    public void setPiece(Position p, Piece piece) {
        board[p.row][p.col] = piece;
    }

    public Board deepCopy() {
        Board b = new Board();
        b.board = new Piece[8][8];
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = this.board[r][c];
                b.board[r][c] = (p == null) ? null : p.copy();
            }
        }
        return b;
    }

    /**
     * Find the king for the given color. Returns null if absent.
     */
    public Position findKing(PieceColor color) {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = board[r][c];
                if (p != null && p.getColor() == color && p instanceof King) {
                    return new Position(r, c);
                }
            }
        }
        return null;
    }

    /**
     * True if the king of 'color' is in check (attacked by opponent).
     */
    public boolean isKingInCheck(PieceColor color) {
        Position kingPos = findKing(color);
        if (kingPos == null) return true; // missing king - treat as in check
        return isSquareAttacked(kingPos, color.opposite());
    }

    /**
     * Return true if any piece of 'attacker' attacks the square 'sq'.
     *
     * IMPORTANT: this function does NOT call piece.getLegalMoves() to avoid recursion.
     * Instead it checks attacks directly:
     *  - pawn diagonals,
     *  - knight jumps,
     *  - sliding attacks (rook/bishop/queen),
     *  - adjacent king.
     */
    public boolean isSquareAttacked(Position sq, PieceColor attacker) {
        int r = sq.row;
        int c = sq.col;

        // 1) Pawn attacks: pawns attack differently depending on color
        int pawnRow = (attacker == PieceColor.WHITE) ? r + 1 : r - 1;
        if (inBounds(pawnRow, c - 1)) {
            Piece p = board[pawnRow][c - 1];
            if (p instanceof Pawn && p.getColor() == attacker) return true;
        }
        if (inBounds(pawnRow, c + 1)) {
            Piece p = board[pawnRow][c + 1];
            if (p instanceof Pawn && p.getColor() == attacker) return true;
        }

        // 2) Knight attacks
        int[][] knightOffsets = {{2,1},{2,-1},{-2,1},{-2,-1},{1,2},{1,-2},{-1,2},{-1,-2}};
        for (int[] o : knightOffsets) {
            int rr = r + o[0], cc = c + o[1];
            if (!inBounds(rr, cc)) continue;
            Piece p = board[rr][cc];
            if (p instanceof Knight && p.getColor() == attacker) return true;
        }

        // 3) King adjacency (opponent king)
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) continue;
                int rr = r + dr, cc = c + dc;
                if (!inBounds(rr, cc)) continue;
                Piece p = board[rr][cc];
                if (p instanceof King && p.getColor() == attacker) return true;
            }
        }

        // 4) Sliding pieces: rook/queen (orthogonal), bishop/queen (diagonal)

        // orthogonal directions (rook / queen)
        int[][] rookDirs = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] d : rookDirs) {
            int rr = r + d[0], cc = c + d[1];
            while (inBounds(rr, cc)) {
                Piece p = board[rr][cc];
                if (p != null) {
                    if (p.getColor() == attacker && (p instanceof Rook || p instanceof Queen)) return true;
                    break; // blocked
                }
                rr += d[0]; cc += d[1];
            }
        }

        // diagonal directions (bishop / queen)
        int[][] bishopDirs = {{1,1},{1,-1},{-1,1},{-1,-1}};
        for (int[] d : bishopDirs) {
            int rr = r + d[0], cc = c + d[1];
            while (inBounds(rr, cc)) {
                Piece p = board[rr][cc];
                if (p != null) {
                    if (p.getColor() == attacker && (p instanceof Bishop || p instanceof Queen)) return true;
                    break;
                }
                rr += d[0]; cc += d[1];
            }
        }

        return false;
    }

    private boolean inBounds(int rr, int cc) {
        return rr >= 0 && rr < 8 && cc >= 0 && cc < 8;
    }
}
