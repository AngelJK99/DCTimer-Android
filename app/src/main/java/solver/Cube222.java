package solver;

import java.util.Random;

import static solver.Utils.turnSuffixInverse;
/**
 * Represents the state and solver for a 2x2x2 cube.
 * <p>
 * This class provides a complete toolset for a 2x2x2 cube, including state
 * manipulation, solving via an IDA* search, and various scramble generation
 * methods for training specific algorithm sets (like EG, PBL).
 * <p>
 * The solver's core uses two coordinate systems:
 * <ul>
 * <li>A permutation coordinate for 7 corners (7! = 5040 states).</li>
 * <li>An orientation/twist coordinate for 7 corners (3^6 = 729 states).</li>
 * </ul>
 */
public class Cube222 {
    //<editor-fold desc="Constants & Class Variables">
    // --- Constants ---
    private static final int NUM_PERMUTATION_STATES = 5040; // 7!
    private static final int NUM_TWIST_STATES = 729;      // 3^6
    private static final int NUM_SOLVER_MOVES = 3;        // U, R, F for the core solver
    private static final int NUM_CORNERS = 8;
    private static final int NUM_TRACKED_CORNERS = 7;
    private static final int MAX_SOLUTION_DEPTH = 12;
    private static final int UNVISITED_STATE = -1;
    private static final int SOLVED_STATE_DISTANCE = 0;
    private static final int SOLVED_STATE_COORD = 0;

    // --- State & Lookup Tables ---
    // The cube's state: [0] = permutation, [1] = orientation (twist)
    private static int[][] cubeState = new int[2][NUM_CORNERS];

    // Pruning tables for permutation and twist.
    private static byte[] pruningTablePerm = new byte[NUM_PERMUTATION_STATES];
    private static byte[] pruningTableTwist = new byte[NUM_TWIST_STATES];

    // Move tables for permutation and twist.
    private static short[][] moveTablePerm = new short[NUM_PERMUTATION_STATES][NUM_SOLVER_MOVES];
    private static short[][] moveTableTwist = new short[NUM_TWIST_STATES][NUM_SOLVER_MOVES];

    // --- Static Data ---
    private static String[] SOLVER_MOVE_CHARS = {"U", "R", "F"};
    private static byte[][] CORNER_FACELET_MAP = {
            { 3, 4, 9   }, { 1, 20, 5  }, { 2, 8, 17   }, { 0, 16, 21  },
            { 13, 11, 6 }, { 15, 7, 22 }, { 12, 19, 10 }, { 14, 23, 18 }
    };
    //private static int[] seq = new int[12];
    private static final Random randomGenerator = new Random();
    //</editor-fold>

    //<editor-fold desc="Initialization">
    /** Static initializer to generate all tables when the class is loaded. */
    static {
        initializeTables();
    }
    /**
     * Initializes and pre-computes all lookup tables for the 2x2x2 solver.
     * <p>
     * This method generates the move tables and pruning tables for both the corner
     * permutation and corner orientation (twist) coordinate systems. This heavy
     * computation is run only once when the class is loaded.
     */
    private static void initializeTables() {

        // =================================================================================
        // Part 1: Permutation Tables (for 7 corners)
        // =================================================================================
        final int PERMUTATION_PRUNING_DEPTH = 7;

        for (int stateIndex = 0; stateIndex < NUM_PERMUTATION_STATES; stateIndex++) {
            pruningTablePerm[stateIndex] = UNVISITED_STATE;  // Initialize pruning table entry.
            for (int moveIndex = 0; moveIndex < NUM_SOLVER_MOVES; moveIndex++)
                moveTablePerm[stateIndex][moveIndex] = (short) getNewPermutationCoord(stateIndex, moveIndex);
        }

        // --- Build Permutation Pruning Table ---
        // The solved state (index 0) has a distance of 0.
        pruningTablePerm[0] = SOLVED_STATE_DISTANCE;
        // Populate the rest of the table using a Breadth-First Search.
        Utils.populatePruningTable(pruningTablePerm, PERMUTATION_PRUNING_DEPTH, moveTablePerm, NUM_SOLVER_MOVES);

        // =================================================================================
        // Part 2: Twist Tables (for 7 corners)
        // =================================================================================
        final int TWIST_PRUNING_DEPTH = 6;

        // --- Build Twist Move Table ---
        // For each of the 729 twist states, calculate the result of each move.
        for (int stateIndex = 0; stateIndex < NUM_TWIST_STATES; stateIndex++) {
            pruningTableTwist[stateIndex] = UNVISITED_STATE; // Initialize pruning table entry
            for (int moveIndex = 0; moveIndex < 3; moveIndex++)
                moveTableTwist[stateIndex][moveIndex] = (short) gettwsmv(stateIndex, moveIndex);
        }
        // --- Build Twist Pruning Table ---
        // The solved state (index 0) has a distance of 0.
        pruningTableTwist[SOLVED_STATE_COORD] = SOLVED_STATE_DISTANCE;
        // Populate the rest of the table using a Breadth-First Search.
        Utils.populatePruningTable(pruningTableTwist, TWIST_PRUNING_DEPTH, moveTableTwist, NUM_SOLVER_MOVES);
    }

    //</editor-fold>

    //<editor-fold desc="Public Scramble Generators">

    /**
     * Generates a standard random-state scramble for the 2x2x2 cube.
     * <p>
     * This method creates a scramble by picking a random permutation state and a random
     * twist state, and then solving the cube from that position. This ensures that
     * the generated scramble is a valid, solvable state.
     *
     * @return A string representing the scramble moves.
     */
    public static String scramble() {
        String scrambleString;

        // This loop ensures that we always return a valid scramble.
        // In the rare case that the solver fails or times out for a random state,
        // it will simply try again with a new random state.
        do {
            // Pick a random permutation coordinate from all 5040 possibilities.
            int randomPermCoord = randomGenerator.nextInt(NUM_PERMUTATION_STATES);
            // Pick a random twist coordinate from all 729 possibilities.
            int randomTwistCoord = randomGenerator.nextInt(NUM_TWIST_STATES);

            // Solve the cube from this random state to generate the scramble.
            scrambleString = solve(randomPermCoord, randomTwistCoord);
        } while (scrambleString.equals("error"));

        return scrambleString;
    }

    /**
     * Generates a WCA-compliant random-state scramble.
     * <p>
     * This method ensures the generated scramble is not only valid and solvable but
     * also meets a minimum length, making it suitable for official competition-style
     * scrambles.
     *
     * @return A string representing the WCA-compliant scramble moves.
     */
    public static String scrambleWCA() {
        // Define the minimum number of moves for a valid WCA scramble for this puzzle.
        final int WCA_MIN_SCRAMBLE_LENGTH = 4;

        String scrambleString;
        // Repeatedly generate a scramble until a valid one of sufficient length is created.
        // This handles the rare cases where the random state is too close to solved.
        do {
            scrambleString = scramble(WCA_MIN_SCRAMBLE_LENGTH);
        } while (scrambleString.equals("error"));

        return scrambleString;
    }

    /**
     * Generates a scramble for a specific EG-1 (Extended Guimond) algorithm case.
     * <p>
     * This method sets up a random cube state corresponding to a specific EG-1 case
     * (e.g., adjacent swap, diagonal swap), converts that state to its coordinates,
     * and then solves it to produce a valid scramble for training that case.
     *
     * @param egCaseType An integer specifying the EG-1 case to generate.
     * @return A string representing the scramble moves.
     */
    public static String scrambleEG(int egCaseType) {
        // --- Define Constants for EG-1 Cases ---
        // These correspond to different permutations of the last layer pieces.
        final int NO_SWAP = 0;
        final int ADJACENT_SWAP = 1;
        final int OPPOSITE_SWAP = 2;

        String scrambleString;
        do {
            // 1. Generate a random cube state for the specified EG-1 case.
            // The "X" parameter tells the helper to exclude the solved OLL case.
            switch (egCaseType) {
                case NO_SWAP:
                    randomEG(4, "X");
                    break;
                case ADJACENT_SWAP:
                    randomEG(2, "X");
                    break;
                case OPPOSITE_SWAP:
                    randomEG(1, "X");
                    break;
            }
            // 2. Convert the generated physical state into coordinates.
            int permCoord = Utils.get8Perm(cubeState[0], NUM_TRACKED_CORNERS);
            int twistCoord = Utils.oriToIdx(cubeState[1], NUM_TRACKED_CORNERS, true);

            // 3. Solve from that state to create the scramble.
            scrambleString = solve(permCoord, twistCoord); // Loop to ensure a valid scramble is returned.

        } while (scrambleString.equals("error")); // Loop to ensure a valid scramble is returned.

        return scrambleString;
    }

    /**
     * Generates a scramble for a specific EG (Extended Guimond) algorithm case.
     * <p>
     * This method sets up a random cube state corresponding to a specific last layer
     * permutation (the 'type') and a specific subset of OLL cases (the 'olls' string).
     * It then converts that state to its coordinates and solves it to produce a valid
     * scramble for training that specific case.
     *
     * @param caseType An integer specifying the last layer permutation (e.g., 4 for solved,
     * 2 for adjacent swap, 1 for diagonal swap).
     * @param ollSubset A string indicating the desired OLL cases (e.g., "H", "Pi", "U", "T", "L", "S", "A").
     * @return A string representing the scramble moves.
     */
    public static String scrambleEG(int caseType, String ollSubset) {
        String scrambleString;

        // Loop to ensure a valid, solvable scramble is always returned.
        do {
            // 1. Generate a random cube state for the specified case.
            randomEG(caseType, ollSubset);

            // 2. Convert the generated physical state into coordinates.
            // It uses the state of the 7 mobile corners to calculate the coordinates.
            int permCoord = Utils.get8Perm(cubeState[0], NUM_TRACKED_CORNERS);
            int twistCoord = Utils.oriToIdx(cubeState[1], NUM_TRACKED_CORNERS, true);

            // 3. Solve from that state to create the scramble.
            scrambleString = solve(permCoord, twistCoord);

        } while (scrambleString.equals("error"));
        return scrambleString;
    }

    /**
     * Generates a scramble for a PBL (Permutation of Both Layers) case with solved OLL.
     * <p>
     * PBL is an algorithm set for solving the 2x2x2 where both layers are oriented
     * correctly, but the corners are permuted. This method sets up such a state
     * and then solves it to produce a valid scramble for training these specific algorithms.
     *
     * @return A string representing the scramble moves.
     */
    public static String scramblePBL() {
        String scrambleString;

        // Loop to ensure a valid, solvable scramble is always returned.
        do {
            // 1. Generate a random cube state for the PBL case.
            // `type = 0` means no bottom layer swap.
            // `olls = "N"` means no orientation is applied to the top layer (OLL solved).
            randomEG(0, "N");

            // 2. Convert the generated physical state into coordinates.
            int permCoord = Utils.get8Perm(cubeState[0], NUM_TRACKED_CORNERS);
            int twistCoord = Utils.oriToIdx(cubeState[1], NUM_TRACKED_CORNERS, true);

            // 3. Solve from that state to create the scramble.
            scrambleString = solve(permCoord, twistCoord);

        } while (scrambleString.equals("error"));

        return scrambleString;
    }

    /**
     * Generates a scramble for a TCLL (Twist Corners Last Layer) case.
     * <p>
     * TCLL is an algorithm set for solving the 2x2x2 where the last layer is
     * permuted correctly, but the corners require twisting. This method sets up
     * a state with a specific corner twist and then solves it to produce a valid
     * scramble for training.
     *
     * @param twistValue An integer representing the type of twist to apply to the corners.
     * @return A string representing the scramble moves.
     */
    public static String scrambleTCLL(int twistValue) {
        String scrambleString;

        // Loop to ensure a valid, solvable scramble is always returned.
        do {
            // 1. Generate a random cube state for the TCLL case.
            // `caseType = 4` tells the helper to generate a state with no permutation swaps.
            randomTEG(4, twistValue);

            // 2. Convert the generated physical state into coordinates.
            int permCoord = Utils.get8Perm(cubeState[0], NUM_TRACKED_CORNERS);
            int twistCoord = Utils.oriToIdx(cubeState[1], NUM_TRACKED_CORNERS, true);

            // 3. Solve from that state to create the scramble.
            scrambleString = solve(permCoord, twistCoord);

        } while (scrambleString.equals("error"));

        return scrambleString;
    }

    /**
     * Generates a scramble for a TEG (Twist EG) case with an adjacent corner swap.
     * <p>
     * This method sets up a random cube state corresponding to a last layer with
     * an adjacent corner swap and a specific twist. It then solves this state
     * to produce a valid scramble for training these specific algorithms.
     *
     * @param twistValue An integer representing the type of twist to apply to the corners.
     * @return A string representing the scramble moves.
     */
    public static String scrambleTEG1(int twistValue) {
        String scramble;

        // Loop to ensure a valid, solvable scramble is always returned.
        do {
            // 1. Generate a random cube state for the TEG case.
            // `caseType = 2` tells the helper to generate a state with an adjacent swap.
            randomTEG(2, twistValue);

            // 2. Convert the generated physical state into coordinates.
            int permCoord = Utils.get8Perm(cubeState[0], NUM_TRACKED_CORNERS);
            int twistCoord = Utils.oriToIdx(cubeState[1], NUM_TRACKED_CORNERS, true);

            // 3. Solve from that state to create the scramble.
            scramble = solve(permCoord, twistCoord);

        } while (scramble.equals("error"));

        return scramble;
    }

    /**
     * Generates a scramble for a TEG (Twist EG) case with a diagonal corner swap.
     * <p>
     * This method sets up a random cube state corresponding to a last layer with
     * a diagonal corner swap and a specific twist. It then solves this state
     * to produce a valid scramble for training these specific algorithms.
     *
     * @param twistValue An integer representing the type of twist to apply to the corners.
     * @return A string representing the scramble moves.
     */
    public static String scrambleTEG2(int twistValue) {
        String scrambleString;
        do {
            // 1. Generate a random cube state for the TEG case.
            // `caseType = 1` tells the helper to generate a state with a diagonal swap.
            randomTEG(1, twistValue);

            // 2. Convert the generated physical state into coordinates.
            int permCoord = Utils.get8Perm(cubeState[0], NUM_TRACKED_CORNERS);
            int twistCoord = Utils.oriToIdx(cubeState[1], NUM_TRACKED_CORNERS, true);

            // 3. Solve from that state to create the scramble.
            scrambleString = solve(permCoord, twistCoord);

        } while (scrambleString.equals("error"));

        return scrambleString;
    }

    /**
     * Generates a scramble for a "no bar" case on the 2x2x2 cube.
     * <p>
     * A "no bar" state is one where no face has two adjacent stickers of the same
     * color. This method repeatedly generates random states until it finds one that
     * satisfies this condition, then solves it to produce a scramble for training.
     *
     * @return A string representing the scramble moves for a "no bar" case.
     */
    public static String scrambleNoBar() {
        // Temporary arrays to hold the physical state of the cube.
        int[] permutation = new int[NUM_CORNERS];
        int[] orientation = new int[NUM_CORNERS];

        // The 8th corner is fixed in this solver's coordinate system.
        permutation[NUM_TRACKED_CORNERS] = NUM_TRACKED_CORNERS;
        int randomPermCoord;
        int randomTwistCoord;

        // --- Generate and Test Loop ---
        // Repeatedly generate random states until one with "no bars" is found.
        do {
            // Pick random coordinates for permutation and twist.
            randomPermCoord = randomGenerator.nextInt(NUM_PERMUTATION_STATES);
            randomTwistCoord = randomGenerator.nextInt(NUM_TWIST_STATES);

            // Decode the coordinates into a physical state representation.
            Utils.idxToPerm(permutation, randomPermCoord, NUM_TRACKED_CORNERS, false);
            Utils.idxToOri(orientation, randomTwistCoord, NUM_TRACKED_CORNERS, true);
        } while (!checkNoBar(permutation, orientation));  // Test if the state has "no bars".

        // Once a valid "no bar" state is found, solve it to get the scramble.
        return solve(randomPermCoord, randomTwistCoord);
    }
    //</editor-fold>

    //<editor-fold desc="Public Cube State API">

    /** Resets the cube to the solved state. */
    private static void reset() {
        for (int i = 0; i < NUM_CORNERS; i++) {
            cubeState[0][i] = i;
            cubeState[1][i] = 0;
        }
    }

    /**
     * Applies a specified move or rotation to the current cube state.
     *
     * @param moveIndex An index representing the move to be applied.
     * 0-5 for face turns (U, R, F, D, L, B).
     * 6-8 for whole-cube rotations (y, x, z).
     * @param turnCount The number of times to apply the move (e.g., 1 for R, 2 for R2, 3 for R').
     */
    private static void doMove(int moveIndex, int turnCount) {
        // Ensure turnCount is within a valid range (0-3). 0 does nothing.
        turnCount %= 4;
        if (turnCount > 0) {
            switch (moveIndex) {
                // --- Case 1: Standard Face Moves (U, R, F, D, L, B) ---
                case 0:	//U
                case 1:	//R
                case 2:	//F
                case 3:	//D
                case 4:	//L
                case 5:	//B
                    // Apply the move 'turnCount' times.
                    for (int i = 0; i < turnCount; i++) {
                        applyPermutationMove(cubeState[0], moveIndex);
                        applyTwistMove(cubeState[1], moveIndex);
                    }
                    break;
                case 6:	// y rotation (like U D')
                case 7:	// x rotation (like R L')
                case 8:	// z rotation (like F B')
                    // Apply the primary face turn (e.g., U for y, R for x).
                    for (int i = 0; i < turnCount; i++) {
                        applyPermutationMove(cubeState[0], moveIndex - 6);
                        applyTwistMove(cubeState[1], moveIndex - 6);
                    }
                    // Apply the opposite face turn in the reverse direction.
                    // (4 - turnCount) correctly calculates the inverse turn.
                    for (int i = 0; i < 4 - turnCount; i++) {
                        applyPermutationMove(cubeState[0], moveIndex - 3);
                        applyTwistMove(cubeState[1], moveIndex - 3);
                    }
                    break;
            }
        }
    }

    /**
     * Swaps two corner pieces (both position and orientation) in the cube's state.
     *
     * @param cornerIndex1 The index (0-7) of the first corner to swap.
     * @param cornerIndex2 The index (0-7) of the second corner to swap.
     */
    private static void swap(int cornerIndex1, int cornerIndex2) {
        // --- Input Validation ---
        // Ensure the indices are valid and different before proceeding.
        if (cornerIndex1 < 0 || cornerIndex2 < 0 || cornerIndex1 > 7 ||
                cornerIndex2 > 7 || cornerIndex1 == cornerIndex2) {
            return;
        }
        // --- 1. Swap the Permutation (Position) ---
        // The piece IDs at the two indices are swapped.
        int temp = cubeState[0][cornerIndex1];
        cubeState[0][cornerIndex1] = cubeState[0][cornerIndex2];
        cubeState[0][cornerIndex2] = temp;

        // --- 2. Swap the Orientation (Twist) ---
        // The twist values at the two indices are also swapped.
        temp = cubeState[1][cornerIndex1];
        cubeState[1][cornerIndex1] = cubeState[1][cornerIndex2];
        cubeState[1][cornerIndex2] = temp;
    }

    /**
     * Applies a twist to a single corner by adding to its orientation value.
     * <p>
     * The final orientation is effectively the result modulo 3.
     *
     * @param cornerIndex The index (0-7) of the corner to twist.
     * @param twistValue The value (0, 1, or 2) to add to the corner's current orientation.
     */    private static void twist(int cornerIndex, int twistValue) {
        // Basic input validation.
        if (twistValue < 0) return;

        // Add the twist value to the existing orientation value for the specified corner.
        // The cubeState[1] array stores the orientation for each of the 8 corners.
        cubeState[1][cornerIndex] += twistValue;
    }

    //</editor-fold>

    //<editor-fold desc="Internal Solver Logic">
    /**
     * The main internal solver that finds a solution from a given coordinate state.
     * <p>
     * This method orchestrates the solving process by taking the permutation and twist
     * coordinates of a scrambled 2x2x2 cube and using an iterative deepening search
     * to find an optimal solution. It also includes logic to discard trivial solutions
     * that are too short.
     *
     * @param permCoord  The starting permutation coordinate of the 7 mobile corners.
     * @param twistCoord The starting twist coordinate of the 7 mobile corners.
     * @return A string of moves representing the solution, or "error" if none is found.
     */
    private static String solve(int permCoord, int twistCoord) {
        // --- Define Constants ---
        final int MIN_SOLUTION_LENGTH = 5; // Ignore solutions shorter than this.

        // Array to store the solution sequence found by the recursive search.
        int[] solutionSequence = new int[MAX_SOLUTION_DEPTH];
        // --- Iterative Deepening Search ---
        // Search for a solution, starting with a depth of 0 and increasing.
        for (int searchDepth = 0; searchDepth < MAX_SOLUTION_DEPTH; searchDepth++) {
            // Call the recursive search function for the current depth.
            if (search(permCoord, twistCoord, searchDepth, -1, solutionSequence))  {

                // --- Solution Found: Validate and Format ---

                // Ignore trivial solutions that are too short (e.g., less than 2 moves).
                if (searchDepth < 2) {
                    return "error";
                }
                // If the solution is valid but still short, continue searching for a longer one.
                if (searchDepth < MIN_SOLUTION_LENGTH) {
                    continue;
                }

                // If a suitable solution is found, format it into a string.
                StringBuilder solutionBuilder = new StringBuilder();
                for (int i = 1; i <= searchDepth; i++) {
                    int moveCode = solutionSequence[i];
                    int faceIndex = moveCode / 3;
                    int turnType = moveCode % 3;
                    solutionBuilder
                            .append(SOLVER_MOVE_CHARS[faceIndex])
                            .append(turnSuffixInverse[turnType])
                            .append(" ");

                }
                return solutionBuilder.toString();
            }
        }
        // If no solution is found within the maximum search depth, return an error.
        return "error";
    }

    /**
     * The recursive IDA* search function for the 2x2x2 solver.
     * <p>
     * This method performs a depth-first search up to a specified depth. It uses
     * pruning tables for both permutation and twist to cut off inefficient branches.
     * It also includes a special mode for generating random scrambles.
     *
     * @param permCoord The current permutation coordinate to search from.
     * @param twistCoord The current twist coordinate to search from.
     * @param depthRemaining The number of moves left to reach the solved state.
     * @param lastMove The index of the last move made. A special value of -2
     * triggers a randomized first move for scramble generation.
     * @param sequence An array to store the solution path.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean search(int permCoord, int twistCoord, int depthRemaining,
                                  int lastMove, int[] sequence) {
        //searches for solution, from position p|t, in l moves exactly. last move was lm, current depth=d
        // --- Base Case: If we have no moves left, check if the state is solved. ---
        if (depthRemaining == 0) {
            return permCoord == SOLVED_STATE_COORD && twistCoord == SOLVED_STATE_COORD;
        }

        // --- Heuristic Pruning ---
        // If either sub-problem requires more moves than we have left, this path is a dead end.
        if (pruningTablePerm[permCoord] > depthRemaining || pruningTableTwist[twistCoord] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore next moves. ---
        final int RANDOM_MOVE_TRIGGER = -2;

        // --- Special Mode: Random first move for scramble generation ---
        if (lastMove == RANDOM_MOVE_TRIGGER) {
            // Pick a random face and a random number of turns (1, 2, or 3).
            int randomMoveCode = randomGenerator.nextInt(9);
            int faceIndex = randomMoveCode / NUM_SOLVER_MOVES;
            int turnCount = randomMoveCode % NUM_SOLVER_MOVES;

            int nextPerm = permCoord;
            int nextTwist = twistCoord;

            // Apply the random move.
            for (int i = 0; i <= turnCount; i++) {
                nextPerm = moveTablePerm[nextPerm][faceIndex];
                nextTwist = moveTableTwist[nextTwist][faceIndex];
            }

            // Continue the search from the new random state.
            if (search(nextPerm, nextTwist, depthRemaining - 1, faceIndex, sequence)) {
                sequence[depthRemaining] = faceIndex * NUM_SOLVER_MOVES + randomMoveCode;
                return true;
            }
        } else {
            // --- Standard Mode: Exhaustive search for solving ---
            for (int moveIndex = 0; moveIndex < NUM_SOLVER_MOVES; moveIndex++) {
                if (moveIndex != lastMove) {
                    int nextPerm = permCoord;
                    int nextTwist = twistCoord;

                    // Try all 3 turn types for the current face (e.g., U, U2, U').
                    for (int turnType  = 0; turnType  < NUM_SOLVER_MOVES; turnType ++) {
                        nextPerm = moveTablePerm[nextPerm][moveIndex];
                        nextTwist = moveTableTwist[nextTwist][moveIndex];

                        // Make the recursive call for the new state.
                        if (search(nextPerm, nextTwist, depthRemaining - 1, moveIndex, sequence)) {
                            sequence[depthRemaining] = moveIndex * NUM_SOLVER_MOVES + turnType;
                            return true;
                        }
                    }
                }
            }
        }

        // If all moves have been explored from this state without success, backtrack.
        return false;
    }

    //</editor-fold>

    //<editor-fold desc="Internal Table Generation Helpers">

    /**
     * Calculates the new permutation coordinate after one move.
     * <p>
     * This helper method is used to build the permutation move table. It decodes
     * a permutation coordinate, applies a physical move to the resulting array,
     * and then re-encodes the array back into a new coordinate.
     *
     * @param permCoord The starting permutation coordinate (0-5039).
     * @param moveIndex The index of the move to apply (0=U, 1=R, 2=F).
     * @return The new permutation coordinate after the move.
     */
    private static int getNewPermutationCoord(int permCoord, int moveIndex) {

        // A temporary array to hold the state of the 7 mobile corners.
        int[] permutationArray = new int[NUM_TRACKED_CORNERS];

        // --- 1. Unpack Coordinate ---
        // Convert the integer coordinate into an array representing the permutation.
        Utils.set8Perm(permutationArray, NUM_TRACKED_CORNERS, permCoord);

        // --- 2. Apply Move ---
        // Perform the physical move on the permutation array.
        applyPermutationMove (permutationArray, moveIndex);

        // --- 3. Repack Coordinate ---
        // Convert the modified permutation array back into its integer coordinate.
        return Utils.get8Perm(permutationArray, NUM_TRACKED_CORNERS);
    }

    /**
     * Calculates the new twist coordinate after one move.
     * <p>
     * This helper method is used to build the twist move table. It decodes
     * a twist coordinate, applies a physical move to the resulting orientation array,
     * and then re-encodes the array back into a new coordinate.
     *
     * @param twistCoord The starting twist coordinate (0-728).
     * @param moveIndex The index of the move to apply (0=U, 1=R, 2=F).
     * @return The new twist coordinate after the move.
     */
    private static int gettwsmv(int twistCoord, int moveIndex) {

        // A temporary array to hold the orientation state of the 7 mobile corners.
        int[] orientationArray = new int[NUM_TRACKED_CORNERS];

        // --- 1. Unpack Coordinate ---
        // Convert the integer coordinate into an array representing the orientations.
        Utils.idxToOri(orientationArray, twistCoord, NUM_TRACKED_CORNERS, true);

        // --- 2. Apply Move ---
        // Perform the physical move on the orientation array.
        applyTwistMove(orientationArray, moveIndex);

        // --- 3. Repack Coordinate ---
        // Convert the modified orientation array back into its integer coordinate.
        return Utils.oriToIdx(orientationArray, NUM_TRACKED_CORNERS, true);
    }

    //</editor-fold>

    //<editor-fold desc="Internal Move Physics">

    /**
     * Applies the permutation effect of a single face turn to a corner array.
     * <p>
     * This method simulates the physical movement of the 8 corner pieces for any
     * of the 6 standard face turns (U, R, F, D, L, B).
     *
     * @param permutationArray The array representing the current permutation of the 8 corners,
     * which will be modified.
     * @param moveIndex The index of the face turn to apply (0=U, 1=R, 2=F, 3=D, 4=L, 5=B).
     */
    private static void applyPermutationMove(int[] permutationArray, int moveIndex) {
        switch (moveIndex) {
            case 0:	// U-move
                Utils.circle(permutationArray, 0, 1, 3, 2); break;
            case 1:	// R-move
                Utils.circle(permutationArray, 0, 4, 5, 1); break;
            case 2:	// F-move
                Utils.circle(permutationArray, 0, 2, 6, 4); break;
            case 3: // D-move
                Utils.circle(permutationArray, 4, 6, 7, 5); break;
            case 4: // L-move
                Utils.circle(permutationArray, 2, 3, 7, 6); break;
            case 5: // B-move
                Utils.circle(permutationArray, 1, 5, 7, 3); break;
        }
    }

    /**
     * Applies the orientation (twist) effect of a single face turn to a corner array.
     * <p>
     * This method simulates the physical change in twist of the 8 corner pieces for any
     * of the 6 standard face turns.
     *
     * @param orientationArray The array representing the current twist of the 8 corners,
     * which will be modified.
     * @param moveIndex The index of the face turn to apply (0=U, 1=R, 2=F, 3=D, 4=L, 5=B).
     */
    private static void applyTwistMove(int[] orientationArray, int moveIndex) {

        switch (moveIndex) {
            case 0: // U-move (no orientation change)
                Utils.circle(orientationArray, 0, 1, 3, 2);
                break;
            case 1: // R-move (with orientation change)
                Utils.circle(orientationArray, 0, 4, 5, 1, new int[] {2, 1, 2, 1});
                break;
            case 2: // F-move (with orientation change)
                Utils.circle(orientationArray, 0, 2, 6, 4, new int[] {1, 2, 1, 2});
                break;
            case 3: // D-move (no orientation change)
                Utils.circle(orientationArray, 4, 6, 7, 5);
                break;
            case 4: // L-move (with orientation change)
                Utils.circle(orientationArray, 2, 3, 7, 6, new int[] {1, 2, 1, 2});
                break;
            case 5:  // B-move (with orientation change)
                Utils.circle(orientationArray, 1, 5, 7, 3, new int[] {2, 1, 2, 1});
                break;
        }
    }
    //</editor-fold>

    //<editor-fold desc="Internal Scramble Helpers">

    /**
     * Generates a random cube state for a specific EG (Extended Guimond) case.
     * <p>
     * This method directly manipulates the cube's state to create a scramble
     * for training specific last-layer algorithms. It sets the permutation and
     * orientation of the corners according to the specified case type.
     *
     * @param lastLayerPermutationType An integer specifying the permutation of the last layer corners.
     * @param ollCaseSubset A string specifying the desired OLL cases to generate.
     */
    public static void randomEG(int lastLayerPermutationType, String ollCaseSubset) {
        // --- Define Constants for Clarity ---
        final int U_MOVE = 0;
        final int Y_ROTATION = 6;
        final int X_ROTATION = 7;
        final int DBL_CORNER_ID = 7; // The ID of the Down-Back-Left corner piece.

        // --- 1. Initial Setup ---
        // Start with a solved cube.
        reset();
        // Apply a random y-rotation to vary the starting orientation.
        doMove(Y_ROTATION, randomGenerator.nextInt(4));

        // --- 2. Set Last Layer Permutation (Bottom Layer) ---
        // The 'type' parameter determines how the bottom layer corners are swapped.
        final int NO_SWAP = 4;
        final int ADJACENT_SWAP = 2;
        final int DIAGONAL_SWAP = 1;

        switch (lastLayerPermutationType) {
            case NO_SWAP:	// No swap needed.
                break;
            case ADJACENT_SWAP:	 // Swap two adjacent corners on the bottom layer.
                swap(4, 6);
                break;
            case DIAGONAL_SWAP:	// Swap two diagonal corners on the bottom layer.
                swap(5, 6);
                break;
            // Other cases handle random selections between swap types.
            case 6:	 // 50% chance of adjacent swap.
                if (randomGenerator.nextInt(2) == 1)
                    swap(4, 5);
                break;
            case 5:	 // 50% chance of diagonal swap.
                if (randomGenerator.nextInt(2) == 1)
                    swap(5, 6);
                break;
            case 3:	// Swap any two random corners on the bottom layer.
                swap(4 + randomGenerator.nextInt(2), 6);
                break;
            default: // Randomly choose between no swap, adjacent, or diagonal.
                switch (randomGenerator.nextInt(3)) {
                    case NO_SWAP:
                        break;
                    case ADJACENT_SWAP:
                        swap(4, 6);
                        break;
                    case DIAGONAL_SWAP:
                        swap(5, 6);
                        break;
                }
                break;
        }

        // --- 3. Randomly Permute Top Layer Corners ---
        // This performs a Fisher-Yates shuffle on the 4 top layer corners.
        for (int i = 0; i < 4; i++) {
            swap(i, i + randomGenerator.nextInt(4 - i));
        }

        // --- 4. Set Top Layer Orientation (OLL) ---
        if (ollCaseSubset.equals(""))
            // Apply a completely random orientation to the top 4 corners.
            Utils.idxToOri(cubeState[1], randomGenerator.nextInt(27), 4, true);
        else if (ollCaseSubset.equals("X") || ollCaseSubset.equals("PHUTLSA")) {
            // Apply any random orientation EXCEPT the solved state.
            Utils.idxToOri(cubeState[1], randomGenerator.nextInt(26) + 1, 4, true);
        } else {
            // Pick a random case from the specified subset (e.g., "H", "Pi", "U", etc.)
            char selectedOllCase  = ollCaseSubset.charAt(randomGenerator.nextInt(ollCaseSubset.length()));
            switch (selectedOllCase ) {
                case 'P':
                    twist(0, 2); twist(1, 1); twist(2, 2); twist(3, 1);
                    break;
                case 'H':
                    twist(0, 2); twist(1, 1); twist(2, 1); twist(3, 2);
                    break;
                case 'U':
                    twist(2, 2); twist(3, 1);
                    break;
                case 'T':
                    twist(2, 1); twist(3, 2);
                    break;
                case 'L':
                    twist(0, 2); twist(3, 1);
                    break;
                case 'S':
                    twist(0, 2); twist(1, 2); twist(3, 2);
                    break;
                case 'A':
                    twist(0, 1); twist(1, 1); twist(2, 1);
                    break;
                case 'N':
                    break;
            }
        }
        // --- 5. Apply Random AUF (Adjust Upper Face) ---
        // Apply a random U turn to the top layer.
        doMove(U_MOVE, randomGenerator.nextInt(4));

        // --- 6. Normalize the Cube (Fix DBL Corner) ---
        // The solver requires the DBL corner (piece 7) to be in its fixed home slot (index 7)
        // and correctly oriented (twist 0). This section moves it there without changing the LL case.
        while (cubeState[0][4] != DBL_CORNER_ID && cubeState[0][5] != DBL_CORNER_ID &&
                cubeState[0][6] != DBL_CORNER_ID && cubeState[0][7] != DBL_CORNER_ID) {
            doMove(X_ROTATION, 1);
        }
        // Perform y-rotations to move the DBL piece to its correct home slot (index 7).
        while (cubeState[0][7] != DBL_CORNER_ID) {
            doMove(Y_ROTATION, 1);
        }
        // Orient the DBL corner correctly using a commutator-like sequence.
        while (cubeState[1][7] % NUM_SOLVER_MOVES != 0) {
            doMove(X_ROTATION, 1);
            doMove(Y_ROTATION, 1);
        }
    }

    /**
     * Generates a random cube state for a specific TEG (Twist Extended Guimond) case.
     * <p>
     * This method directly manipulates the cube's state to create a scramble for training
     * TEG algorithms. It sets a specific last layer permutation and then applies a
     * specific twist pattern to two corners before normalizing the cube.
     *
     * @param lastLayerPermutationType An integer specifying the permutation of the last layer corners.
     * @param twistValue The twist value (1 for clockwise, 2 for counter-clockwise) to apply.
     */
    public static void randomTEG(int lastLayerPermutationType, int twistValue) {
        // --- Define Constants for Clarity ---
        final int U_MOVE = 0;
        final int Y_ROTATION = 6;
        final int X_ROTATION = 7;
        final int DBL_CORNER_ID = 7; // The ID of the Down-Back-Left corner piece.

        // --- 1. Initial Setup ---
        // Start with a solved cube and apply a random y-rotation.

        reset();
        doMove(Y_ROTATION, randomGenerator.nextInt(4));

        // --- 2. Set Last Layer Permutation (Bottom Layer) ---
        // The 'type' parameter determines how the bottom layer corners are swapped.
        final int NO_SWAP = 4;
        final int ADJACENT_SWAP = 2;
        final int DIAGONAL_SWAP = 1;

        switch (lastLayerPermutationType) {
            case NO_SWAP:  // No swap needed.
                break;
            case ADJACENT_SWAP:	// Swap two adjacent corners.
                swap(4, 6);
                break;
            case DIAGONAL_SWAP:	// Swap two diagonal corners.
                swap(5, 6);
                break;
            // Other cases handle random selections between swap types.
            case 6: // 50% chance of adjacent swap.
                if (randomGenerator.nextInt(2) == 1)
                    swap(4, 5);
                break;
            case 5:	// 50% chance of diagonal swap.
                if (randomGenerator.nextInt(2) == 1)
                    swap(5, 6);
                break;
            case 3:	// Swap any two random corners on the bottom layer.
                swap(4 + randomGenerator.nextInt(2), 6);
                break;
            default:
                switch (randomGenerator.nextInt(3)) {
                    case 0: // No swap needed.
                        break;
                    case 1: 	// Swap two adjacent corners.
                        swap(4, 6);
                        break;
                    case 2: // Swap two diagonal corners.
                        swap(5, 6);
                        break;
                }
                break;
        }

        // --- 3. Randomly Permute Top Layer Corners ---
        // This performs a Fisher-Yates shuffle on the 4 top layer corners.
        for (int i = 0; i < 4; i++) {
            swap(i, i + randomGenerator.nextInt(4 - i));
        }

        // --- 4. Set Corner Orientations (Twists) ---
        // Apply a random orientation to the top 4 corners first.
        Utils.idxToOri(cubeState[1], randomGenerator.nextInt(27), 4, true);

        // Apply a specific twist to one bottom layer corner.
        twist(4, twistValue);

        // Apply the opposite twist to a random top layer corner to maintain a valid state.
        twist(randomGenerator.nextInt(4), 3 - twistValue);

        // --- 5. Apply Random AUF (Adjust Upper Face) ---
        // Apply a random U turn to the top layer.
        doMove(U_MOVE, randomGenerator.nextInt(4));

        // --- 6. Normalize the Cube (Fix DBL Corner) ---
        // The solver requires the DBL corner (piece 7) to be in its fixed home slot (index 7)
        // and correctly oriented (twist 0). This section moves it there.

        // Rotate the cube on the x-axis until the DBL piece is in the bottom layer.
        while (cubeState[0][4] != DBL_CORNER_ID  && cubeState[0][5] != DBL_CORNER_ID  &&
                cubeState[0][6] != DBL_CORNER_ID && cubeState[0][7] != DBL_CORNER_ID ) {
            doMove(X_ROTATION, 1);
        }
        while (cubeState[0][7] != DBL_CORNER_ID) {
            doMove(Y_ROTATION, 1);
        }
        // Orient the DBL corner correctly using a commutator-like sequence.
        while (cubeState[1][7] % 3 != 0) {
            doMove(X_ROTATION, 1);
            doMove(Y_ROTATION, 1);
        }
    }

    /**
     * The core random scramble generator with a minimum length requirement.
     * <p>
     * This method finds a solution from a random state and then reverses it to create a
     * scramble. It includes special logic to ensure the final scramble has a fixed
     - * length of 11 moves and is of high quality (no redundant consecutive moves).
     *
     * @param minLength The minimum acceptable length for the scramble.
     * @return A string representing the scramble moves, or "error" if a valid
     * scramble could not be generated.
     */
    private static String scramble(int minLength) {
        // --- Define Constants ---
        final int TARGET_SCRAMBLE_LENGTH = 11;
        final int INITIAL_LAST_MOVE = -1;
        final int RANDOM_MOVE_TRIGGER = -2;

        // --- 1. Pick a Random Starting State ---
        int startPermCoord = randomGenerator.nextInt(5040);
        int startTwistCoord = randomGenerator.nextInt(729);
        // Array to store the solution/scramble sequence.
        int[] sequence = new int[MAX_SOLUTION_DEPTH];

        // --- 2. Iterative Deepening Search ---
        // Find the shortest path from the random state to the solved state.
        for (int searchDepth = 0; searchDepth < MAX_SOLUTION_DEPTH; searchDepth++) {
            if (search(startPermCoord, startTwistCoord, searchDepth, INITIAL_LAST_MOVE, sequence)) {
                // --- 3. Validate and Finalize the Scramble ---
                // If the optimal solution is shorter than the required minimum, reject it.

                if (searchDepth < minLength) {
                    return "error";
                }

                // If the solution is shorter than the target length, re-run the search
                // with a special "random first move" trigger to find an 11-move path.
                // This ensures scrambles have a consistent length and complexity.

                if (searchDepth < TARGET_SCRAMBLE_LENGTH) {
                    search(startPermCoord, startTwistCoord, TARGET_SCRAMBLE_LENGTH, RANDOM_MOVE_TRIGGER, sequence);
                }

                // --- 4. Format the Final Scramble String ---
                StringBuilder scrambleBuilder = new StringBuilder();
                int lastFace = -1; // Used to check for redundant moves.

                for (int i = 1; i <= TARGET_SCRAMBLE_LENGTH; i++) {
                    int moveCode = sequence[i];
                    int faceIndex = moveCode / 3;

                    // Quality Check: If a move affects the same face as the previous one,
                    // the scramble is invalid (e.g., "R R2"). Reject it.
                    if (lastFace == faceIndex) {
                        return "error";
                    }
                    int turnType = moveCode % 3;
                    scrambleBuilder
                            .append(SOLVER_MOVE_CHARS[faceIndex])
                            .append(turnSuffixInverse[turnType])
                            .append(" ");
                    lastFace = faceIndex;
                }
                return scrambleBuilder.toString();
            }
        }

        // If no solution is found, return an error.
        return "error";
    }

    /**
     * Checks if the current cube state has "no bars" on any face.
     * <p>
     * A "bar" is defined as two adjacent facelets (stickers) on the same face
     * having the same color. This method checks all six faces for this condition.
     *
     * @param permutation The current corner permutation array.
     * @param orientation The current corner orientation (twist) array.
     * @return True if the cube has no bars, false otherwise.
     */
    private static boolean checkNoBar(int[] permutation, int[] orientation) {
        // --- Define Constants ---
        final int NUM_TOTAL_FACELETS = 24;
        final int FACELETS_PER_FACE = 4;

        // --- 1. Prepare for Check ---
        // Assign a unique bit flag (1, 2, 4, 8, 16, 32) to each of the 6 face colors.
        char[] faceColorFlags = {1, 2, 4, 8, 16, 32};

        // Create an array to store the color flag for each of the 24 facelets.
        char[] faceletColors = new char[NUM_TOTAL_FACELETS];

        // Populate the faceletColors array based on the cube's current state.
        Utils.fillFacelet(CORNER_FACELET_MAP, faceletColors, permutation, orientation, faceColorFlags, 4);
        // --- 2. Check Each Face for Bars ---
        // Iterate through the 6 faces of the cube (4 facelets at a time).
        for (int faceletIndex = 0; faceletIndex < NUM_TOTAL_FACELETS; faceletIndex += FACELETS_PER_FACE) {
            // This is a bitwise trick to check for bars.
            // Let the four facelet color flags be A, B, C, D in clockwise order.
            // (A|D) represents the colors on one diagonal pair.
            // (B|C) represents the colors on the other diagonal pair.
            // If there is any common bit between these two pairs, it means two
            // adjacent stickers have the same color, forming a bar.

            if (((faceletColors[faceletIndex] | faceletColors[faceletIndex + 3]) &
                    (faceletColors[faceletIndex + 1] | faceletColors[faceletIndex + 2])) != 0)
                return false; // A bar was found.
        }

        // If the loop completes, no bars were found on any face.
        return true;
    }

}
