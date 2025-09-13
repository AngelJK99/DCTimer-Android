package solver;

import android.util.Log;

import java.util.Random;

import static solver.Utils.permutationSign;
import static solver.Utils.turnSuffix;
import static solver.Utils.turnSuffixInverse;

/**
 * A solver for the 2-Generator Group <U, R> on a 3x3x3 cube.
 * <p>
 * This class is designed to find solutions to a cube using only Up and Right
 * face moves. It uses pre-computed move tables and pruning tables for three
 * coordinate systems: 6-corner permutation, 6-corner orientation, and 7-edge permutation.
 * Its primary use is to generate random-state scrambles within this move set.
 */
public class CubeRU {

    //<editor-fold desc="Constants & Class Variables">
    // --- Constants ---
    private static final int NUM_TRACKED_EDGES = 7;
    private static final int NUM_TRACKED_CORNERS = 6;
    private static final int NUM_CORNER_PERM_STATES = 720;    // 6!
    private static final int NUM_CORNER_ORIENT_STATES = 243;  // 3^5
    private static final int NUM_EDGE_PERM_STATES = 5040;   // 7!
    private static final int NUM_SOLVER_MOVES = 2;          // U, R
    private static final int MAX_SOLUTION_DEPTH = 21;
    private static final int NUM_TURN_TYPES = 3;
    private static final int INITIAL_LAST_MOVE = -1;
    private static final int UNVISITED_STATE = -1;
    private static final int SOLVED_STATE_COORD = 0;


    // --- Lookup Tables ---
    // Move tables for corner permutation, corner orientation, and edge permutation.
    private static short[][] moveTableCornerPerm = new short[NUM_CORNER_PERM_STATES][NUM_SOLVER_MOVES];
    private static short[][] moveTableCornerOrient = new short[NUM_CORNER_ORIENT_STATES][NUM_SOLVER_MOVES];
    private static short[][] moveTableEdgePerm = new short[NUM_EDGE_PERM_STATES][NUM_SOLVER_MOVES];

    // Pruning tables for combined corner state and edge permutation state.
    private static byte[] pruningTableCorner = new byte[NUM_CORNER_PERM_STATES * NUM_CORNER_ORIENT_STATES];
    private static byte[] pruningTableEdge = new byte[NUM_EDGE_PERM_STATES];

    // --- Solver Variables ---
    // Array to store the found solution sequence.
    private static int[] solutionSequence = new int[MAX_SOLUTION_DEPTH];

    // String arrays for formatting the output.
    private static String[] MOVE_CHARS_RU = {"U", "R"};
    private static String[] MOVE_CHARS_LU = {"U", "L"};
    //</editor-fold>

    //<editor-fold desc="Initialization">

    /* Static initializer to generate all tables when the class is loaded. */
    static {
        initializeTables();
    }

    /**
     * Initializes and pre-computes all lookup tables for the <U, R> solver.
     * <p>
     * This heavy computation is run only once. It generates the move tables for
     * the three coordinate systems (6-corner perm, 6-corner orient, 7-edge perm)
     * and then uses them to generate the corresponding pruning tables.
     */
    private static void initializeTables() {
        // A temporary array to hold piece states during calculation.
        long time = System.currentTimeMillis();
        int[] pieceState = new int[NUM_TRACKED_EDGES]; // Max size needed is 7
        // =================================================================================
        // Part 1: Build Corner Permutation Move Table (6 corners)
        // =================================================================================
        for (int stateIndex = 0; stateIndex < NUM_CORNER_PERM_STATES; stateIndex++) {
            for (int moveIndex = 0; moveIndex < NUM_SOLVER_MOVES; moveIndex++) {
                Utils.idxToPerm(pieceState, stateIndex, NUM_TRACKED_CORNERS, false);
                if (moveIndex == 0) {  // U-move
                    Utils.circle(pieceState, 0, 3, 2, 1);
                }
                else {  // R-move
                    Utils.circle(pieceState, 1, 2, 4, 5);
                }
                moveTableCornerPerm[stateIndex][moveIndex] = (short) Utils.permToIdx(pieceState, NUM_TRACKED_CORNERS, false);
            }
        }

        // =================================================================================
        // Part 2: Build Corner Orientation Move Table (6 corners)
        // =================================================================================
        for (int stateIndex = 0; stateIndex < NUM_CORNER_ORIENT_STATES; stateIndex++) {
            for (int moveIndex = 0; moveIndex < NUM_SOLVER_MOVES; moveIndex++) {
                Utils.idxToOri(pieceState, stateIndex, NUM_TRACKED_CORNERS, true);
                if (moveIndex == 0) {  // U-move (no orientation change)
                    Utils.circle(pieceState, 0, 3, 2, 1);
                }
                else { // R-move (with orientation change)
                    Utils.circle(pieceState, 1, 2, 4, 5, new int[] {1, 2, 1, 2});
                }
                moveTableCornerOrient[stateIndex][moveIndex] = (short) Utils.oriToIdx(pieceState, NUM_TRACKED_CORNERS, true);
            }
        }

        // =================================================================================
        // Part 3: Build Edge Permutation Move Table (7 edges)
        // =================================================================================

        for (int stateIndex = 0; stateIndex < NUM_EDGE_PERM_STATES; stateIndex++) {
            for (int moveIndex = 0; moveIndex < NUM_SOLVER_MOVES; moveIndex++) {
                Utils.idxToPerm(pieceState, stateIndex, NUM_TRACKED_EDGES, false);
                if (moveIndex == 0) { // U-move
                    Utils.circle(pieceState, 0, 3, 2, 1);
                }  else { // R-move
                    Utils.circle(pieceState, 1, 6, 5, 4);
                }
                moveTableEdgePerm[stateIndex][moveIndex] = (short) Utils.permToIdx(pieceState, NUM_TRACKED_EDGES, false);
            }
        }

        // =================================================================================
        // Part 4: Build Combined Corner Pruning Table
        // =================================================================================
        final int CORNER_PRUNING_DEPTH = 14;

        for (int i = 1; i < NUM_CORNER_PERM_STATES * NUM_CORNER_ORIENT_STATES; i++) {
            pruningTableCorner[i] = UNVISITED_STATE ; // -1 means unvisited
        }
        pruningTableCorner[SOLVED_STATE_COORD] = 0;
        Utils.populatePruningTable(pruningTableCorner, CORNER_PRUNING_DEPTH, moveTableCornerPerm, moveTableCornerOrient, NUM_TURN_TYPES);


        // =================================================================================
        // Part 5: Build Edge Permutation Pruning Table
        // =================================================================================
        final int EDGE_PRUNING_DEPTH = 11;

        for (int i = 1; i < NUM_EDGE_PERM_STATES; i++) {
            pruningTableEdge[i] = UNVISITED_STATE;
        }
        pruningTableEdge[SOLVED_STATE_COORD] = 0;
        Utils.populatePruningTable(pruningTableEdge, EDGE_PRUNING_DEPTH, moveTableEdgePerm, NUM_TURN_TYPES);

        time = System.currentTimeMillis() - time;
        Log.w("dct", "init "+time+"ms");
    }

    //</editor-fold>

    //<editor-fold desc="Public Scramble Generator">
    /**
     * Generates a random-state scramble using only U and R (or U and L) moves.
     * <p>
     * This method creates a valid scramble by:
     * <ol>
     * <li>Picking random, reachable coordinates for corners and edges.</li>
     * <li>Ensuring the combination of coordinates has a valid parity.</li>
     * <li>Solving the cube from that state to produce the scramble.</li>
     * <li>Optionally formatting the output with L moves instead of R moves.</li>
     * </ol>
     *
     * @param useLUMoves If true, the output scramble will use L moves instead of R moves.
     * @return A string representing the scramble.
     */

    public static String scramble(boolean useLUMoves) {
        // --- Define Constants ---
        final int MAX_SEARCH_DEPTH = 21;
        final int MIN_SOLUTION_LENGTH = 4; // Ignore solutions shorter than this.

        // --- 1. Generate a Valid, Reachable Random State ---
        int cornerPermCoord, cornerOrientCoord, edgePermCoord;

        // Temporary arrays for parity checking.
        int[] cornerPermArray = new int[NUM_TRACKED_CORNERS];
        int[] edgePermArray = new int[NUM_TRACKED_EDGES];
        Random randomGenerator = new Random();

        // --- 1. Generate a Valid, Reachable Random State ---
        do {
            // Repeatedly pick random corner coordinates until a reachable state is found.
            do {
                cornerPermCoord = randomGenerator.nextInt(NUM_CORNER_PERM_STATES);
                cornerOrientCoord = randomGenerator.nextInt(NUM_CORNER_ORIENT_STATES);
            }
            while (pruningTableCorner[cornerPermCoord * NUM_CORNER_ORIENT_STATES + cornerOrientCoord] < 0); // Check if state is reachable

            // Pick a random edge coordinate.
            edgePermCoord = randomGenerator.nextInt(NUM_EDGE_PERM_STATES);

            // Unpack the coordinates to check if their parities match.
            Utils.idxToPerm(cornerPermArray, cornerPermCoord, NUM_TRACKED_CORNERS, false);
            Utils.idxToPerm(edgePermArray, edgePermCoord, NUM_TRACKED_EDGES, false);

        } while (permutationSign(cornerPermArray) != permutationSign(edgePermArray)); // Parity must match for a state to be solvable.

        // --- 2. Iterative Deepening Search ---
        // Find the shortest solution from the valid random state.
        for (int searchDepth = 0; searchDepth < 21; searchDepth++) {
            if (search(cornerPermCoord, cornerOrientCoord, edgePermCoord, searchDepth, INITIAL_LAST_MOVE)) {

                // --- 3. Validate and Format Solution ---
                // If the solution is too short, recursively call to get a new, more complex scramble.
                if (searchDepth < 2) return scramble(useLUMoves);

                // If the solution is valid but still short, continue searching for a longer one.
                if (searchDepth < MIN_SOLUTION_LENGTH) {
                    continue;
                }

                // If a suitable solution is found, format it into a scramble string.
                StringBuilder scrambleBuilder = new StringBuilder();
                for (int i = 1; i <= searchDepth; i++) {
                    int moveCode = solutionSequence[i];
                    int faceIndex = moveCode / 3;
                    int turnType = moveCode % 3;

                    if (useLUMoves) {
                        scrambleBuilder
                                .append(MOVE_CHARS_LU[faceIndex])
                                .append(turnSuffix[turnType])
                                .append(" ");
                    }
                    else {
                        scrambleBuilder
                                .append(MOVE_CHARS_RU[faceIndex])
                                .append(turnSuffixInverse[turnType])
                                .append(" ");
                    }
                }
                return scrambleBuilder.toString();
            }
        }
        // Should not be reached if tables are correct.
        return "error";
    }

    //</editor-fold>

    //<editor-fold desc="Internal Solver Logic">
    /**
     * The recursive IDA* search function for the <U, R> solver.
     * <p>
     * This method performs a depth-first search, using two separate pruning tables
     * (one for the combined corner state, one for the edge state) to efficiently
     * find a solution path.
     *
     * @param cornerPerm The current corner permutation coordinate.
     * @param cornerOrient The current corner orientation coordinate.
     * @param edgePerm The current edge permutation coordinate.
     * @param depthRemaining The number of moves left in the current search path.
     * @param lastMove The index of the last move made, to avoid redundant sequences.
     * @return True if a solution is found, false otherwise.
     */

    private static boolean search(int cornerPerm, int cornerOrient, int edgePerm,
                                  int depthRemaining, int lastMove) {
        // --- Base Case: If we have no moves left, check if all coordinates are in the solved state (0). ---
        if (depthRemaining == 0) {
            return cornerPerm == SOLVED_STATE_COORD &&
                    cornerOrient == SOLVED_STATE_COORD &&
                    edgePerm == SOLVED_STATE_COORD;
        }

        // --- Heuristic Pruning ---
        // Check both corner and edge pruning tables. If either subproblem requires
        // more moves than we have left, this entire path is a dead end.
        if (pruningTableCorner[cornerPerm * NUM_CORNER_ORIENT_STATES  + cornerOrient] > depthRemaining ||
                pruningTableEdge[edgePerm] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore all valid next moves. ---
        for (int moveIndex = 0; moveIndex < NUM_SOLVER_MOVES; moveIndex++) {
            if (moveIndex != lastMove) {

                // Start with the current coordinates for this move sequence.
                int nextCornerPerm = cornerPerm;
                int nextCornerOrient = cornerOrient;
                int nextEdgePerm = edgePerm;


                // Try all 3 turn types for the current face (e.g., U, U2, U').
                for (int j = 0; j < 3; j++) {
                    nextCornerPerm = moveTableCornerPerm[nextCornerPerm][moveIndex];
                    nextCornerOrient = moveTableCornerOrient[nextCornerOrient][moveIndex];
                    nextEdgePerm = moveTableEdgePerm[nextEdgePerm][moveIndex];

                    // Make the recursive call for the new state with one less depth.
                    if (search(nextCornerPerm, nextCornerOrient, nextEdgePerm, depthRemaining - 1, moveIndex)) {
                        // --- Solution Found! ---
                        // Record the successful move in the solution sequence array.
                        solutionSequence[depthRemaining] = moveIndex * NUM_TURN_TYPES  + j;
                        //sb.insert(0, turn[i] + suff[j]+" ");
                        return true;
                    }
                }
            }
        }

        // If all moves have been explored from this state without success, backtrack.
        return false;
    }



    //</editor-fold>

}
