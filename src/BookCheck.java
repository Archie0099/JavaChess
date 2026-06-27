public class BookCheck {
    public static void main(String[] a) {
        int bad = ImprovedAI.validateBuiltinLines();
        System.out.println(bad == 0 ? "All built-in opening lines replay fully - book is clean."
                                    : bad + " BROKEN LINES");
        if (bad != 0) System.exit(1);   // fail the build, like the other harnesses
    }
}
