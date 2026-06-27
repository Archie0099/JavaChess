import java.util.*;

public class King extends Piece {
    public King(PieceColor color) { super(color); }
    @Override public char getSymbol() { return 'k'; }

    @Override
    public List<Move> getLegalMoves(Position from, Board board) {
        List<Move> res = new ArrayList<>();
        int[][] offs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        for (int[] o : offs) {
            int r = from.row + o[0], c = from.col + o[1];
            if (inBounds(r,c)) {
                Position to = new Position(r,c);
                Piece t = board.getPiece(to);
                if (t == null || t.getColor() != color) res.add(new Move(from,to,this,t));
            }
        }

        // Castling: only if king hasn't moved and not in check
        if (!this.hasMoved()) {
            int r = from.row;
            PieceColor enemy = this.color.opposite();
            // King-side castling: squares f (col=5) and g (col=6) must be empty, rook at h (7) must be unmoved.
            Piece rookK = board.getPiece(new Position(r,7));
            if (rookK instanceof Rook && !rookK.hasMoved()
                    && board.getPiece(new Position(r,5)) == null
                    && board.getPiece(new Position(r,6)) == null) {
                // King must not be currently in check, and squares king passes through must not be attacked.
                if (!board.isSquareAttacked(from, enemy)
                        && !board.isSquareAttacked(new Position(r,5), enemy)
                        && !board.isSquareAttacked(new Position(r,6), enemy)) {
                    Move m = new Move(from, new Position(r,6), this, null);
                    m.isCastling = true;
                    res.add(m);
                }
            }

            // Queen-side castling: squares b (1), c (2), d (3) must be empty between rook (0) and king (4),
            // and rook at a (0) must be unmoved. King passes through d (3) and c (2).
            Piece rookQ = board.getPiece(new Position(r,0));
            if (rookQ instanceof Rook && !rookQ.hasMoved()
                    && board.getPiece(new Position(r,1)) == null
                    && board.getPiece(new Position(r,2)) == null
                    && board.getPiece(new Position(r,3)) == null) {
                if (!board.isSquareAttacked(from, enemy)
                        && !board.isSquareAttacked(new Position(r,3), enemy)
                        && !board.isSquareAttacked(new Position(r,2), enemy)) {
                    Move m = new Move(from, new Position(r,2), this, null);
                    m.isCastling = true;
                    res.add(m);
                }
            }
        }

        return res;
    }

    @Override public Piece copy() { King p = new King(color); p.moved = this.moved; return p; }
}
