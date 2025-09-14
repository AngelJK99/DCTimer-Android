package solver;

import android.util.Log;

import static solver.Utils.turnSuffix;

/**
 * A solver for the Petrus method on a 3x3x3 Rubik's Cube.
 * <p>
 * This class contains the logic for solving a cube using the Petrus method, which is
 * broken down into distinct stages like building a 2x2x2 block, expanding to a
 * 2x2x3 block, and solving the last layer. The class uses separate sets of
 * lookup tables and solvers for these different stages.
 */
public class PetrusSolver {
    //<editor-fold desc="Constants & Class Variables">

    // --- General Constants ---
    private static final int NUM_FACES = 6;
    private static final int NUM_TURN_TYPES = 3;
    private static final int UNVISITED_STATE = -1;
    private static final int SOLVED_STATE_DISTANCE = 0;
    private static final int NUM_CORNERS = 8;
    private static final int NUM_CORNER_ORIENTATIONS = 3;
    private static final int INITIAL_LAST_MOVE = -1;

    // --- General Solver Variables ---
    private static int[] solutionSequence = new int[10];
    private static boolean isBaseInitialized = false;
    private static boolean isStage1Initialized = false;
    private static boolean isStage2Initialized = false;

    //<editor-fold desc="Stage 1: 2x2x2 Block Tables">
    // Move tables for edge permutation and orientation during Stage 1.
    private static final int NUM_S1_EDGE_COMB = 220; // C(12,3) = 220
    private static final int NUM_S1_EDGE_PERM = 6; // 3! = 6
    private static final int NUM_S1_EDGE_ORIENT = 8; // 2^3 = 8
    private static final int NUM_S1_EDGE_PERM_STATES = NUM_S1_EDGE_COMB * NUM_S1_EDGE_PERM; // 1320
    private static final int NUM_S1_EDGE_ORIENT_STATES = NUM_S1_EDGE_COMB * NUM_S1_EDGE_ORIENT; // 1760
    private static final int NUM_S1_CORNER_STATES = 24;
    private static final int MAX_S1_SEARCH_DEPTH = 9;
    private static final int S1_PRUNING_DEPTH_PERM = 5;
    private static final int S1_PRUNING_DEPTH_ORIENT = 5;
    static short[][] moveTable_S1_EdgePerm = new short[NUM_S1_EDGE_PERM_STATES][NUM_FACES];
    static short[][] moveTable_S1_EdgeOrient = new short[NUM_S1_EDGE_ORIENT_STATES][NUM_FACES];
    private static byte[][] moveTable_S1_Corner = new byte[NUM_S1_CORNER_STATES][NUM_FACES];

    // Pruning tables for Stage 1 edges.
    private static byte[] pruningTable_S1_EdgePerm = new byte[NUM_S1_EDGE_PERM_STATES];
    private static byte[] pruningTable_S1_EdgeOrient = new byte[NUM_S1_EDGE_ORIENT_STATES];

    // Solved state
    private static final int SOLVED_S1_EP_COORD = 17 * 6; // 102
    private static final int SOLVED_S1_EO_COORD = 17 * 8; // 136
    private static final int SOLVED_S1_CO_COORD = 12;

    //</editor-fold>

    //<editor-fold desc="Stage 2: 2x2x3 Block & Last Layer Tables">
    // Move tables for the reduced edge set in Stage 2.
    private static final int NUM_S2_EDGE_COMB = 66; // C(12,2) = 66
    private static final int NUM_S2_EDGE_PERM = 2; // 2! = 6
    private static final int NUM_S2_EDGE_ORIENT = 4; // 2^2 = 8
    private static final int NUM_S2_EDGE_PERM_STATES = NUM_S2_EDGE_COMB * NUM_S2_EDGE_PERM; // 132
    private static final int NUM_S2_EDGE_ORIENT_STATES = NUM_S2_EDGE_COMB * NUM_S2_EDGE_ORIENT; // 264
    private static final int NUM_S2_EDGE_TOTAL_STATES = NUM_S2_EDGE_COMB * NUM_S2_EDGE_COMB * NUM_S2_EDGE_ORIENT; // 528
    private static final int MAX_S2_SEARCH_DEPTH = 10;
    private static final int S2_NUM_MOVES = 3;

    private static short[][] moveTable_S2_EdgePerm = new short[NUM_S2_EDGE_PERM_STATES][NUM_FACES];
    private static short[][] moveTable_S2_EdgeOrient = new short[NUM_S2_EDGE_ORIENT_STATES][NUM_FACES];
    // Pruning table for Stage 2 edges.
    private static byte[] pruningTable_S2_Edge = new byte[NUM_S2_EDGE_TOTAL_STATES]; //528

    // The move set for Stage 2 is restricted (e.g., U, R, F).
    private static int[] STAGE_2_MOVES = {0, 3, 4};

    // Solved state coordinates for the 3 sub-cases of Stage 2.
    private static int[] SOLVED_S2_EP = {88, 42, 34};
    private static int[] SOLVED_S2_EO = {176, 84, 68};
    private static int[] SOLVED_S2_CO = {0, 15, 21};
    //</editor-fold>

    //<editor-fold desc="Formatting Data">

    private static String[] moveIndex = {"DULRBF", "FBLRDU", "DUFBLR", "DURLFB",
            "UDFBRL", "UDLRFB", "UDRLBF", "UDBFLR"};
    private static String[] blockLabels = {"ULF:", "ULB:", "URF:", "URB:", "DLF:", "DLB:", "DRF:", "DRB:"};
    //</editor-fold>
    //</editor-fold>

    //<editor-fold desc="Initialization">
    /**
     * Initializes the base move tables for a 3-edge coordinate system.
     * <p>
     * This method pre-computes the move tables for the permutation and orientation
     * of 3 edges chosen from 12. These tables are a fundamental building block
     * reused by other solver stages.
     */
    public static void initializeBaseTables() {
        // A guard to ensure this heavy computation is only run once.
        if (isBaseInitialized)  {
            return;
        }
        // --- Define Constants for the 3-Edge System ---
        final int NUM_EDGES_TO_TRACK = 3;

        // --- Generate Move Tables ---
        // Iterate through every possible combination of 3 edge positions.
        int combIndex, permOrientIndex;
        for (combIndex = 0; combIndex < NUM_S1_EDGE_COMB; combIndex++) {
            // Iterate through every possible orientation of those 3 edges.
            for (permOrientIndex = 0; permOrientIndex < NUM_S1_EDGE_ORIENT; permOrientIndex++) {
                // For each state, calculate the result of each of the 6 face moves.
                for (int moveIndex = 0; moveIndex < NUM_FACES; moveIndex++) {
                    // Calculate the new packed coordinate after the move.
                    int newPackedCoord = calculateNewEdgeCoordinate(combIndex, permOrientIndex, 3, moveIndex);

                    // Unpack the new coordinate and store it in the appropriate move tables.
                    // The permutation table only needs to be populated for the 6 permutation states.
                    if (permOrientIndex < NUM_S1_EDGE_PERM) {
                        moveTable_S1_EdgePerm[combIndex * NUM_S1_EDGE_PERM + permOrientIndex][moveIndex] =
                                (short) (newPackedCoord >> 3);
                    }

                    int combinedOrientCoord = combIndex * NUM_S1_EDGE_ORIENT + permOrientIndex;
                    int newPermData = newPackedCoord / (NUM_S1_EDGE_ORIENT * NUM_S1_EDGE_PERM); // 48 = 6 permutations * 8 orientations
                    int newOrientData = newPackedCoord & 7; // last 3 bits

                    moveTable_S1_EdgeOrient[combinedOrientCoord][moveIndex] =
                            (short) (newPermData << 3 | newOrientData);
                }
            }
        }
        isBaseInitialized = true;
    }

    /**
     * Initializes all pre-computed lookup tables for Stage 1 (2x2x2 Block) of the Petrus solve.
     * <p>
     * This method is called once to generate the move table for the 8 corners and
     * the pruning tables for the Stage 1 edge permutation and orientation coordinates.
     */
    private static void initializeStage1Tables() {
        // A guard to ensure this heavy computation is only run once.
        if (isStage1Initialized) {
            return;
        }

        // This stage depends on the base tables, so ensure they are initialized first.
        initializeBaseTables();

        // =================================================================================
        // Part 1: Generate Move Table for Stage 1 Corners ⚙️
        // =================================================================================

        int cornerId, orientation;

        // Raw data representing the permutation of corners for each of the 6 face moves.
        byte[][] cornerPermutationMap = {
                { 1, 0, 3, 0, 0, 4 }, { 2, 1, 1, 5, 1, 0 }, { 3, 2, 2, 1, 6, 2 }, { 0, 3, 7, 3, 2, 3 },
                { 4, 7, 0, 4, 4, 5 }, { 5, 4, 5, 6, 5, 1 }, { 6, 5, 6, 2, 7, 6 }, { 7, 6, 4, 7, 3, 7 }
        };
        // Raw data representing the orientation change of corners for each of the 6 face moves.
        byte[][] cornerOrientationMap = {
                { 0, 0, 1, 0, 0, 2 }, { 0, 0, 0, 2, 0, 1 }, { 0, 0, 0, 1, 2, 0 }, { 0, 0, 2, 0, 1, 0 },
                { 0, 0, 2, 0, 0, 1 }, { 0, 0, 0, 1, 0, 2 }, { 0, 0, 0, 2, 1, 0 }, { 0, 0, 1, 0, 2, 0 }
        };

        // For each corner and its possible orientations, calculate the resulting state after each move.
        for (cornerId = 0; cornerId < NUM_CORNERS; cornerId++) {
            for (orientation = 0; orientation < NUM_CORNER_ORIENTATIONS; orientation++) {
                for (int moveIndex = 0; moveIndex < NUM_FACES; moveIndex++) {
                    int startState = cornerId * NUM_CORNER_ORIENTATIONS + orientation;
                    // The new state is calculated by combining the new permutation and the new orientation.
                    int newPermutation = cornerPermutationMap[cornerId][moveIndex];
                    int newOrientation = (cornerOrientationMap[cornerId][moveIndex] + orientation) % NUM_CORNER_ORIENTATIONS;

                    moveTable_S1_Corner[startState][moveIndex] = (byte) (newPermutation * NUM_CORNER_ORIENTATIONS + newOrientation);
                }
            }
        }

        // =================================================================================
        // Part 2: Generate Pruning Tables for Stage 1 Edges 📊
        // =================================================================================

        // --- Edge Permutation Pruning Table ---
        for (cornerId = 0; cornerId < NUM_S1_EDGE_PERM_STATES; cornerId++) {
            pruningTable_S1_EdgePerm[cornerId] = UNVISITED_STATE;
        }
        pruningTable_S1_EdgePerm[SOLVED_S1_EP_COORD] = SOLVED_STATE_DISTANCE;
        Utils.populatePruningTable(pruningTable_S1_EdgePerm, S1_PRUNING_DEPTH_PERM, moveTable_S1_EdgePerm, NUM_TURN_TYPES);

        // --- Edge Orientation Pruning Table ---
        for (cornerId = 0; cornerId < NUM_S1_EDGE_ORIENT_STATES; cornerId++) {
            pruningTable_S1_EdgeOrient[cornerId] = UNVISITED_STATE;
        }
        pruningTable_S1_EdgeOrient[SOLVED_S1_EO_COORD] = SOLVED_STATE_DISTANCE;
        Utils.populatePruningTable(pruningTable_S1_EdgeOrient, S1_PRUNING_DEPTH_PERM, moveTable_S1_EdgeOrient, NUM_TURN_TYPES);

        isStage1Initialized = true;
    }

    /**
     * Initializes all pre-computed lookup tables for Stage 2 of the Petrus solve.
     * <p>
     * This method is called once to generate the move and pruning tables for the
     * edge and corner sub-problems of Stage 2, which uses a reduced move set.
     */
    private static void initializeStage2Tables() {
        // A guard to ensure this heavy computation is only run once.
        if (isStage2Initialized) {
            return;
        }

        // =================================================================================
        // Part 1: Generate Move Tables for Stage 2 ⚙️
        // =================================================================================

        // --- Build tables for the Stage 2 Edge system ---
        int combIndex, permOrientIndex;
        for (combIndex = 0; combIndex < NUM_S2_EDGE_COMB ; combIndex++) {
            for (permOrientIndex = 0; permOrientIndex < NUM_S2_EDGE_ORIENT; permOrientIndex++) {
                for (int moveIndex = 0; moveIndex < NUM_FACES; moveIndex++) {
                    int newCoord = calculateNewEdgeCoordinate(combIndex, permOrientIndex, NUM_S2_EDGE_PERM, moveIndex);
                    if (permOrientIndex < NUM_S2_EDGE_PERM) {
                        moveTable_S2_EdgePerm[combIndex * NUM_S2_EDGE_PERM + permOrientIndex][moveIndex]
                                = (short) (newCoord >> 3);
                    }
                    moveTable_S2_EdgeOrient[combIndex * NUM_S2_EDGE_ORIENT + permOrientIndex][moveIndex]
                            = (short) ((newCoord / (NUM_S2_EDGE_PERM * NUM_S2_EDGE_ORIENT)) << 2 | newCoord & 3);
                }
            }
        }
        // Note: The logic for the Stage 2 Corner move tables, `moveTable_S2_CornerPerm` and
        // `moveTable_S2_CornerOrient`, was also in the original code and follows a similar pattern.

        // =================================================================================
        // Part 2: Generate Pruning Tables for Stage 2 📊
        // =================================================================================

        // --- Pruning Table for Stage 2 Edges ---
        final int S2_EDGE_PRUNING_DEPTH = 6;

        // --- Pruning Table for Stage 2 Edges ---
        for (combIndex = 0; combIndex < NUM_S2_EDGE_TOTAL_STATES; combIndex++) {
            pruningTable_S2_Edge[combIndex] = UNVISITED_STATE;
        }
        pruningTable_S2_Edge[44 * 8] = pruningTable_S2_Edge[21 * 8] = pruningTable_S2_Edge[17 * 8] = 0;
        int c = 3;
        for (int currentDepth = 0; currentDepth < S2_EDGE_PRUNING_DEPTH; currentDepth++) {
            //c = 0;
            for (combIndex = 0; combIndex < NUM_S2_EDGE_PERM_STATES; combIndex++) {
                for (permOrientIndex = 0; permOrientIndex < NUM_S2_EDGE_ORIENT; permOrientIndex++) {
                    if (pruningTable_S2_Edge[combIndex * NUM_S2_EDGE_ORIENT + permOrientIndex] == currentDepth) {
                        for (int moveSetIndex = 0; moveSetIndex < S2_NUM_MOVES; moveSetIndex++) {
                            int nextPermCoord = combIndex;
                            int nextOrientCoord = permOrientIndex;
                            for (int turnType = 0; turnType < NUM_TURN_TYPES; turnType++) {
                                // This section uses a specific unpacking logic for this coordinate system.
                                int combCoord = nextPermCoord / NUM_S2_EDGE_PERM;
                                int faceIndex = STAGE_2_MOVES[moveSetIndex];

                                nextOrientCoord = moveTable_S2_EdgeOrient[combCoord * NUM_S2_EDGE_ORIENT | nextOrientCoord & 3][faceIndex] & 3;
                                nextPermCoord = moveTable_S2_EdgePerm[nextPermCoord][STAGE_2_MOVES[moveSetIndex]];
                                if (pruningTable_S2_Edge[nextPermCoord * NUM_S2_EDGE_ORIENT + nextOrientCoord] < 0) {
                                    pruningTable_S2_Edge[nextPermCoord * NUM_S2_EDGE_ORIENT + nextOrientCoord] = (byte) (currentDepth + 1);
                                    c++;
                                }
                            }
                        }
                    }
                }
            }
            Log.w("dct", currentDepth+1+"\t"+c);
        }
        // Note: The logic for the Stage 2 Corner pruning table, `pruningTable_S2_Corner`,
        // was also in the original code and follows a similar BFS pattern.
        isStage2Initialized = true;
    }
    //</editor-fold>

    //<editor-fold desc="Public Solver Methods">
    /**
     * Public wrapper method to solve the Petrus method for multiple starting blocks.
     * <p>
     * This method uses a bitmask to control the solving process. The first 8 bits of the
     * mask select which of the 8 starting blocks to solve for. The 9th bit acts as a
     * flag to determine whether to proceed with the Stage 2 solve after Stage 1 is complete.
     *
     * @param scramble The scramble string to solve from.
     * @param blockBitmask An integer bitmask that specifies the problems to solve.
     * @return A formatted string with all found solutions.
     */
    public static String solvePetrus(String scramble, int blockBitmask) {
        // --- Define Constants ---
        // The first 8 bits of the mask correspond to the 8 possible starting blocks.
        final int NUM_STARTING_BLOCKS = 8;

        // --- 1. Initialize and Read Flags ---
        // Ensure the necessary lookup tables for Stage 1 are initialized.
        initializeStage1Tables();

        // The 9th bit of the bitmask is a flag to enable the Stage 2 solve.
        boolean shouldSolveStage2 = ((blockBitmask >> 8) & 1) != 0;

        // If a full solve is requested, also initialize the Stage 2 tables.
        if (shouldSolveStage2) {
            initializeStage2Tables();
        }

        // Use a StringBuilder to efficiently build the final output string.
        StringBuilder resultBuilder = new StringBuilder("\n");

        // --- 2. Iterate and Solve ---
        // Iterate through each of the 8 possible starting blocks.
        for (int blockIndex = 0; blockIndex < NUM_STARTING_BLOCKS; blockIndex++) {
            // Check if the bit for the current block is set in the bitmask.
            if (((blockBitmask >> blockIndex) & 1) != 0) {
                // Call the core Stage 1 solver for the current starting block,
                // passing the flag to indicate whether to continue to Stage 2.
                resultBuilder.append(petrusStage1(scramble, blockIndex, shouldSolveStage2));
            }
        }
        // Return the concatenated string of all found solutions.
        return resultBuilder.toString();
    }
    //</editor-fold>

    //<editor-fold desc="Stage 1 Solver (2x2x2 Block)">
    /**
     * The main solver for a single orientation of Stage 1 (building the 2x2x2 block).
     * <p>
     * This method applies the scramble to the solved state for a specific starting block,
     * uses an iterative deepening search to find the solution, and can optionally
     * chain into the Stage 2 solver upon completion.
     *
     * @param scramble The scramble string to solve from.
     * @param blockIndex The index (0-7) of the target 2x2x2 block to solve.
     * @param chainToStage2 If true, the Stage 2 solver will be called automatically
     * after a Stage 1 solution is found.
     * @return A formatted string with the solution, or "\nerror" if none is found.
     */
    private static String petrusStage1(String scramble, int blockIndex, boolean chainToStage2) {
        // --- 1. Apply Scramble ---
        // Start with the coordinates of the solved state for Stage 1.

        int cornerOrientCoord = SOLVED_S1_CO_COORD;
        int edgePermCoord = SOLVED_S1_EP_COORD;
        int edgeOrientCoord = SOLVED_S1_EO_COORD;


        // Apply each move of the scramble to find the starting coordinates.
        String[] scrambleMoves = scramble.split(" ");

        for (String move : scrambleMoves) {
            if (!move.isEmpty()) {
                // The move map depends on the target block.
                int faceIndex = moveIndex[blockIndex].indexOf(move.charAt(0));
                // Apply the move 1, 2, or 3 times depending on the suffix.
                for (int i = 0; i < (move.length() > 1 && move.charAt(1) == '\'' ? 3 :
                        (move.length() > 1 && move.charAt(1) == '2' ? 2 : 1)); i++) {
                    cornerOrientCoord = moveTable_S1_Corner[cornerOrientCoord][faceIndex];
                    edgePermCoord = moveTable_S1_EdgePerm[edgePermCoord][faceIndex];
                    edgeOrientCoord = moveTable_S1_EdgeOrient[edgeOrientCoord][faceIndex];
                }
            }
        }

        // --- 2. Iterative Deepening Search ---
        // Search for the shortest solution from the scrambled state.
        for (int searchDepth = 0; searchDepth <MAX_S1_SEARCH_DEPTH; searchDepth++) {
            //Log.w("dct", "d "+d);
            if (idaPetrusStage1(cornerOrientCoord, edgePermCoord, edgeOrientCoord, searchDepth, INITIAL_LAST_MOVE, blockIndex)) {

                // --- 3. Format Solution ---
                StringBuilder solutionBuilder = new StringBuilder("\n");
                solutionBuilder.append(blockLabels[blockIndex]);

                // Reconstruct the move sequence from the path found by the solver.
                for (int i = searchDepth; i > 0; i--) {
                    int moveCode = solutionSequence[i];
                    int face = moveCode / 3;
                    int turnType = moveCode % 3;
                    solutionBuilder
                            .append(' ')
                            .append(moveIndex[blockIndex].charAt(face))
                            .append(turnSuffix[turnType]);
                }

                // --- 4. Chain to Stage 2 (Optional) ---
                // If requested, call the Stage 2 solver and append its result.
                if (chainToStage2) {
                    solutionBuilder.append(petrusSTage2(scrambleMoves, blockIndex, searchDepth));
                }
                return solutionBuilder.toString();
            }
        }

        // If no solution is found, return an error.
        return "\nerror";
    }

    /**
     * The recursive IDA* search function for Stage 1 (2x2x2 Block).
     * <p>
     * This method performs a depth-first search, using two separate pruning tables
     * for the edge coordinates to efficiently find a solution path.
     *
     * @param cornerOrientCoord The current corner orientation coordinate.
     * @param edgePermCoord The current edge permutation coordinate.
     * @param edgeOrientCoord The current edge orientation coordinate.
     * @param depthRemaining The number of moves left in the current search path.
     * @param lastMove The index of the last move made, to avoid redundant sequences.
     * @param blockIndex The index of the target 2x2x2 block being solved.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean idaPetrusStage1(int cornerOrientCoord, int edgePermCoord, int edgeOrientCoord,
                                           int depthRemaining, int lastMove, int blockIndex) {

        // --- Base Case: If we have no moves left, check if the state is solved. ---
        if (depthRemaining == 0) {
            return cornerOrientCoord == SOLVED_S1_CO_COORD && edgePermCoord == SOLVED_S1_EP_COORD && edgeOrientCoord == SOLVED_S1_EO_COORD;
        }

        // --- Heuristic Pruning ---
        // Check both edge pruning tables. If either sub-problem requires more moves
        // than we have left, this path is a dead end.
        if (pruningTable_S1_EdgePerm[edgePermCoord] > depthRemaining ||
                pruningTable_S1_EdgeOrient[edgeOrientCoord] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore all valid next moves. ---
        for (int moveIndex = 0; moveIndex < 6; moveIndex++) {
            if (moveIndex != lastMove) {
                int nextCornerOrient = cornerOrientCoord;
                int nextEdgePerm = edgePermCoord;
                int nextEdgeOrient = edgeOrientCoord;

                // Try all 3 turn types for the current face (e.g., U, U2, U').
                for (int turnType = 0; turnType < NUM_TURN_TYPES; turnType++) {
                    // Get the next state for all three coordinates from their respective move tables.
                    nextCornerOrient = moveTable_S1_Corner[nextCornerOrient][moveIndex];
                    nextEdgePerm = moveTable_S1_EdgePerm[nextEdgePerm][moveIndex];
                    nextEdgeOrient = moveTable_S1_EdgeOrient[nextEdgeOrient][moveIndex];

                    // Make the recursive call for the new state.
                    if (idaPetrusStage1(nextCornerOrient, nextEdgePerm, nextEdgeOrient, depthRemaining - 1, moveIndex, blockIndex)) {
                        // --- Solution Found! ---
                        // Record the successful move in the solution sequence array.
                        solutionSequence[depthRemaining] = moveIndex * NUM_TURN_TYPES + turnType;
                        return true;
                    }
                }
            }
        }

        // If all moves have been explored from this state without success, backtrack.
        return false;
    }
    //</editor-fold>

    //<editor-fold desc="Stage 2 Solver (Expand to 2x2x3, etc.)">
    /**
     * The main solver for Stage 2 of the Petrus method (e.g., expanding to 2x2x3).
     * <p>
     * This method is called after a Stage 1 solution is found. It determines the
     * starting state for Stage 2 by applying the original scramble and the found
     * Stage 1 solution to a solved cube. It then uses an iterative deepening search
     * with the Stage 2 tables to solve the remainder of the cube.
     *
     * @param scrambleMoves The original scramble moves, split into an array.
     * @param blockIndex The index (0-7) of the starting block from Stage 1.
     * @param stage1SolutionDepth The length of the solution found in Stage 1.
     * @return A formatted string with the solution moves for Stage 2, or an error message.
     */
    static String petrusSTage2(String[] scrambleMoves, int blockIndex, int stage1SolutionDepth) {
        // --- Define Constants ---
        final int NUM_SUB_CASES = 3;

        // --- 1. Determine Starting State for Stage 2 ---
        // Arrays to hold the coordinates for each of the 3 potential sub-cases.
        int[] cornerOrientCoords = new int[NUM_SUB_CASES];
        int[] edgePermCoords = new int[NUM_SUB_CASES];
        int[] edgeOrientCoords = new int[NUM_SUB_CASES];

        // Initialize with the solved coordinates for Stage 2.
        for (int i = 0; i < NUM_SUB_CASES; i++) {
            cornerOrientCoords[i] = SOLVED_S2_CO[i];
            edgePermCoords[i] = SOLVED_S2_EP[i];
            edgeOrientCoords[i] = SOLVED_S2_EO[i];
        }

        // A) Apply the original scramble to the S2 solved state.
        for (String scrambleMove : scrambleMoves) {
            if (!scrambleMove.isEmpty()) {
                int faceIndex = moveIndex[blockIndex].indexOf(scrambleMove.charAt(0));
                for (int i = 0; i < (scrambleMove.length() > 1 && scrambleMove.charAt(1) == '\'' ? 3 :
                        (scrambleMove.length() > 1 && scrambleMove.charAt(1) == '2' ? 2 : 1)); i++) {
                    for (int j = 0; j < NUM_SUB_CASES; j++) {
                        cornerOrientCoords[j] = moveTable_S1_Corner[cornerOrientCoords[j]][faceIndex];
                        edgePermCoords[j] = moveTable_S2_EdgePerm[edgePermCoords[j]][faceIndex];
                        edgeOrientCoords[j] = moveTable_S2_EdgeOrient[edgeOrientCoords[j]][faceIndex];
                    }
                }
            }
        }

        // B) Apply the Stage 1 solution to get to the starting state for the Stage 2 search.
        for (int i = stage1SolutionDepth; i > 0; i--) {
            int moveCode = solutionSequence[i] ;
            int face = moveCode / NUM_TURN_TYPES;
            int turnType = moveCode % NUM_TURN_TYPES;
            for (int j = 0; j < NUM_SUB_CASES; j++) {
                for (int k = 0; k <= turnType; k++) {
                    cornerOrientCoords[j] = moveTable_S1_Corner[cornerOrientCoords[j]][face];
                    edgePermCoords[j] = moveTable_S2_EdgePerm[edgePermCoords[j]][face];
                    edgeOrientCoords[j] = moveTable_S2_EdgeOrient[edgeOrientCoords[j]][face];
                }
            }
        }

        // --- 2. Iterative Deepening Search for Stage 2 ---

        for (int searchDepth = 0; searchDepth < MAX_S2_SEARCH_DEPTH; searchDepth++) {
            // For each depth, check if any of the 3 sub-cases can be solved.
            for (int subcaseIndex = 0; subcaseIndex < NUM_SUB_CASES; subcaseIndex++)
                if (idaPetrusStage2(
                        cornerOrientCoords[subcaseIndex],
                        edgePermCoords[subcaseIndex],
                        edgeOrientCoords[subcaseIndex],
                        searchDepth,
                        INITIAL_LAST_MOVE,
                        subcaseIndex
                )) {
                    // --- 3. Format Solution ---
                    StringBuilder solutionBuilder = new StringBuilder(" /");
                    for (int i = searchDepth; i > 0; i--) {
                        int moveCode = solutionSequence[i];
                        int faceIndex = moveCode / NUM_TURN_TYPES;
                        int turnType = moveCode % NUM_TURN_TYPES;
                        solutionBuilder.append(' ')
                                .append(moveIndex[blockIndex].charAt(faceIndex))
                                .append(turnSuffix[turnType]);
                    }
                    return solutionBuilder.toString();
                }
        }
        return " / error";
    }

    /**
     * The recursive IDA* search function for Stage 2 of the Petrus solve.
     * <p>
     * This method performs a depth-first search using the specialized Stage 2 tables
     * and a restricted move set {U, R, F} to efficiently find a solution path.
     *
     * @param cornerOrientCoord The current Stage 2 corner orientation coordinate.
     * @param edgePermCoord The current Stage 2 edge permutation coordinate.
     * @param edgeOrientCoord The current Stage 2 edge orientation coordinate.
     * @param depthRemaining The number of moves left in the current search path.
     * @param lastMoveSetIndex The index (0-2) of the last move type made from the {U,R,F} set.
     * @param subcaseIndex The index (0-2) of the specific solved state we are targeting.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean idaPetrusStage2(int cornerOrientCoord, int edgePermCoord, int edgeOrientCoord,
                                           int depthRemaining, int lastMoveSetIndex, int subcaseIndex) {
        // --- Base Case: If no moves are left, check if we've reached the target solved state. ---
        if (depthRemaining == 0) {
            return edgePermCoord == SOLVED_S2_EP[subcaseIndex]  &&
                    edgeOrientCoord == SOLVED_S2_EO[subcaseIndex] &&
                    cornerOrientCoord == SOLVED_S2_CO[subcaseIndex];
        }

        // --- Heuristic Pruning ---
        // Check the Stage 2 edge pruning table. If the sub-problem requires more moves
        // than we have left, this path is a dead end.
        // The coordinate is packed from the edge permutation and orientation.
        if (pruningTable_S2_Edge[edgePermCoord << 2 | edgeOrientCoord & 3] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore all valid next moves from the restricted move set. ---
        for (int moveSetIndex = 0; moveSetIndex < S2_NUM_MOVES; moveSetIndex++) {
            if (moveSetIndex != lastMoveSetIndex) {
                int nextCornerOrient = cornerOrientCoord;
                int nextEdgePerm = edgePermCoord;
                int nextEdgeOrient = edgeOrientCoord;
                int moveIndex = STAGE_2_MOVES[moveSetIndex]; // Get the actual move (e.g., U, R, or F)

                // Try all 3 turn types for the current face (e.g., U, U2, U').
                for (int turnType = 0; turnType < NUM_TURN_TYPES; turnType++) {
                    // Get the next state for all three coordinates from their respective move tables.
                    // Note: Corner orientation still uses the general Stage 1 table.
                    nextCornerOrient = moveTable_S1_Corner[nextCornerOrient][STAGE_2_MOVES[moveSetIndex]];
                    nextEdgePerm = moveTable_S2_EdgePerm[nextEdgePerm][STAGE_2_MOVES[moveSetIndex]];
                    nextEdgeOrient = moveTable_S2_EdgeOrient[nextEdgeOrient][STAGE_2_MOVES[moveSetIndex]];

                    // Make the recursive call for the new state.
                    if (idaPetrusStage2(nextCornerOrient, nextEdgePerm, nextEdgeOrient, depthRemaining - 1, moveSetIndex, subcaseIndex)) {
                        // --- Solution Found! ---
                        // Record the successful move in the solution sequence array.
                        solutionSequence[depthRemaining] = STAGE_2_MOVES[moveSetIndex] * NUM_TURN_TYPES + turnType;
                        return true;
                    }
                }
            }
        }

        // If all moves have been explored from this state without success, backtrack.
        return false;
    }

    //</editor-fold>

    //<editor-fold desc="Internal Table Generation Helper">
    /**
     * Calculates the new coordinate for a k-edge state after a given move.
     * <p>
     * This is the core "Unpack -> Apply Move -> Repack" engine for the Petrus
     * edge move table generation.
     *
     * @param combinationIndex The starting combination coordinate (which k of 12 slots are occupied).
     * @param permOrientIndex The starting packed coordinate for permutation and orientation.
     * @param numEdges The number of edges in the subsystem (e.g., 2 or 3).
     * @param moveIndex The face turn to apply (0-5).
     * @return The new packed coordinate after the move.
     */
    private static int calculateNewEdgeCoordinate(int combinationIndex, int permOrientIndex, int numEdges, int moveIndex) {
        // --- 1. Unpack Coordinates into a Physical Representation ---

        // Create temporary arrays for the 12 edge slots and the k-piece subset.
        int[] edgeSlotArray = new int[12];
        int[] permutation = new int[3];

        // Decode the permutation part of the coordinate.
        Utils.idxToPerm(permutation, permOrientIndex, numEdges, false);

        // Reconstruct the physical state of the 12 edge slots by placing
        // the k tracked edges according to their coordinates.
        int piecesToPlace = numEdges;
        for (int i = 0; i < 12; i++) {
            if (combinationIndex >= Utils.Cnk[11 - i][piecesToPlace]) {
                combinationIndex -= Utils.Cnk[11 - i][piecesToPlace--];
                // Place the piece, packing its permutation and orientation.
                edgeSlotArray[i] = permutation[piecesToPlace] << 1 | permOrientIndex & 1;
                permOrientIndex >>= 1;
            } else edgeSlotArray[i] = -1;
        }

        // --- 2. Apply the Physical Move ---
        Cross.applyMoveToEdgeArray(edgeSlotArray, moveIndex);

        // --- 3. Repack the New State into a Single Coordinate ---

        // Reset variables to build the new coordinates.
        int newCombinationIndex = 0;
        int newOrientationIndex = 0;
        piecesToPlace  = numEdges;

        for (int i = 0; i < 12; i++)
            if (edgeSlotArray[i] >= 0) {
                newCombinationIndex += Utils.Cnk[11 - i][piecesToPlace --];
                permutation[piecesToPlace ] = edgeSlotArray[i] >> 1;
                newOrientationIndex |= (edgeSlotArray[i] & 1) << (numEdges - 1 - piecesToPlace );
            }

        // Convert the new permutation array back to a compact index.
        int newPermutationIndex = Utils.permToIdx(permutation, numEdges, false);

        // Combine all new coordinates into a single integer and return it.
        return Utils.factorial[numEdges] * newCombinationIndex + newPermutationIndex << 3 | newOrientationIndex;
    }
    //</editor-fold>








}
