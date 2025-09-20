package solver;

import java.util.Arrays;
import java.util.Random;

/**
 * A solver for the Skewb puzzle.
 * <p>
 * This class uses coordinate systems to track the state of the 6 centers and
 * 8 corners. It pre-computes lookup tables to find optimal solutions using the
 * four basic moves (twists around the corners). It is primarily used to
 * generate random-state scrambles.
 */
public class SkewbSolver {
    //<editor-fold desc="Constants & Class Variables">
    // --- Constants ---
    private static final int NUM_CENTER_PERM_STATES = 360;    // 6! / 2
    private static final int NUM_CORNER_PERM_STATES = 12;     // 4! / 2
    private static final int NUM_CORNER_ORIENT_STATES = 2187; // 3^7
    private static final int NUM_MOVES = 4;                   // R, U, L, B/F
    private static final int MAX_SOLUTION_DEPTH = 12;
    private static final int NUM_CORNERS = 8;
    private static final int NUM_CENTERS = 6;
    private static final int NUM_TURN_TYPES = 2; // A move can be default or prime
    private static final int SOLVED_STATE_COORD = 0;
    private static final int SOLVED_STATE_DISTANCE = 0;
    private static final int UNVISITED_STATE = -1;
    private static final int INITIAL_LAST_MOVE = -1;


    // --- Lookup Tables ---
    // Move tables for center permutation, corner permutation, and corner orientation.

    static short[][] moveTableCenterPerm = new short[NUM_CENTER_PERM_STATES][NUM_MOVES];
    static short[][] moveTableCornerPerm = new short[NUM_CORNER_PERM_STATES][NUM_MOVES];
    static short[][] moveTableCornerOrient = new short[NUM_CORNER_ORIENT_STATES][NUM_MOVES];

    // Pruning tables for centers and the combined corner state.
    static byte[] pruningTableCenter = new byte[NUM_CENTER_PERM_STATES ];
    static byte[] pruningTableCorner = new byte[NUM_CORNER_PERM_STATES * NUM_CORNER_ORIENT_STATES];

    // --- Solver & State Variables ---
    static int[] solutionSequence = new int[MAX_SOLUTION_DEPTH];
    private static String[] SUFFIXES = {"'", ""};
    static Random randomGenerator = new Random();
    private static boolean isInitialized = false;

    //</editor-fold>

    //<editor-fold desc="Initialization">
    /* Static initializer to generate all tables when the class is loaded. */
    static {
        initializeTables();
    }

    /**
     * Initializes and pre-computes all lookup tables for the Skewb solver.
     * <p>
     * This heavy computation is run only once. It generates the move tables for the
     * three coordinate systems (center permutation, corner permutation, corner orientation)
     * and then builds the corresponding pruning tables.
     */
    static void initializeTables() {
        /* center
         * 		  0
         *	4	1	2	3
         * 		  5
         */

        // =================================================================================
        // Part 1: Build Center Permutation Move Table (6 centers)
        // =================================================================================
        // A temporary array to hold piece states during calculation.

        int[] pieceState  = new int[NUM_CORNERS];
        for (int stateIndex = 0; stateIndex < NUM_CENTER_PERM_STATES; stateIndex++) {
            for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                Utils.idxToPerm(pieceState, stateIndex, NUM_CENTERS, true);
                switch (moveIndex) {
                    case 0:
                        Utils.circle(pieceState, 2, 5, 3);
                        break;    // R-twist
                    case 1:
                        Utils.circle(pieceState, 0, 3, 4);
                        break;    // U-twist
                    case 2:
                        Utils.circle(pieceState, 1, 4, 5);
                        break;    // L-twist
                    case 3:
                        Utils.circle(pieceState, 0, 1, 2);
                        break;    // F-twist
                }
                //if (i == 0) System.out.println(j+": "+ Arrays.toString(arr));
                moveTableCenterPerm[stateIndex][moveIndex] = (short) Utils.permToIdx(pieceState, NUM_CENTERS, true);
            }
        }

        // =================================================================================
        // Part 2: Build Corner Permutation Move Table (4 active corners)
        // =================================================================================
        /* corner
         *      4
         *  0	    1
         *  	3
         *  5	    6
         *      2
         */
        final int NUM_ACTIVE_CORNERS = 4;
        for (int stateIndex = 0; stateIndex < NUM_CORNER_PERM_STATES; stateIndex++) {
            for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                Utils.idxToPerm(pieceState , stateIndex, NUM_ACTIVE_CORNERS, true);
                switch (moveIndex) {
                    case 0: Utils.circle(pieceState , 1, 2, 3); break;  // R-twist
                    case 1: Utils.circle(pieceState , 0, 1, 3); break;  // U-twist
                    case 2: Utils.circle(pieceState , 2, 0, 3); break;  // L-twist
                    case 3: Utils.circle(pieceState , 0, 2, 1); break;  // F-twist
                }
                moveTableCornerPerm[stateIndex][moveIndex] = (short) Utils.permToIdx(pieceState , NUM_ACTIVE_CORNERS, true);
            }
        }

        // =================================================================================
        // Part 3: Build Corner Orientation Move Table (8 corners)
        // =================================================================================
        for (int stateIndex = 0; stateIndex < NUM_CORNER_ORIENT_STATES; stateIndex++) {
            for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                Utils.idxToOri(pieceState , stateIndex, NUM_CORNERS, true);
                switch (moveIndex) {
                    case 0: // R-twist
                        Utils.circle(pieceState , 1, 2, 3);
                        pieceState [6]++; pieceState [1] += 2; pieceState [2] += 2; pieceState [3] += 2;
                        break;
                    case 1: // U-twist
                        Utils.circle(pieceState , 0, 1, 3);
                        pieceState [4]++; pieceState [0] += 2; pieceState [1] += 2; pieceState [3] += 2;
                        break;
                    case 2: // L-twist
                        Utils.circle(pieceState , 2, 0, 3);
                        pieceState [5]++; pieceState [0] += 2; pieceState [2] += 2; pieceState [3] += 2;
                        break;
                    case 3: // F-twist
                        Utils.circle(pieceState , 0, 2, 1);
                        pieceState [7]++; pieceState [0] += 2; pieceState [1] += 2; pieceState [2] += 2;
                        break;
                }
                moveTableCornerOrient[stateIndex][moveIndex] = (short) Utils.oriToIdx(pieceState , NUM_CORNERS, true);
            }
        }

        // =================================================================================
        // Part 4: Build Pruning Tables 📊
        // =================================================================================
        final int CENTER_PRUNING_DEPTH = 5;
        final int CORNER_PRUNING_DEPTH = 7;

        // --- Center Pruning Table ---
        Arrays.fill(pruningTableCenter, (byte) UNVISITED_STATE);
        pruningTableCenter[SOLVED_STATE_COORD] = SOLVED_STATE_DISTANCE;
        Utils.populatePruningTable(pruningTableCenter, CENTER_PRUNING_DEPTH, moveTableCenterPerm, NUM_TURN_TYPES) ;

        Arrays.fill(pruningTableCorner, (byte) UNVISITED_STATE);
        pruningTableCorner[SOLVED_STATE_COORD] = SOLVED_STATE_DISTANCE;
        Utils.populatePruningTable(pruningTableCorner, CORNER_PRUNING_DEPTH, moveTableCornerOrient, moveTableCornerPerm, NUM_TURN_TYPES );
    }
    //</editor-fold>

    //<editor-fold desc="Public Scramble Generators">
    /**
     * Generates a standard random-state scramble.
     * <p>
     * This method creates a scramble by picking a random reachable state for all
     * pieces and then finding its optimal solution. The reversed solution becomes
     * the scramble. It includes logic to ensure the scramble is not trivially short.
     *
     * @return A string representing the scramble moves.
     */
    public static String scramble() {
        // --- Define Constants ---
        final int MIN_LENGTH_FOR_RETRY = 2;   // If shorter than this, generate a new random state.
        final int MIN_LENGTH_TO_ACCEPT = 5;   // If shorter than this, keep searching for a longer solution.


        int startCenterPerm;
        int startCornerPerm;
        int startCornerOrient;
        // Pick a random coordinate for the center permutation.
        startCenterPerm = randomGenerator.nextInt(NUM_CENTER_PERM_STATES);

        // Repeatedly pick random corner coordinates until a reachable state is found.
        do {
            startCornerPerm = randomGenerator.nextInt(NUM_CORNER_PERM_STATES);
            startCornerOrient = randomGenerator.nextInt(NUM_CORNER_ORIENT_STATES);
        } while (pruningTableCorner[startCornerOrient * NUM_CORNER_PERM_STATES + startCornerPerm] < 0);  // -1 means unreachable

        // --- 2. Iterative Deepening Search ---
        // Search for the shortest solution from the random state.
        for (int searchDepth = 0; searchDepth < MAX_SOLUTION_DEPTH; searchDepth++) {
            if (search(startCenterPerm, startCornerPerm, startCornerOrient, searchDepth, INITIAL_LAST_MOVE)) {
                // --- 3. Validate and Format Solution ---

                // If the optimal solution is shorter than the required minimum, it's an invalid scramble.
                if (searchDepth < MIN_LENGTH_FOR_RETRY) {
                    return scramble();
                }
                // If the solution is still short, continue searching for a longer, more interesting scramble.
                if (searchDepth < MIN_LENGTH_TO_ACCEPT) {
                    continue;
                }
                // If a suitable solution is found, format it into a scramble string.
                return formatSolution(searchDepth);
            }
        }
        return "error";
    }

    /**
     * The core generator for a WCA-compliant scramble with a minimum length.
     * <p>
     * This method creates a high-quality scramble by finding a solution path from a
     * random state and ensuring it has a fixed length of 11 moves, as required
     * by WCA regulations for Skewb.
     *
     * @param minLength The minimum acceptable length for the scramble.
     * @return A string representing the WCA-compliant scramble, or "error".
     */
    private static String scramble(int minLength) {
        // --- Define Constants ---
        final int WCA_SCRAMBLE_LENGTH = 11;

        // --- 1. Pick a Valid Random Starting State ---
        int startCenterPerm;
        int startCornerPerm;
        int startCornerOrient;

        // Pick a random coordinate for the center permutation.
        startCenterPerm = randomGenerator.nextInt(NUM_CENTER_PERM_STATES);

        // Repeatedly pick random corner coordinates until a reachable state is found.
        do {
            startCornerPerm = randomGenerator.nextInt(NUM_CORNER_PERM_STATES);
            startCornerOrient = randomGenerator.nextInt(NUM_CORNER_ORIENT_STATES);
        } while (pruningTableCorner[startCornerOrient * NUM_CORNER_PERM_STATES + startCornerPerm] < 0);
        // --- 2. Iterative Deepening Search ---
        // Find the shortest solution from the random state.
        for (int searchDepth = 0; searchDepth < MAX_SOLUTION_DEPTH; searchDepth++)
            if (search(startCenterPerm, startCornerPerm, startCornerOrient, searchDepth, INITIAL_LAST_MOVE)) {
                // --- 3. Validate and Finalize the Scramble ---

                // If the optimal solution is shorter than the required minimum, it's an invalid scramble.
                if (searchDepth < minLength) {
                    return "error";
                }

                // If the optimal solution is shorter than the WCA standard length, re-run
                // the search to find *any* valid 11-move path. This ensures all scrambles
                // have a consistent length and complexity.
                if (searchDepth < WCA_SCRAMBLE_LENGTH) {
                    search(startCenterPerm, startCornerPerm, startCornerOrient, WCA_SCRAMBLE_LENGTH, INITIAL_LAST_MOVE);
                }

                // Format the final 11-move solution into a scramble string.
                return formatSolution(WCA_SCRAMBLE_LENGTH);
            }

        // If no solution is found, return an error.
        return "error";
    }

    /**
     * Generates a WCA-compliant scramble with a minimum length.
     * <p>
     * This method ensures the generated scramble is not only valid but also meets
     * a minimum length, making it suitable for official competition-style scrambles.
     * It repeatedly calls the internal generator until a valid scramble is produced.
     *
     * @return A string representing the WCA-compliant scramble moves.
     */
    public static String scrambleWCA() {
        // Define the minimum number of moves for a valid WCA scramble for Skewb.
        final int WCA_MIN_SCRAMBLE_LENGTH = 7;
        String scrambleString;

        // Loop to ensure a valid, sufficiently long scramble is always returned.
        // This handles rare cases where the random state is too close to solved.
        do {
            scrambleString = scramble(WCA_MIN_SCRAMBLE_LENGTH);
        } while (scrambleString.equals("error"));

        return scrambleString;
    }

    /**
     * Generates a scramble for the "Last 2 Layers" (L2L) training case.
     * <p>
     * This method directly constructs a random state where one layer is solved,
     * leaving a random L2L case. It then solves this state to produce a valid
     * scramble for training these specific algorithms.
     *
     * @return A string representing the scramble moves for an L2L case.
     */
    public static String scrambleL2L() {
        // --- Define Constants ---
        final int MIN_LENGTH_FOR_RETRY = 2;
        final int MIN_LENGTH_TO_ACCEPT = 5;
        final int NUM_ACTIVE_CORNERS = 4;

        // --- 1. Construct a Random L2L State ---
        // --- A) Set up the Center Permutation ---
        // A temporary array to build the permutation.

        int[] tempPermutation = new int[NUM_CENTERS];
        // Generate a random permutation for 5 of the 6 centers, leaving the last one solved.
        Utils.idxToPerm(tempPermutation, randomGenerator.nextInt(60), 5, true);
        tempPermutation[5] = 5; // Keep the 6th center solved.
        // Convert this physical state back to its integer coordinate.
        int centerPermCoord = Utils.permToIdx(tempPermutation, NUM_CENTERS, true);

        // --- B) Set up the Corner State ---
        // For L2L, the corner permutation is solved.
        int cornerPermCoord = SOLVED_STATE_COORD, cornerOrientCoord;

        do {
            int[] tempOrient = new int[NUM_ACTIVE_CORNERS];
            Utils.idxToOri(tempOrient, randomGenerator.nextInt(27), NUM_ACTIVE_CORNERS, true);
            // The full orientation coordinate is built from the 4 active corners.
            int [] fullOrientArray = new int[] {tempOrient[0], tempOrient[1], 0, 0, tempOrient[2], 0, 0, tempOrient[3]};
            cornerOrientCoord = Utils.oriToIdx(fullOrientArray , NUM_CORNERS, true);
        } while (pruningTableCorner[cornerOrientCoord * NUM_CORNER_PERM_STATES + cornerPermCoord] < 0);

        // --- 2. Iterative Deepening Search ---
        // Find the shortest solution from the generated L2L state.
        for (int searchDepth = 0; searchDepth < MAX_SOLUTION_DEPTH; searchDepth++) {
            if (search(centerPermCoord, cornerPermCoord, cornerOrientCoord, searchDepth, -1)) {
                // --- 3. Validate and Format Solution ---
                if (searchDepth < MIN_LENGTH_FOR_RETRY) {
                    return scramble(); // Start over if the scramble is too trivial.
                }
                if (searchDepth < MIN_LENGTH_TO_ACCEPT) {
                    continue; // Keep searching for a longer, more interesting scramble.
                }
                return formatSolution(searchDepth);
            }
        }
        return "error";
    }
    //</editor-fold>



    //<editor-fold desc="Internal Solver Logic">
    /**
     * The recursive IDA* search function.
     *
     * @param centerPerm The current center permutation coordinate.
     * @param cornerPerm The current corner permutation coordinate.
     * @param cornerOrient The current corner orientation coordinate.
     * @param depthRemaining The number of moves left in the current search path.
     * @param lastMove The index of the last move made.
     * @return True if a solution is found, false otherwise.
     */
    static boolean search(int centerPerm, int cornerPerm, int cornerOrient, int depthRemaining, int lastMove) {
        // --- Base Case: If no moves are left, check if the state is solved. ---
        if (depthRemaining == 0) {
            return centerPerm == SOLVED_STATE_COORD  &&
                    cornerOrient == SOLVED_STATE_COORD  &&
                    cornerPerm == SOLVED_STATE_COORD ;
        }

        // --- Heuristic Pruning ---
        // Check both pruning tables. If either sub-problem requires more moves
        // than we have left, this entire path is a dead end.
        if (pruningTableCenter[centerPerm] > depthRemaining ||
                pruningTableCorner[cornerOrient * NUM_CORNER_PERM_STATES  + cornerPerm] > depthRemaining)  {
            return false;
        }

        // --- Recursive Step: Explore all valid next moves. ---
        for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
            if (moveIndex != lastMove) {
                int nextCenterPerm = centerPerm;
                int nextCornerPerm = cornerPerm;
                int nextCornerOrient = cornerOrient;

                // Try both turn types for the current face (e.g., R and R').
                for (int turnType = 0; turnType < 2; turnType++) {
                    // Get the next state for all three coordinates from their respective move tables.
                    nextCenterPerm = moveTableCenterPerm[nextCenterPerm][moveIndex];
                    nextCornerPerm = moveTableCornerPerm[nextCornerPerm][moveIndex];
                    nextCornerOrient = moveTableCornerOrient[nextCornerOrient][moveIndex];
                    //System.out.println(p+", "+q+", "+r+": "+search(p, q, r, d-1, k));
                    // Make the recursive call for the new state.
                    if (search(nextCenterPerm, nextCornerPerm, nextCornerOrient, depthRemaining-1, moveIndex)) {
                        // --- Solution Found! ---
                        // Record the successful move in the solution sequence array.
                        solutionSequence[depthRemaining] = moveIndex << 1 | turnType;
                        //sol.append("RULB".charAt(k)).append(suff[m]).append(' ');
                        return true;
                    }
                }
            }
        }
        // If all moves have been explored from this state without success, backtrack.
        return false;
    }

    /**
     * Formats a found solution sequence into a human-readable string.
     * <p>
     * This method converts a raw sequence of move codes into a standard string format.
     * It includes special logic to handle the dynamic axis of the 'B' move on a Skewb,
     * which changes depending on the orientation of the top layer.
     *
     * @param depth The length of the solution found.
     * @return A formatted string representing the solution (e.g., "R' U L ").
     */
    static String formatSolution(int depth) {
        // --- Define Constants ---
        final int B_MOVE_INDEX = 3;

        // This array maps move indices to characters. It is modified during the loop
        // to track the changing axis of the B move.
        String[] moveToChar = { "R", "U", "L", "B" };
        StringBuilder solutionBuilder = new StringBuilder();

        // Iterate through the solution sequence to build the string.
        for (int i = 1; i <= depth; i++) {
            int moveCode = solutionSequence[i];
            int moveIndex = moveCode >> 1; // Unpack the move index (0-3)
            int turnType = moveCode & 1;  // Unpack the turn type (0=' or 1=default)

            // --- Handle Dynamic B-Move Axis ---
            // If the move is a B-twist, it changes the orientation of the top layer.
            // We simulate this by cyclically permuting the R, U, and L move labels.
            if (moveIndex == B_MOVE_INDEX) {
                for (int j = 0; j <= turnType; j++) {
                    String temp = moveToChar[2];        // L
                    moveToChar[2] = moveToChar[1];  // U -> L
                    moveToChar[1] = moveToChar[0];  // R -> U
                    moveToChar[0] = temp;           // L -> R
                }
            }
            // Append the correct move character and suffix to the solution string.
            solutionBuilder.append(moveToChar[moveIndex])
                            .append(SUFFIXES[turnType])
                            .append(" ");
        }
        return solutionBuilder.toString();
    }

    //</editor-fold>

}
