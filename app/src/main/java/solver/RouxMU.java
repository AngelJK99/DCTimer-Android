package solver;

import java.util.Random;

import static solver.PetrusSolver.moveIndex;
import static solver.Utils.turnSuffixInverse;
/**
 * A solver for the LSE (Last Six Edges) stage of the Roux method, using only M and U moves.
 * <p>
 * This class uses several coordinate systems to track the state of the 6 M-slice edges
 * and the 4 U-layer corners. It pre-computes lookup tables to find optimal solutions
 * for this specific sub-problem of a 3x3x3 solve.
 */
public class RouxMU {

    //<editor-fold desc="Constants & Class Variables">
    // --- Constants ---
    private static final int NUM_EDGE_PERM_STATES = 720;     // Permutation of 6 edges: 6!
    private static final int NUM_EDGE_ORIENT_STATES = 32;    // Orientation of 6 edges (1 dependent): 2^5
    private static final int NUM_CORNER_PERM_STATES = 4;     // Permutation of the 4 U-layer corners
    private static final int NUM_CENTER_PERM_STATES = 4;     // Permutation of the 4 M-slice centers
    private static final int NUM_CORNER_CENTER_STATES = 16;  // Combined state of corners and centers: 4 * 4
    private static final int NUM_MOVES = 2;                  // M, U
    private static final int MAX_SOLUTION_DEPTH = 21;
    private static final int UNVISITED_STATE = -1;
    private static final int INITIAL_LAST_MOVE = -1;
    private static final int SOLVED_STATE_COORD = 0;
    private static final int SOLVED_STATE_DISTANCE = 0;
    private static final int NUM_TRACKED_EDGES = 6;
    private static final int NUM_TURN_TYPES = 3;


    // --- Lookup Tables ---
    // Move tables for edge permutation and orientation
    private static short[][] moveTableEdgePerm = new short[NUM_EDGE_PERM_STATES][NUM_MOVES];
    private static short[][] moveTableEdgeOrient = new short[NUM_EDGE_ORIENT_STATES][NUM_MOVES];

    // Move tables for the permutation of U-layer corners and M-slice centers.
    private static short[][] moveTableCenterPerm = {{1, 0}, {2, 1}, {3, 2}, {0, 3}};
    private static short[][] moveTableCornerPerm_ULUR = {{0, 1}, {1, 2}, {2, 3}, {3, 0}};

    // Pruning tables for different coordinate combinations.
    private static byte[] pruningTableEdge = new byte[NUM_EDGE_PERM_STATES  * NUM_EDGE_ORIENT_STATES];
    private static byte[] pruningTableEdgeAndCenterCorner = new byte[NUM_EDGE_ORIENT_STATES * NUM_CORNER_CENTER_STATES];

    // --- Solver Variables ---
    private static String[] MOVE_CHARS = {"M", "U"};
    private static int[] solutionSequence = new int[MAX_SOLUTION_DEPTH];

    //</editor-fold>

    //<editor-fold desc="Initialization">
    //Static initializer to generate all tables when the class is loaded. */
    static {
        initializeTables();
    }

    /** Pre-computes all move tables and pruning tables for the LSE solver. */
    private static void initializeTables()  {
        // A temporary array to hold piece states during calculation.
        int[] temp = new int[NUM_TRACKED_EDGES];

        // =================================================================================
        // Part 1: Build Edge Permutation Move Table (epm)
        // =================================================================================
        for (int stateIndex = 0; stateIndex < NUM_EDGE_PERM_STATES; stateIndex++) {
            for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                // Unpack coordinate -> Apply M or U move -> Repack coordinate
                Utils.idxToPerm(temp, stateIndex, NUM_TRACKED_EDGES, false);
                switch (moveIndex) {
                    case 0: Utils.circle(temp, 0, 4, 5, 2); break; // M-move
                    case 1: Utils.circle(temp, 0, 3, 2, 1); break; // U-move
                }
                moveTableEdgePerm[stateIndex][moveIndex] = (short) Utils.permToIdx(temp, NUM_TRACKED_EDGES, false);
            }
        }

        // =================================================================================
        // Part 2: Build Edge Orientation Move Table (eom)
        // =================================================================================
        for (int stateIndex = 0; stateIndex < NUM_EDGE_ORIENT_STATES; stateIndex++) {
            for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                // Unpack coordinate -> Apply M or U move (M flips edges) -> Repack coordinate
                Utils.idxToFlip(temp, stateIndex, NUM_TRACKED_EDGES, true);
                switch (moveIndex) {
                    case 0: // M-move
                        // M-moves flip the orientation of the 4 edges being moved.
                        Utils.circle(temp, 0, 4, 5, 2);
                        temp[0] = 1 - temp[0];
                        temp[2] = 1 - temp[2];
                        temp[4] = 1 - temp[4];
                        temp[5] = 1 - temp[5];
                        break;
                    case 1:  // U-move (does not flip edges)
                        Utils.circle(temp, 0, 3, 2, 1);
                        break;
                }
                moveTableEdgeOrient[stateIndex][moveIndex] = (short) Utils.flipToIdx(temp, NUM_TRACKED_EDGES, true);
            }
        }

        // =================================================================================
        // Part 3: Build Combined Edge Pruning Table (ed)
        // =================================================================================
        final int TOTAL_EDGE_STATES = NUM_EDGE_PERM_STATES * NUM_EDGE_ORIENT_STATES;
        final int EDGE_PRUNING_DEPTH = 14;
        for (int stateIndex = 0; stateIndex < TOTAL_EDGE_STATES; stateIndex++) {
            pruningTableEdge[stateIndex] = UNVISITED_STATE;
        }
        pruningTableEdge[SOLVED_STATE_COORD] = SOLVED_STATE_DISTANCE;
        Utils.populatePruningTable(pruningTableEdge, EDGE_PRUNING_DEPTH, moveTableEdgePerm,
                moveTableEdgeOrient, NUM_TURN_TYPES);

        // =================================================================================
        // Part 4: Build Edge/Corner/Center Pruning Table (eod)
        // =================================================================================
        final int TOTAL_EOD_STATES = NUM_EDGE_ORIENT_STATES * NUM_CORNER_CENTER_STATES;
        final int EOD_PRUNING_DEPTH = 12;

        for (int stateIndex = 0; stateIndex < TOTAL_EOD_STATES; stateIndex++)  {
            pruningTableEdgeAndCenterCorner[stateIndex] = UNVISITED_STATE;
        }
        pruningTableEdgeAndCenterCorner[SOLVED_STATE_COORD] = SOLVED_STATE_DISTANCE;
        int c = 1;
        // Populate the table using a custom Breadth-First Search (BFS).
        for (int currentDepth = 0; currentDepth < EOD_PRUNING_DEPTH; currentDepth++) {
            //c = 0;
            for (int edgeOrientCoord  = 0; edgeOrientCoord  < NUM_EDGE_ORIENT_STATES; edgeOrientCoord ++) {
                for (int cornerCenterCoord = 0; cornerCenterCoord < NUM_CORNER_CENTER_STATES; cornerCenterCoord++) {
                    if (pruningTableEdgeAndCenterCorner[edgeOrientCoord * NUM_CORNER_CENTER_STATES + cornerCenterCoord] == currentDepth) {
                        for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                            int nextEdgeOrientCoord = edgeOrientCoord;
                            int nextCornerCenterCoord = cornerCenterCoord;
                            for (int turnType = 0; turnType < NUM_TURN_TYPES; turnType++) {
                                // Calculate the next state for the combined coordinate.
                                nextEdgeOrientCoord = moveTableEdgeOrient[nextEdgeOrientCoord][moveIndex];
                                int centerPerm = nextCornerCenterCoord / NUM_CORNER_PERM_STATES;
                                int cornerPerm = nextCornerCenterCoord % NUM_CORNER_PERM_STATES;
                                int nextCenterPerm = moveTableCenterPerm[centerPerm][moveIndex];
                                int nextCornerPerm = moveTableCornerPerm_ULUR[cornerPerm][moveIndex];
                                nextCornerCenterCoord = nextCenterPerm * NUM_CORNER_PERM_STATES + nextCornerPerm;

                                int nextCombinedCoord = nextEdgeOrientCoord * NUM_CORNER_CENTER_STATES + nextCornerCenterCoord;
                                if (pruningTableEdgeAndCenterCorner[nextCombinedCoord] < 0) {
                                    pruningTableEdgeAndCenterCorner[nextCombinedCoord] = (byte) (currentDepth + 1);
                                    c++;
                                }
                            }
                        }
                    }
                }
            }
            //Log.w("dct", d + 1 + "\t" + c);
        }
    }

    //</editor-fold>

    //<editor-fold desc="Public Scramble Generator">
    /**
     * Generates a random-state scramble for the LSE (Last Six Edges) case.
     * <p>
     * This method creates a valid scramble by:
     * <ol>
     * <li>Picking random, reachable coordinates for all relevant piece groups.</li>
     * <li>Ensuring the combination of coordinates has a valid parity (a physical
     * constraint of the cube).</li>
     * <li>Solving the cube from that state to produce the scramble.</li>
     * </ol>
     *
     * @param randomGenerator A Random object instance.
     * @return A string representing the scramble.
     */
    public static String scramble(Random randomGenerator) {
        // --- Define Constants ---
        final int MIN_SOLUTION_LENGTH_RETRY = 2;   // If shorter than this, generate a new random state.
        final int MIN_SOLUTION_LENGTH_ACCEPT = 4;   // If shorter than this, keep searching for a longer solution.

        int edgePermCoord, centerPermCoord, cornerPermCoord, edgeOrientCoord;
        // Temporary array for the parity check.
        int[] tempPermutation = new int[NUM_TRACKED_EDGES];

        // --- 1. Generate a Valid, Reachable Random State ---
        // Repeatedly generate random coordinates until their combined parity is valid.
        do {
            // Pick random coordinates for the permutation of edges, centers, and corners.
            edgePermCoord = randomGenerator.nextInt(NUM_EDGE_PERM_STATES);
            centerPermCoord = randomGenerator.nextInt(NUM_CENTER_PERM_STATES);
            cornerPermCoord = randomGenerator.nextInt(NUM_CENTER_PERM_STATES);

            // Unpack the edge permutation to check its parity.
            Utils.idxToPerm(tempPermutation, edgePermCoord, NUM_TRACKED_EDGES, false);

        } while (Utils.permutationSign(tempPermutation) != hasValidParity(centerPermCoord, cornerPermCoord));

        // Pick a random coordinate for the edge orientation.
        edgeOrientCoord = randomGenerator.nextInt(NUM_EDGE_ORIENT_STATES);

        // --- 2. Iterative Deepening Search ---
        // Find the shortest solution from the valid random state.
        for (int searchDepth = 0; searchDepth < MAX_SOLUTION_DEPTH; searchDepth++) {
            if (search(edgePermCoord, edgeOrientCoord, centerPermCoord, cornerPermCoord, searchDepth, INITIAL_LAST_MOVE)) {

                // --- 3. Validate and Format Solution ---
                // If the solution is extremely short, discard it and start over.
                if (searchDepth < MIN_SOLUTION_LENGTH_RETRY) {
                    return scramble(randomGenerator);
                }
                // If the solution is still short, continue searching for a longer, more interesting scramble.
                if (searchDepth < MIN_SOLUTION_LENGTH_ACCEPT) {
                    continue;
                }

                // If a suitable solution is found, format it into a scramble string.
                StringBuilder scrambleBuilder = new StringBuilder();
                for (int i = 1; i <= searchDepth; i++) {
                    int moveCode = solutionSequence[i];
                    int faceIndex = moveCode / 3;
                    int turnType = moveCode % 3;
                    scrambleBuilder
                            .append(MOVE_CHARS[faceIndex])
                            .append(turnSuffixInverse[turnType])
                            .append(" ");
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
     * The recursive IDA* search function for the LSE solver.
     * <p>
     * This method performs a depth-first search, using two separate pruning tables
     * to efficiently find a solution path. It tracks the state of edge permutation,
     * edge orientation, center permutation, and corner permutation simultaneously.
     *
     * @param edgePerm The current edge permutation coordinate.
     * @param edgeOrient The current edge orientation coordinate.
     * @param centerPerm The current M-slice center permutation coordinate.
     * @param cornerPerm The current U-layer corner permutation coordinate.
     * @param depthRemaining The number of moves left in the current search path.
     * @param lastMove The index of the last move made, to avoid redundant sequences.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean search(int edgePerm, int edgeOrient, int centerPerm,
                                  int cornerPerm, int depthRemaining, int lastMove) {
        if (depthRemaining == 0) {
            return cornerPerm == SOLVED_STATE_COORD  &&
                    edgePerm == SOLVED_STATE_COORD  &&
                    centerPerm == SOLVED_STATE_COORD  &&
                    edgeOrient == SOLVED_STATE_COORD ;
        }

        // --- Heuristic Pruning ---
        // Check both pruning tables. If either sub-problem requires more moves
        // than we have left, this entire path is a dead end.
        if (pruningTableEdge[edgePerm * NUM_EDGE_ORIENT_STATES  + edgeOrient] > depthRemaining ||
                pruningTableEdgeAndCenterCorner[edgeOrient * NUM_CORNER_CENTER_STATES  + (centerPerm *
                        NUM_CORNER_PERM_STATES  + cornerPerm)] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore all valid next moves. ---
        for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
            if (moveIndex != lastMove) {
                int nextCornerPerm = cornerPerm;
                int nextEdgePerm = edgePerm;
                int nextCenterPerm = centerPerm;
                int nextEdgeOrient = edgeOrient;

                // Try all 3 turn types for the current face (e.g., U, U2, U').
                for (int turnType = 0; turnType < NUM_TURN_TYPES; turnType++) {
                    // Get the next state for all four coordinates from their respective move tables.
                    nextCornerPerm = moveTableCornerPerm_ULUR[nextCornerPerm][moveIndex];
                    nextEdgePerm = moveTableEdgePerm[nextEdgePerm][moveIndex];
                    nextCenterPerm = moveTableCenterPerm[nextCenterPerm][moveIndex];
                    nextEdgeOrient = moveTableEdgeOrient[nextEdgeOrient][moveIndex];

                    // Make the recursive call for the new state.
                    if (search(nextEdgePerm, nextEdgeOrient, nextCenterPerm, nextCornerPerm, depthRemaining - 1, moveIndex)) {
                        // --- Solution Found! ---
                        // Record the successful move in the solution sequence array.
                        solutionSequence[depthRemaining] = moveIndex * NUM_TURN_TYPES + turnType;
                        //sb.insert(0, turn[i] + suff[k] + " ");
                        return true;
                    }
                }
            }
        }
        // If all moves have been explored from this state without success, backtrack.
        return false;
    }

    /**
     * Checks if the parity of the center and corner permutations match.
     * <p>
     * For a cube state to be solvable, the parity of the M-slice center permutation
     * and the U-layer corner permutation must be the same (i.e., both even or both odd).
     * This function validates that physical constraint.
     *
     * @param centerPermCoord The coordinate for the M-slice center permutation.
     * @param cornerPermCoord The coordinate for the U-layer corner permutation.
     * @return True if the parities match, false otherwise.
     */
    private static boolean hasValidParity (int centerPermCoord, int cornerPermCoord) {
        // Check if the center permutation is even.
        boolean isCenterPermEven = centerPermCoord % 2 == 0;

        // Check if the corner permutation is even.
        boolean isCornerPermEven = cornerPermCoord % 2 == 0;

        // The state is valid only if both parities are the same.
        return isCenterPermEven == isCornerPermEven;
    }
    //</editor-fold>


}
