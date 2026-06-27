public class Move {
    public Position from, to;
    public Piece movedPiece, capturedPiece;
    public boolean isPromotion = false;
    public char promotion = '\0';
    public boolean isCastling = false;
    public boolean isEnPassant = false; // New flag for en passant

    public Move(Position from, Position to, Piece moved, Piece captured) {
        this.from = from;
        this.to = to;
        this.movedPiece = moved;
        this.capturedPiece = captured;
    }

    public Move(Position from, Position to) {
        this(from, to, null, null);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Move)) return false;
        Move m = (Move) o;
        return from.equals(m.from) && 
               to.equals(m.to) && 
               this.isPromotion == m.isPromotion && 
               this.promotion == m.promotion &&
               this.isCastling == m.isCastling &&
               this.isEnPassant == m.isEnPassant;
    }

    @Override
    public int hashCode() {
        return from.hashCode() * 31 + to.hashCode() + 
               (isPromotion ? 1000 : 0) + 
               (int)promotion * 100 +
               (isCastling ? 10000 : 0) +
               (isEnPassant ? 100000 : 0);
    }

    public String toAlgebraic() {
        if (isCastling) {
            if (to.col == 6) return "O-O";      // King-side
            if (to.col == 2) return "O-O-O";   // Queen-side
        }

        StringBuilder sb = new StringBuilder();

        // Piece symbol (except for pawns)
        if (movedPiece != null && !(movedPiece instanceof Pawn)) {
            sb.append(Character.toUpperCase(movedPiece.getSymbol()));
        }

        // Capture notation
        if (capturedPiece != null || isEnPassant) {
            if (movedPiece instanceof Pawn) {
                // For pawn captures, include the file
                sb.append((char)('a' + from.col));
            }
            sb.append('x');
        }

        // Destination square
        sb.append(to.toAlgebraic());

        // Promotion
        if (isPromotion) {
            sb.append('=').append(Character.toUpperCase(promotion));
        }

        // En passant notation (optional)
        if (isEnPassant) {
            sb.append(" e.p.");
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return toAlgebraic();
    }
}