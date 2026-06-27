import java.util.List;

public abstract class Piece {
    protected PieceColor color;
    protected boolean moved = false;

    public Piece(PieceColor color) {
        this.color = color;
    }

    public PieceColor getColor() {
        return color;
    }

    public boolean hasMoved() {
        return moved;
    }

    public void setMoved(boolean moved) {
        this.moved = moved;
    }

    // Helper method for bounds checking
    protected boolean inBounds(int row, int col) {
        return row >= 0 && row < 8 && col >= 0 && col < 8;
    }

    // Abstract methods that each piece must implement
    public abstract char getSymbol();
    public abstract List<Move> getLegalMoves(Position from, Board board);
    public abstract Piece copy();

    @Override
    public String toString() {
        return color + " " + getClass().getSimpleName();
    }
}