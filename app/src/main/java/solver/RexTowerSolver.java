package solver;

import java.util.Random;

/**
 * A two-stage solver for a puzzle like the Rex Tower Cube.
 * <p>
 * This class solves the puzzle by first reducing it to a state solvable by a
 * standard Tower Cube (2x2x3) algorithm (Stage 1), and then solving the
 * remainder of the puzzle (Stage 2). It uses separate lookup tables and
 * search functions for each stage.
 */
public class RexTowerSolver {
    //<editor-fold desc="Constants & Class Variables">
    // --- Constants ---
    private static final int S1_NUM_TRACKED_EDGES = 7; // 7!
    private static final int S1_NUM_EDGE_PERM_STATES = 5040; // 7!
    private static final int S1_NUM_EDGE_ORIENT_STATES = 729;  // 3^6
    private static final int S1_NUM_MOVES = 3;               // Uw, R, F
    private static final int S2_NUM_MOVES = 4;               // Uw, R, F

    private static final int NUM_TURN_TYPES = 3;
    private static final int SOLVED_STATE_COORD = 0;
    private static final int SOLVED_STATE_DISTANCE = 0;
    private static final int UNVISITED_STATE = -1;
    private static final int INITIAL_LAST_MOVE = -1;
    private static final int MAX_SOLUTION_DEPTH = 20;

    // --- Lookup Tables ---
    // Stage 1 move tables for edge permutation and orientation.
    private static short[][] moveTable_S1_EdgePerm = new short[S1_NUM_EDGE_PERM_STATES][S1_NUM_MOVES];
    private static short[][] moveTable_S1_EdgeOrient = new short[S1_NUM_EDGE_ORIENT_STATES][S1_NUM_MOVES];
    // Stage 1 pruning tables.
    private static byte[] pruningTable_S1_EdgePerm = new byte[S1_NUM_EDGE_PERM_STATES];
    private static byte[] pruningTable_S1_EdgeOrient = new byte[S1_NUM_EDGE_ORIENT_STATES];
    // --- Solver & State Variables ---
    private static String[] MOVE_CHARS_S1 = {"Uw", "R", "F"};
    private static String[] MOVE_CHARS_S2 = {"U", "R", "F", "D"};
    private static String[] SUFFIXES = {"'", "2", ""};
    private static int[] solutionSequence = new int[25];
    private static int stage1Length, stage2Length;
    private static int startEdgePerm, startEdgeOrient, startCornerPerm;
    private static byte[] TURNS_PER_MOVE = {3, 1, 1, 3};
    private static boolean isInitialized = false;
    //</editor-fold>

    //<editor-fold desc="Initialization">
    /**
     * Initializes and pre-computes all lookup tables for the Stage 1 solver.
     * <p>
     * This heavy computation is run only once. It generates the move tables for the
     * edge permutation and orientation coordinates of the 7 mobile pieces, and then
     * builds the corresponding pruning tables.
     */
    public static void initialize() {
        // A guard to ensure this computation is only run once.
        if (isInitialized) {
            return;
        }
        // This solver may depend on tables from the standard Tower solver.
        TowerSolver.initialize();

        // A temporary array to hold piece states during calculation.
        int[] pieceState  = new int[8];
		/*	0	1
		 *	3	2
		 *
		 *	4	5
		 *	-	6
		 */
        // =================================================================================
        // Part 1: Build Edge Permutation Move Table (7 edges)
        // =================================================================================
        for (int stateIndex = 0; stateIndex < S1_NUM_EDGE_PERM_STATES; stateIndex++) {
            for (int moveIndex = 0; moveIndex < S1_NUM_MOVES; moveIndex++) {
                Utils.set8Perm(pieceState , S1_NUM_TRACKED_EDGES, stateIndex);
                switch (moveIndex) {
                    case 0: Utils.circle(pieceState , 0, 3, 2, 1); break;	//Uw
                    case 1: Utils.circle(pieceState , 1, 2, 5, 6); break;	//R
                    case 2: Utils.circle(pieceState , 2, 3, 4, 5); break;	//F
                }
                moveTable_S1_EdgePerm[stateIndex][moveIndex] = (short) Utils.get8Perm(pieceState, S1_NUM_TRACKED_EDGES);
            }
        }

        // =================================================================================
        // Part 2: Build Edge Orientation Move Table (7 edges)
        // =================================================================================
        for (int stateIndex = 0; stateIndex < S1_NUM_EDGE_ORIENT_STATES; stateIndex++) {
            for (int moveIndex = 0; moveIndex < S1_NUM_MOVES; moveIndex++) {
                Utils.idxToOri(pieceState , stateIndex, S1_NUM_TRACKED_EDGES, true);
                switch (moveIndex) {
                    case 0: // Uw-move (no orientation change)
                        Utils.circle(pieceState , 0, 3, 2, 1);
                        break;	//Uw
                    case 1: // R-move (with orientation change)
                        Utils.circle(pieceState , 1, 2, 5, 6, new int[] {2, 1, 2, 1});
                        //arr[1] += 2; arr[2]++; arr[5] += 2; arr[6]++;
                        break;	//R
                    case 2: // F-move (with orientation change)
                        Utils.circle(pieceState , 2, 3, 4, 5, new int[] {2, 1, 2, 1});
                        //arr[2] += 2; arr[3]++; arr[4] += 2; arr[5]++;
                        break;	//F
                }
                moveTable_S1_EdgeOrient[stateIndex][moveIndex] = (short) Utils.oriToIdx(pieceState , S1_NUM_TRACKED_EDGES, true);
            }
        }

        // =================================================================================
        // Part 3: Build Pruning Tables 📊
        // =================================================================================

        // --- Edge Permutation Pruning Table ---
        final int EDGE_PERM_PRUNING_DEPTH = 4;
        // First, identify all states that are considered "solved" for this phase
        // and set their distance to 0. Unsolved states are marked as unvisited.
        pruningTable_S1_EdgePerm[SOLVED_STATE_COORD] = SOLVED_STATE_DISTANCE;
        for (int i = 1; i < S1_NUM_EDGE_PERM_STATES; i++) {
            Utils.set8Perm(pieceState , S1_NUM_TRACKED_EDGES, i);
            if (isStage1EdgePermSolved(pieceState)) {
                pruningTable_S1_EdgePerm[i] = SOLVED_STATE_DISTANCE;
            } else {
                pruningTable_S1_EdgePerm[i] = UNVISITED_STATE;
            }
        }
        // Then, populate the rest of the table using a BFS.
        Utils.populatePruningTable(pruningTable_S1_EdgePerm, EDGE_PERM_PRUNING_DEPTH, moveTable_S1_EdgePerm, NUM_TURN_TYPES);

        // --- Edge Orientation Pruning Table ---
        final int EDGE_ORIENT_PRUNING_DEPTH = 6;
        for (int i = 1; i < S1_NUM_EDGE_ORIENT_STATES; i++) {
            pruningTable_S1_EdgeOrient[i] = UNVISITED_STATE;
        }
        pruningTable_S1_EdgeOrient[SOLVED_STATE_COORD] = SOLVED_STATE_DISTANCE;
        Utils.populatePruningTable(pruningTable_S1_EdgeOrient, EDGE_ORIENT_PRUNING_DEPTH, moveTable_S1_EdgeOrient, NUM_TURN_TYPES);

        isInitialized = true;
    }

    //</editor-fold>

    //<editor-fold desc="Public Scramble Generator">
    /**
     * Generates a random-state scramble for the puzzle.
     * <p>
     * This method creates a scramble by picking a random starting state and then
     * executing the full two-stage solve. The combined, reversed solution from
     * both stages becomes the scramble.
     *
     * @return A string representing the scramble moves.
     */
    public static String scramble() {
        // --- Define Constants ---
        final int S1_MAX_SEARCH_DEPTH = 20;
        final int MIN_TOTAL_LENGTH = 3;

        // Ensure the solver's lookup tables are initialized.
        initialize();
        Random randomGenerator = new Random();

        // --- 1. Pick a Random Starting State ---
        // The starting coordinates are set as class variables for the search functions.
        startEdgeOrient = randomGenerator.nextInt(S1_NUM_EDGE_ORIENT_STATES);
        startEdgePerm = randomGenerator.nextInt(S1_NUM_EDGE_PERM_STATES);
        startCornerPerm = randomGenerator.nextInt(TowerSolver.NUM_CORNER_PERM_STATES);

        // --- 2. Iterative Deepening Search (Stage 1) ---
        // This loop searches for the Stage 1 solution. The recursive search function
        // will automatically trigger the Stage 2 search upon completion.
        for (stage1Length = 0; stage1Length < S1_MAX_SEARCH_DEPTH; stage1Length++) {
            if (search_Stage1(startEdgePerm, startEdgeOrient, stage1Length, INITIAL_LAST_MOVE)) {

                // --- 3. Validate and Format Solution ---

                // If the total solution is too short, generate a new scramble.
                if (stage1Length + stage2Length < MIN_TOTAL_LENGTH) {
                    return scramble();
                }
                StringBuilder scrambleBuilder = new StringBuilder();

                // First, append the Stage 2 solution moves.
                for (int i = stage1Length + 1; i <= stage2Length + stage1Length; i++) {
                    int moveCode = solutionSequence[i];
                    int faceIndex = moveCode / NUM_TURN_TYPES;
                    int turnType = moveCode % NUM_TURN_TYPES;
                    scrambleBuilder.append(MOVE_CHARS_S2[faceIndex])
                            .append(SUFFIXES[turnType])
                            .append(' ');
                    //sb.append(". ");
                }
                // Then, append the Stage 1 solution moves.
                for (int i = 1; i <= stage1Length; i++) {
                    int moveCode = solutionSequence[i];
                    int faceIndex = moveCode / NUM_TURN_TYPES;
                    int turnType = moveCode % NUM_TURN_TYPES;
                    scrambleBuilder.append(MOVE_CHARS_S1[faceIndex])
                                   .append(SUFFIXES[turnType])
                                   .append(' ');
                }

                return scrambleBuilder.toString();
            }
        }
        // If no solution is found, return an error.
        return "error";
    }

    //</editor-fold>

    //<editor-fold desc="Stage 1 Solver">
    /**
     * The recursive IDA* search function for Stage 1.
     * This search attempts to reduce the puzzle to a state solvable by the Stage 2 solver.
     *
     * @param edgePermCoord The current edge permutation coordinate.
     * @param edgeOrientCoord The current edge orientation coordinate.
     * @param depthRemaining The number of moves left in the current search path.
     * @param lastMove The index of the last face turned.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean search_Stage1(int edgePermCoord, int edgeOrientCoord, int depthRemaining, int lastMove) {
        // --- Base Case: If no moves are left, check if Stage 1 is solved. ---
        if (depthRemaining == 0) {
            // A state is "solved" for Stage 1 if edge orientation is solved (0) AND
            // the edge permutation is in one of the target states (distance 0 in its pruning table).
            // If so, immediately initialize and start the Stage 2 search from this state.
            return edgeOrientCoord == SOLVED_STATE_COORD &&
                    pruningTable_S1_EdgePerm[edgePermCoord] == SOLVED_STATE_DISTANCE  &&
                    initializeAndStartStage2Search();
        }

        // --- Heuristic Pruning ---
        // If either sub-problem requires more moves than we have left, this path is a dead end.
        if (pruningTable_S1_EdgePerm[edgePermCoord] > depthRemaining ||
                pruningTable_S1_EdgeOrient[edgeOrientCoord] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore all valid next moves. ---
        for (int moveIndex = 0; moveIndex < S1_NUM_MOVES; moveIndex++) {
            if (moveIndex != lastMove) {
                int nextEdgeOrient = edgeOrientCoord;
                int nextEdgePerm = edgePermCoord;

                // Try all 3 turn types for the current face (e.g., Uw, Uw2, Uw').
                for (int turnType = 0; turnType < NUM_TURN_TYPES; turnType++) {
                    // Get the next state for both coordinates from the Stage 1 move tables.
                    nextEdgeOrient = moveTable_S1_EdgeOrient[nextEdgeOrient][moveIndex];
                    nextEdgePerm = moveTable_S1_EdgePerm[nextEdgePerm][moveIndex];

                    // Record the move in the path *before* the recursive call.
                    solutionSequence[depthRemaining] = moveIndex * NUM_TURN_TYPES + turnType;

                    // Make the recursive call for the new state.
                    if (search_Stage1(nextEdgePerm, nextEdgeOrient, depthRemaining - 1, moveIndex)) {
                        //sb.insert(0, turn1[i]+suff[j]+" ");
                        return true;
                    }
                }
            }
        }
        // If all moves have been explored from this state without success, backtrack.
        return false;
    }
    //</editor-fold>

    //<editor-fold desc="Stage 2 Solver">
    /**
     * Initializes and starts the search for the Stage 2 solution.
     * <p>
     * This method is called when the Stage 1 search is complete. It calculates the
     * starting state for Stage 2 by applying the Stage 1 solution to the original
     * scrambled state. It then maps the coordinates to the Stage 2 system and
     * begins the second IDA* search.
     *
     * @return True if a Stage 2 solution is found, false otherwise.
     */
    private static boolean initializeAndStartStage2Search() {
        // --- Define Constants ---
        final int MAX_TOTAL_LENGTH = 19;
        final int MIN_TOTAL_LENGTH_TO_ACCEPT = 4;

        // --- 1. Calculate Stage 2 Start State ---
        // Start with the original scrambled coordinates from the class variables.
        int stage2StartEdgePerm = startEdgePerm;
        int stage2StartCornerPerm = startCornerPerm;

        // Apply the just-found Stage 1 solution to get to the starting state for Stage 2.
        for (int i = stage1Length; i > 0; i--) {
            int moveCode = solutionSequence[i];
            int moveIndex  = moveCode / 3;
            int turnType  = moveCode % 3;

            // Note: The inverse moves are not needed here because the move tables
            // are being applied to the scrambled state, not a solved state.
            for (int turn = 0; turn <= turnType; turn++) {
                stage2StartEdgePerm = moveTable_S1_EdgePerm[stage2StartEdgePerm][moveIndex];
                stage2StartCornerPerm = TowerSolver.moveTableCornerPerm[stage2StartCornerPerm][moveIndex];
            }
        }

        // --- 2. Map Coordinates and Start Stage 2 Search ---
        // Map the Stage 1 edge permutation coordinate to the Stage 2 system.
        stage2StartEdgePerm = mapToStage2EdgePermCoord(stage2StartEdgePerm);

        // Determine the last move of Stage 1 to avoid redundant first moves in Stage 2.
        int lastFace_S1 = (stage1Length > 0) ? solutionSequence[1] / 3 : -1;
        if (lastFace_S1 == 0) { // If last move was Uw, no restriction on S2 moves
            lastFace_S1 = -1;
        }
        // Use iterative deepening to find the Stage 2 solution.
        for (stage2Length = 0; stage2Length < MAX_TOTAL_LENGTH  - stage1Length; stage2Length++) {
            if (search_Stage2(stage2StartCornerPerm, stage2StartEdgePerm, stage2Length, lastFace_S1)) {
                if (stage1Length + stage2Length < MIN_TOTAL_LENGTH_TO_ACCEPT) {
                    continue;  // Solution is too short, look for a longer one.
                }
                return true; // A valid solution has been found.
            }
        }
        // If the Stage 2 search fails, return false.
        return false;
    }

    /**
     * The recursive IDA* search function for Stage 2.
     * <p>
     * This search uses the standard Tower Cube solver's tables to find the final
     * solution path from the state left after Stage 1 is complete.
     *
     * @param cornerPermCoord The current corner permutation coordinate.
     * @param edgePermCoord   The current edge permutation coordinate.
     * @param depthRemaining  The number of moves left in the current search path.
     * @param lastMove        The index of the last move made, to avoid redundant moves.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean search_Stage2(int cornerPermCoord, int edgePermCoord, int depthRemaining, int lastMove) {
        // --- Base Case: If we have no moves left, check if the state is solved. ---
        if (depthRemaining == 0) {
            return cornerPermCoord == SOLVED_STATE_COORD && edgePermCoord == SOLVED_STATE_COORD;
        }

        // --- Heuristic Pruning ---
        // Use the pruning tables from the standard TowerSolver.
        if (TowerSolver.PRUNING_TABLE_EDGE[edgePermCoord] > depthRemaining ||
                TowerSolver.pruningTableCorner[cornerPermCoord] > depthRemaining) {
            return false;
        }
        // --- Recursive Step: Explore all valid next moves. ---
        for (int moveIndex = 0; moveIndex < S2_NUM_MOVES; moveIndex++) {
            if (moveIndex  != lastMove) {
                int nextCornerPerm = cornerPermCoord;
                int nextEdgePerm = edgePermCoord;
                for (int turn = 0; turn < TURNS_PER_MOVE[moveIndex]; turn++) {
                    // Get the next state for both coordinates from the TowerSolver's move tables.
                    nextCornerPerm = TowerSolver.moveTableCornerPerm[nextCornerPerm][moveIndex];
                    nextEdgePerm = TowerSolver.moveTableEdgePerm[nextEdgePerm][moveIndex];

                    // Store the move in the solution sequence, offset by the length of the Stage 1 solution.
                    int turnType = (TURNS_PER_MOVE[moveIndex] == 1) ? 1 : turn;

                    solutionSequence[depthRemaining + stage1Length] = moveIndex  * 3 + turnType;

                    // Make the recursive call for the new state.
                    if (search_Stage2(nextCornerPerm, nextEdgePerm, depthRemaining - 1, moveIndex)) {
                        //sb.insert(0, turn2[i]+(i<2?"2":suff[k])+" ");
                        return true;  // Solution found.
                    }
                }
            }
        }
        // If all moves have been explored from this state without success, backtrack.
        return false;
    }

    //</editor-fold>

    //<editor-fold desc="Internal Helper Methods">
    /**
     * Checks if the edge permutation coordinate corresponds to a solved Stage 1 state.
     * <p>
     * A "solved" state for Stage 1 is defined by a specific symmetrical pattern:
     * the first edge must be in its home position (0), and the next three edges
     * must form pairs with their counterparts on the opposite side.
     *
     * @param permutationArray The edge permutation array to check.
     * @return True if the permutation matches the solved pattern, false otherwise.
     */
    private static boolean isStage1EdgePermSolved(int[] permutationArray) {
        // --- Condition 1: Check if the first edge (index 0) is in its home position. ---
        if (permutationArray[0] != 0) {
            return false;
        }
        // --- Condition 2: Check for symmetrical pairing of the next three edges. ---
        // The pieces must form pairs that sum to 7 (e.g., piece 1 in slot 1 and piece 6 in slot 6).
        for (int i = 1; i < 4; i++) {
            // Example: for i=1, it checks if the piece in slot 1 (arr[1]) and the piece
            // in slot 6 (arr[7-1]) sum to 7. This must hold for pairs (1,6), (2,5), and (3,4).
            if (permutationArray[i] + permutationArray[7 - i] != 7) {
                return false;
            }
        }
        // If all conditions are met, the state is considered solved for this stage.
        return true;
    }

    /**
     * Maps a Stage 1 edge permutation coordinate to a Stage 2 coordinate.
     * <p>
     * This method is called after Stage 1 is solved. It takes the resulting 7-edge
     * permutation coordinate, extracts the relevant subset of 3 edges needed for
     * Stage 2, remaps their values, and then packs them into the new, smaller
     * coordinate system used by the Stage 2 solver.
     *
     * @param stage1EdgePermCoord The edge permutation coordinate from the Stage 1 system.
     * @return The corresponding edge permutation coordinate for the Stage 2 system.
     */
    private static int mapToStage2EdgePermCoord(int stage1EdgePermCoord) {
        // A temporary array to hold the physical permutation of the 7 mobile edges.
        int[] permutationArrayS1 = new int[S1_NUM_TRACKED_EDGES];

        // --- 1. Unpack Stage 1 Coordinate ---
        // Decode the Stage 1 coordinate into a full 7-edge permutation array.
        Utils.set8Perm(permutationArrayS1, S1_NUM_TRACKED_EDGES, stage1EdgePermCoord);

        // --- 2. Extract and Remap the Relevant Subset for Stage 2 ---
        // The Stage 2 solver only tracks a subset of 3 edges. This loop extracts
        // them and remaps their IDs to the new system (0, 1, 2).

        int[] permutationArrayS2 = new int[3];
        for (int i = 0; i < 3; i++) {
            int pieceId = permutationArrayS1[i + 1];
            if (pieceId < 4) {
                permutationArrayS2[i] = pieceId;
            }
            else {
                // This remapping handles the symmetry of the puzzle.
                permutationArrayS2[i] = 6 - pieceId;
            }
        }

        // --- 3. Repack into Stage 2 Coordinate ---
        // Convert the new 3-edge permutation array into its unique integer coordinate.
        return Utils.permToIdx(permutationArrayS2, 3, false);
    }

    //</editor-fold>



}
