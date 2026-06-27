import java.util.*;

public class Queen extends Piece {
    public Queen(PieceColor color) { super(color); }

    @Override public char getSymbol() { return 'q'; }

    @Override
    public List<Move> getLegalMoves(Position from, Board board) {
        List<Move> res = new ArrayList<>();
        // Delegate square-finding to Rook and Bishop helpers, but stamp 'this'
        // (the actual Queen) as movedPiece so notation and AI logic are correct.
        for (Move m : new Rook(color).getLegalMoves(from, board))
            res.add(new Move(from, m.to, this, m.capturedPiece));
        for (Move m : new Bishop(color).getLegalMoves(from, board))
            res.add(new Move(from, m.to, this, m.capturedPiece));
        return res;
    }

    @Override
    public Piece copy() { Queen p = new Queen(color); p.moved = this.moved; return p; }
}
