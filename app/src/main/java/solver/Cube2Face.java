package solver;

import static solver.Utils.turnSuffix;

/**
 * A solver for the "2-Face" stage of a Rubik's Cube.
 * This class uses pre-computed move tables and pruning tables to find optimal
 * solutions for placing four specific pieces using only U, R, and F moves.
 */
public class Cube2Face {

    //<editor-fold desc="Constants and Class Variables">
    // --- Constants ---
    private static final int NUM_STATES = 10626; // C(24, 4) ways to choose 4 pieces from 24 slots
    private static final int NUM_MOVES = 3; // The solver only uses U, R, and F moves
    private static final int MAX_SOLUTION_DEPTH = 7;
    private static final int PRUNING_TABLE_MAX_DEPTH = 5;
    private static final int NUM_PIECES_TO_TRACK = 4;
    private static final int NUM_SLOTS = 24; // 8 facelets * 3 faces = 24
    private static final int NUM_ORIENTATIONS = 6;
    private static final int UNVISITED_STATE = -1;
    private static final int SOLVED_STATE_DISTANCE = 0;
    private static final String MOVE_CHARS = "URF";
    private static final int INITIAL_LAST_MOVE = -1;
    

    // Move table for the 4-piece combination coordinate (C(24,4) = 10626 states).
    private static short[][] facemv = new short[10626][3];

    // Pruning table storing the minimum moves to the solved state.
    private static byte[] prun = new byte[10626];

    // Array to store the found solution sequence.
    private static int[] seq = new int[7];

    // Coordinates for the 6 possible solved states.
    private static int[] solved = {1819, 0, 4844, 69, 494, 10625};

    // String array for formatting the output.
    private static String[] color = {"D: ", "U: ", "L: ", "R: ", "F: ", "B: "};

    // Static initializer to generate all tables when the class is loaded.

    static {
        init();
    }

    //</editor-fold>

    //<editor-fold desc="Public Solver Methods">
    /**
     * Public wrapper method to solve the 2-Face problem for multiple orientations
     * specified by a bitmask.
     */
    public static String solveFace(String scramble, int face) {
        StringBuilder sb = new StringBuilder("\n");
        for (int i = 0; i < 6; i++) {
            if (((face >> i) & 1) != 0)
                sb.append(solve(scramble, i));
        }
        return sb.toString();
    }
    //</editor-fold>

    //<editor-fold desc="Private Core Logic">

    /**
     * The main solver for a single 2-Face problem. Applies the scramble and
     * starts the iterative deepening search.
     */
    private static String solve(String scramble, int face) {
        String[] s = scramble.split(" ");
        int fc = solved[face], d;
        for (d = 0; d < s.length; d++)
            if (s[d].length() != 0) {
                int m = "URF".indexOf(s[d].charAt(0));
                fc = facemv[fc][m];
                if (s[d].length() > 1) {
                    fc = facemv[fc][m];
                    if (s[d].charAt(1) == '\'')
                        fc = facemv[fc][m];
                }
            }
        for (d = 0; d < 7; d++)
            if (search(fc, d, -1)) {
                StringBuilder sb = new StringBuilder("\n");
                sb.append(color[face]);
                for (int i = d; i > 0; i--)
                    sb.append("URF".charAt(seq[i] / 3)).append(turnSuffix[seq[i] % 3]).append(" ");
                return sb.toString();
            }
        return "error";
    }

    /**
     * The recursive IDA* search function to find a solution for a given state.
     */
    private static boolean search(int face, int d, int lm) {
        if (d == 0) return prun[face] == 0;
        if (prun[face] > d) return false;
        for (int i = 0; i < 3; i++)
            if (i != lm) {
                int x = face;
                for (int j = 0; j < 3; j++) {
                    x = facemv[x][i];
                    if (search(x, d - 1, i)) {
                        seq[d] = i * 3 + j;
                        return true;
                    }
                }
            }
        return false;
    }
    //</editor-fold>

    //<editor-fold desc="Table Generation">
    /**
     * Initializes all pre-computed lookup tables (move and pruning tables).
     * This heavy computation is run only once.
     */
    private static void init() {
        int[] arr = new int[24];
        for (int i = 0; i < 10626; i++) {
            prun[i] = -1;
            for (int j = 0; j < 3; j++) {
                Utils.idxToComb(arr, i, 4, 24);
                switch (j) {
                    case 0: //U
                        Utils.circle(arr, 0,  2,  3,  1);
                        Utils.circle(arr, 4, 20, 16,  8);
                        Utils.circle(arr, 5, 21, 17,  9);
                        break;
                    case 1: //R
                        Utils.circle(arr, 4,  6,  7,  5);
                        Utils.circle(arr, 1,  9, 13, 22);
                        Utils.circle(arr, 3, 11, 15, 20);
                        break;
                    case 2: //F
                        Utils.circle(arr, 8, 10, 11,  9);
                        Utils.circle(arr, 2, 19, 13,  4);
                        Utils.circle(arr, 3, 17, 12,  6);
                        break;
                }
                facemv[i][j] = (short) Utils.combToIdx(arr, 4, 24);
            }
        }
        for (int i = 0; i < 6; i++) prun[solved[i]] = 0;
        Utils.populatePruningTable(prun, 5, facemv, 3);
    }
    //</editor-fold>


}
