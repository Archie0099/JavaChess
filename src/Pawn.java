import java.util.*;

public class Pawn extends Piece {
    public Pawn(PieceColor color) { super(color); }
    @Override public char getSymbol() { return 'p'; }

    @Override
    public List<Move> getLegalMoves(Position from, Board board) {
        List<Move> res = new ArrayList<>();
        int dir = (color == PieceColor.WHITE) ? -1 : 1;
        int start = (color == PieceColor.WHITE) ? 6 : 1;
        Position one = new Position(from.row + dir, from.col);
        // single step
        if (inBounds(one.row, one.col) && board.getPiece(one) == null) {
            if (one.row == 0 || one.row == 7) {
                // promotions: add all four promotion types
                char[] promos = {'Q','R','B','N'};
                for (char pr : promos) {
                    Move m = new Move(from, one, this, null);
                    m.isPromotion = true;
                    m.promotion = pr;
                    res.add(m);
                }
            } else {
                res.add(new Move(from, one, this, null));
            }

            // two-step from starting square
            Position two = new Position(from.row + 2*dir, from.col);
            if (from.row == start && inBounds(two.row, two.col) && board.getPiece(two) == null) {
                res.add(new Move(from, two, this, null));
            }
        }
        // captures
        for (int dc = -1; dc <= 1; dc += 2) {
            Position to = new Position(from.row + dir, from.col + dc);
            if (inBounds(to.row, to.col)) {
                Piece t = board.getPiece(to);
                if (t != null && t.getColor() != color) {
                    if (to.row == 0 || to.row == 7) {
                        char[] promos = {'Q','R','B','N'};
                        for (char pr : promos) {
                            Move m = new Move(from, to, this, t);
                            m.isPromotion = true;
                            m.promotion = pr;
                            res.add(m);
                        }
                    } else {
                        res.add(new Move(from, to, this, t));
                    }
                }
            }
        }
        return res;
    }

    @Override public Piece copy() { Pawn p = new Pawn(color); p.moved = this.moved; return p; }
}
