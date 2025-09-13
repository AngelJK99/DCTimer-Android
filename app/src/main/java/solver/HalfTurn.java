package solver;

import android.util.Log;

import java.util.Random;

/**
 * A solver for the Half-Turn Metric (HTM) on a 3x3x3 cube.
 * <p>
 * This class is designed to find solutions using only 180-degree face turns.
 * It uses several coordinate systems to track the permutation of corner and
 * edge groups and relies on pre-computed lookup tables for fast solving.
 * Its primary use is to generate random-state HTM scrambles.
 */
public class HalfTurn {

    //<editor-fold desc="Constants & Class Variables">
    // --- Constants ---
    private static final int NUM_TRACKED_CORNERS = 4;
    private static final int NUM_4_PIECE_PERM_STATES = 24;  // 4!
    private static final int NUM_FACES = 6;
    private static final int MAX_SOLUTION_DEPTH = 21;
    private static final int INITIAL_LAST_MOVE = -1;
    private static final int SOLVED_STATE_COORD = 0;
    private static final int UNVISITED_STATE = -1;


    // --- Lookup Tables ---
    // Move table for the permutation of 4 corners.
    private static short[][] moveTableCornerPerm = new short[NUM_4_PIECE_PERM_STATES][NUM_FACES];

    // Move tables for the permutation of the 3 groups of 4 edges.
    private static short[][][] moveTableEdgePerm = new short[3][NUM_4_PIECE_PERM_STATES][NUM_FACES];

    // Pruning table for the combined state of two corner groups.
    private static byte[] pruningTableCorner = new byte[NUM_4_PIECE_PERM_STATES * NUM_4_PIECE_PERM_STATES];

    // Pruning table for the combined state of the three edge groups.
    private static byte[][][] pruningTableEdge = new byte[NUM_4_PIECE_PERM_STATES][NUM_4_PIECE_PERM_STATES][NUM_4_PIECE_PERM_STATES];

    // --- Solver Variables ---
    // Array to store the found solution sequence.
    private static int[] solutionSequence = new int[MAX_SOLUTION_DEPTH];

    // String array for formatting the output.
    private static String[] MOVE_CHARS = {"U", "D", "F", "B", "L", "R"};

    //</editor-fold>

    //<editor-fold desc="Initialization">
    /* Static initializer to generate all tables when the class is loaded. */
    static {
        initializeTables();
    }

    /**
     * Initializes and pre-computes all lookup tables for the HTM solver.
     * <p>
     * This heavy computation is run only once. It generates move tables for the
     * corner and edge group permutations, then uses them to generate the corresponding
     * pruning tables via Breadth-First Searches.
     */
    private static void initializeTables() {
        // A temporary array to hold a 4-piece permutation during calculation.
        int[] temp = new int[NUM_TRACKED_CORNERS];
        // =================================================================================
        // Part 1: Build Move Tables ⚙️
        // =================================================================================
        for (int stateIndex = 0; stateIndex < NUM_4_PIECE_PERM_STATES; stateIndex++) {
            for (int moveIndex = 0; moveIndex < NUM_FACES; moveIndex++) {

                // --- Corner Permutation Move Table ---

                /*
            CORNERS
                          +---+---+---+
                          | 0 |   |   |
                          +---+---+---+
                          |   | U |  |
                          +---+---+---+
                          |   |   | 1 |
                          +---+---+---+
            +---+---+---+ +---+---+---+ +---+---+---+ +---+---+---+
            | 0 |   |   | |   |   | 1 | | 1 |   |   | |   |   | 0 |
            +---+---+---+ +---+---+---+ +---+---+---+ +---+---+---+
            |   | L |   | |   | F |   | |   | R |   | |   | B |   |
            +---+---+---+ +---+---+---+ +---+---+---+ +---+---+---+
            |   |   | 2 | | 2 |   |   | |   |   | 3 | | 3 |   |   |
            +---+---+---+ +---+---+---+ +---+---+---+ +---+---+---+
                          +---+---+---+
                          | 2 |   |   |
                          +---+---+---+
                          | 2 | D | 3 |
                          +---+---+---+
                          |   |   | 3 |
                          +---+---+---+

            EDGES
                          +---+---+---+
                          |   | 1 |   |
                          +---+---+---+
                          | 0 | U | 1 |
                          +---+---+---+
                          |   | 0 |   |
                          +---+---+---+
            +---+---+---+ +---+---+---+ +---+---+---+ +---+---+---+
            |   | 0 |   | |   | 0 |   | |   | 1 |   | |   | 1 |   |
            +---+---+---+ +---+---+---+ +---+---+---+ +---+---+---+
            | 3 | L | 0 | | 0 | F | 1 | | 1 | R | 2 | | 2 | B | 3 |
            +---+---+---+ +---+---+---+ +---+---+---+ +---+---+---+
            |   | 3 |   | |   | 3 |   | |   | 2 |   | |   | 2 |   |
            +---+---+---+ +---+---+---+ +---+---+---+ +---+---+---+
                          +---+---+---+
                          |   | 3 |   |
                          +---+---+---+
                          | 3 | D | 2 |
                          +---+---+---+
                          |   | 2 |   |
                          +---+---+---+

                 */

                Utils.idxToPerm(temp, stateIndex, NUM_TRACKED_CORNERS, false);
                switch (moveIndex) {  // Apply the correct corner swaps for the move
                    case 0: Utils.swap(temp, 0, 1); break; // U2
                    case 1: Utils.swap(temp, 2, 3); break; // D2
                    case 2: Utils.swap(temp, 0, 2); break; // L2
                    case 3: Utils.swap(temp, 1, 3); break; // R2
                    case 4: Utils.swap(temp, 1, 2); break; // F2
                    case 5: Utils.swap(temp, 0, 3); break; // B2
                }
                moveTableCornerPerm[stateIndex][moveIndex] = (byte) Utils.permToIdx(temp, NUM_TRACKED_CORNERS, false);


                // --- Edge Permutation Move Tables (for 3 separate edge groups) ---
                // Group 1
                Utils.idxToPerm(temp, stateIndex, NUM_TRACKED_CORNERS, false);
                // Apply swaps relevant to the first edge group...
                switch (moveIndex) {
                    case 0: Utils.swap(temp, 0, 1); break; // U2
                    case 1: Utils.swap(temp, 2, 3); break; // D2
                    case 2: Utils.swap(temp, 0, 3); break; // L2
                    case 3: Utils.swap(temp, 1, 2); break; // R2
                }
                moveTableEdgePerm[0][stateIndex][moveIndex] = (byte) Utils.permToIdx(temp, NUM_TRACKED_CORNERS, false);

                // Group 2
                Utils.idxToPerm(temp, stateIndex, NUM_TRACKED_CORNERS, false);
                // Apply swaps relevant to the second edge group...
                switch (moveIndex) {
                    case 0: Utils.swap(temp, 0, 1); break; // U2
                    case 1: Utils.swap(temp, 2, 3); break; // D2
                    case 4: Utils.swap(temp, 0, 3); break; // F2
                    case 5: Utils.swap(temp, 1, 2); break; // B2
                }
                moveTableEdgePerm[1][stateIndex][moveIndex] = (byte) Utils.permToIdx(temp, NUM_TRACKED_CORNERS, false);

                // Group 3
                Utils.idxToPerm(temp, stateIndex, NUM_TRACKED_CORNERS, false);
                // Apply swaps relevant to the third edge group...
                switch (moveIndex) {
                    case 2: Utils.swap(temp, 0, 3); break; // L2
                    case 3: Utils.swap(temp, 1, 2); break; // R2
                    case 4: Utils.swap(temp, 0, 1); break; // F2
                    case 5: Utils.swap(temp, 2, 3); break; // B2
                }
                moveTableEdgePerm[2][stateIndex][moveIndex] = (byte) Utils.permToIdx(temp, NUM_TRACKED_CORNERS, false);
            }
        }

        // =================================================================================
        // Part 2: Build Pruning Tables 📊
        // =================================================================================

        // --- Corner Pruning Table (for 2 combined corner groups) ---
        final int CORNER_PRUNING_DEPTH = 3;
        final int TOTAL_CORNER_STATES = NUM_4_PIECE_PERM_STATES * NUM_4_PIECE_PERM_STATES;
        for (int i = 1; i < TOTAL_CORNER_STATES; i++) {
            pruningTableCorner[i] = UNVISITED_STATE;
        }
        pruningTableCorner[SOLVED_STATE_COORD] = 0;
        // Populate using a generic BFS helper. Note: movesPerFace is 1 because we only use 180-degree turns.
        Utils.populatePruningTable(pruningTableCorner, CORNER_PRUNING_DEPTH, moveTableCornerPerm,
                moveTableCornerPerm, 1);


        // --- Edge Pruning Table (for 3 combined edge groups) ---
        final int EDGE_PRUNING_DEPTH = 8;
        // Initialize the 3D pruning table with an "unvisited" marker.
        for (int i = 0; i < NUM_4_PIECE_PERM_STATES; i++)
            for (int j = 0; j < NUM_4_PIECE_PERM_STATES; j++)
                for (int k = 0; k < NUM_4_PIECE_PERM_STATES; k++)
                    pruningTableEdge[i][j][k] = UNVISITED_STATE;

        // Set the solved state (0,0,0) distance to 0.
        pruningTableEdge[0][0][0] = 0;

        int c = 1;
        // Populate the table layer by layer using a manual Breadth-First Search (BFS).
        for (int currentDepth = 0; currentDepth < EDGE_PRUNING_DEPTH; currentDepth++) {
            //c = 0;
            for (int p1 = 0; p1 < NUM_4_PIECE_PERM_STATES; p1++) {
                for (int p2 = 0; p2 < NUM_4_PIECE_PERM_STATES; p2++) {
                    for (int p3 = 0; p3 < NUM_4_PIECE_PERM_STATES; p3++) {
                        // If the current combined state is at the depth we are searching...
                        if (pruningTableEdge[p1][p2][p3] == currentDepth) {
                            // ...explore all 6 possible half-turns from this state.
                            for (int moveIndex = 0; moveIndex < NUM_FACES; moveIndex++) {
                                // Get the next coordinate for each of the 3 edge groups.
                                int nextP1 = moveTableEdgePerm[0][p1][moveIndex];
                                int nextP2 = moveTableEdgePerm[1][p2][moveIndex];
                                int nextP3 = moveTableEdgePerm[2][p3][moveIndex];
                                // If this new combined state has not been visited yet...
                                if (pruningTableEdge[nextP1][nextP2][nextP3] == UNVISITED_STATE) {
                                    // ...mark its distance as one greater than the current depth.
                                    pruningTableEdge[nextP1][nextP2][nextP3] = (byte) (currentDepth + 1);
                                    c++;
                                }
                            }
                        }
                    }
                }
            }
            Log.w("dct", currentDepth+1+"\t"+c);
        }
    }
    //</editor-fold>

    //<editor-fold desc="Public Scramble Generator">

    /**
     * Generates a random-state scramble using only half-turns (180-degree moves).
     * <p>
     * This method creates a valid scramble by:
     * <ol>
     * <li>Picking random, reachable coordinates for all corner and edge groups.</li>
     * <li>Solving the cube from that state using an IDA* search.</li>
     * <li>Ensuring the resulting scramble has a reasonable length.</li>
     * </ol>
     *
     * @param randomGenerator A Random object instance.
     * @return A string representing the HTM scramble.
     */

    public static String scramble(Random randomGenerator) {
        // --- Define Constants ---
        final int MAX_SEARCH_DEPTH = 20;
        final int MIN_SOLUTION_LENGTH = 4; // Ignore solutions shorter than this.

        int cornerPerm1, cornerPerm2;
        // --- 1. Generate Reachable Corner Coordinates ---
        // Repeatedly pick random coordinates for the two corner groups until a
        // state that is reachable within the pruning table depth is found.
        do {
            cornerPerm1 = randomGenerator.nextInt(NUM_4_PIECE_PERM_STATES);
            cornerPerm2 = randomGenerator.nextInt(NUM_4_PIECE_PERM_STATES);
        } while (pruningTableCorner[cornerPerm1 * NUM_4_PIECE_PERM_STATES + cornerPerm2] < 0);

        int edgePerm1, edgePerm2, edgePerm3;
        // --- 2. Generate Reachable Edge Coordinates ---
        // Do the same for the three edge groups.
        do {
            edgePerm1 = randomGenerator.nextInt(NUM_4_PIECE_PERM_STATES);
            edgePerm2 = randomGenerator.nextInt(NUM_4_PIECE_PERM_STATES);
            edgePerm3 = randomGenerator.nextInt(NUM_4_PIECE_PERM_STATES);
        } while (pruningTableEdge[edgePerm1][edgePerm2][edgePerm3] < 0);

        // --- 3. Iterative Deepening Search ---
        // Find the shortest solution from the valid random state.
        for (int searchDepth = 0; searchDepth < MAX_SEARCH_DEPTH; searchDepth++) {
            if (search(cornerPerm1, cornerPerm2, edgePerm1, edgePerm2, edgePerm3, searchDepth, INITIAL_LAST_MOVE)) {

                // --- 4. Validate and Format Solution ---
                // If the solution is too short, recursively call to get a new, more complex scramble.
                if (searchDepth < 2) return scramble(randomGenerator);

                // If the solution is valid but still short, continue searching for a longer one.
                if (searchDepth < MIN_SOLUTION_LENGTH) {
                    continue;
                }

                // If a suitable solution is found, format it into a scramble string.
                StringBuilder scrambleBuilder = new StringBuilder();
                for (int i = 1; i <= searchDepth; i++) {
                    // All moves are half-turns, so "2" is always appended.
                    scrambleBuilder.append(MOVE_CHARS[solutionSequence[i]]).append("2 ");
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
     * The recursive IDA* search function for the HTM solver.
     * <p>
     * This method performs a depth-first search, using two separate pruning tables
     * (one for corners, one for edges) to efficiently find a solution using only half-turns.
     *
     * @param cornerPerm1    The coordinate for the first corner group's permutation.
     * @param cornerPerm2    The coordinate for the second corner group's permutation.
     * @param edgePerm1      The coordinate for the first edge group's permutation.
     * @param edgePerm2      The coordinate for the second edge group's permutation.
     * @param edgePerm3      The coordinate for the third edge group's permutation.
     * @param depthRemaining The number of moves left in the current search path.
     * @param lastMove       The index of the last move made, to avoid redundant moves.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean search(int cornerPerm1, int cornerPerm2, int edgePerm1, int edgePerm2,
                                  int edgePerm3, int depthRemaining, int lastMove) {
        // --- Base Case: If we have no moves left, check if all coordinates are in the solved state (0). ---
        if (depthRemaining == 0) {
            return cornerPerm1 == SOLVED_STATE_COORD  && cornerPerm2 == SOLVED_STATE_COORD  &&
                    edgePerm1 == SOLVED_STATE_COORD  && edgePerm2 == SOLVED_STATE_COORD  &&
                    edgePerm3 == SOLVED_STATE_COORD ;
        }

        // --- Heuristic Pruning ---
        // Check both corner and edge pruning tables. If either subproblem requires
        // more moves than we have left, this entire path is a dead end.
        if (pruningTableCorner[cornerPerm1 * NUM_4_PIECE_PERM_STATES  + cornerPerm2] > depthRemaining ||
                pruningTableEdge[edgePerm1][edgePerm2][edgePerm3] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore all valid next moves. ---
        for (int moveIndex = 0; moveIndex < NUM_FACES; moveIndex++) {
            if (moveIndex != lastMove) {

                // Get the next state for all five coordinates from their respective move tables.
                int nextCornerPerm1 = moveTableCornerPerm[cornerPerm1][moveIndex];
                int nextCornerPerm2 = moveTableCornerPerm[cornerPerm2][moveIndex];
                int nextEdgePerm1 = moveTableEdgePerm[0][edgePerm1][moveIndex];
                int nextEdgePerm2 = moveTableEdgePerm[1][edgePerm2][moveIndex];
                int nextEdgePerm3 = moveTableEdgePerm[2][edgePerm3][moveIndex];

                // Make the recursive call for the new state.
                if (search(nextCornerPerm1, nextCornerPerm2, nextEdgePerm1, nextEdgePerm2, nextEdgePerm3,
                        depthRemaining - 1, moveIndex)) {
                    // --- Solution Found! ---
                    // Record the successful move in the solution sequence array.
                    solutionSequence[depthRemaining] = moveIndex;
                    //sb.insert(0, turn[n] + "2 ");
                    return true;
                }
            }
        }
        // If all moves have been explored from this state without success, backtrack.
        return false;
    }
    //</editor-fold>


}
