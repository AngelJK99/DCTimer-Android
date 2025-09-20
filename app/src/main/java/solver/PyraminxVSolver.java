package solver;

import java.util.Arrays;

/**
 * A specialized solver for a Pyraminx sub-problem, likely a "V-shape" solve.
 * <p>
 * This class uses coordinate systems to track the state of 3 corners and a
 * subset of 2 edges. It pre-computes lookup tables to find optimal solutions
 * for this specific first step of a Pyraminx solve.
 */
public class PyraminxVSolver {
    //<editor-fold desc="Constants & Class Variables">
    // --- Constants ---
    private static final int NUM_MOVES = 4;
    private static final int NUM_FACES = 4;


    private static final int NUM_2_EDGE_COMB = 15; // C(6, 2)
    private static final int NUM_2_EDGE_PO = 4;    // Permutation (2) and Orientation (2^2) states
    private static final int NUM_2_EDGE_PERMS = 2;
    private static final int NUM_2_EDGE_PERM_STATES = NUM_2_EDGE_COMB * NUM_2_EDGE_PERMS;
    private static final int NUM_2_EDGE_ORIENT_STATES = NUM_2_EDGE_COMB * NUM_2_EDGE_PO;
    private static final int NUM_3_CORNER_ORIENT_STATES = 27;
    private static final int MAX_SOLUTION_DEPTH = 7;
    private static final int UNVISITED_STATE = -1;
    private static final int SOLVED_STATE_DISTANCE = 0;
    private static final int SOLVED_CORNER_TWIST_COORD = 0;

    private static final int NUM_TURNS_TYPE = 2; // Each move is applied as default or prime
    private static final int INITIAL_LAST_MOVE = -1;


    // --- Lookup Tables ---
    // Move tables for a 2-edge subset (permutation and orientation).
    private static short[][] moveTable_EdgePerm = new short[NUM_2_EDGE_PERM_STATES][NUM_MOVES]; // C(6,2) * 2!
    private static short[][] moveTable_EdgeOrient = new short[NUM_2_EDGE_ORIENT_STATES][NUM_MOVES]; // C(6,2) * 2^2
    // Move table for a 3-corner subset (orientation/twist).
    private static short[][] moveTable_CornerTwist = new short[NUM_3_CORNER_ORIENT_STATES][NUM_MOVES]; // 3^3

    // Pruning table for the combined state of all three coordinates (edge permutation, edge orientation, corner twist).
    private static byte[] pruningTable = new byte[3240];

    // --- Solver & State Variables ---
    private static String[] MOVE_MAPS = { "LRBU", "ULBR", "RUBL", "LURB" };
    private static String[] SUFFIXES = { "", "'" };
    private static int[] solutionSequence = new int[MAX_SOLUTION_DEPTH];

    // The coordinates for the 3 solved sub-cases.
    private static boolean isInitialized = false;
    private static int[] SOLVED_EDGE_PERMUTATIONS = {0, 6, 8};

    //</editor-fold>

    //<editor-fold desc="Initialization">
    /* Static initializer to generate all tables when the class is loaded. */
    static {
        initializeTables();
    }

    /**
     * Initializes all pre-computed lookup tables for the Pyraminx V-solver.
     * <p>
     * This heavy computation is run only once. It generates the move tables
     * for the edge and corner subsystems, and then builds a single combined
     * pruning table using a Breadth-First Search.
     */
    private static void initializeTables() {
        // A guard to ensure this computation is only run once.
        if (isInitialized) {
            return;
        }

        // =================================================================================
        // Part 1: Generate Move Tables ⚙️
        // =================================================================================

        // --- Build tables for the 2-edge subsystem ---
        for (int combIndex = 0; combIndex < NUM_2_EDGE_COMB; combIndex++) {
            for (int permOrientIndex = 0; permOrientIndex < NUM_2_EDGE_PO; permOrientIndex++) {
                for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                    int newPackedCoord = getNewEdgeCoordinate(combIndex, permOrientIndex, permOrientIndex, moveIndex);

                    // Unpack and store the new orientation coordinate.
                    moveTable_EdgeOrient[NUM_2_EDGE_PO * combIndex + permOrientIndex][moveIndex] = (short) ((newPackedCoord / 8) << 2 | (newPackedCoord & 3));

                    // Unpack and store the new permutation coordinate.
                    if (permOrientIndex < NUM_2_EDGE_PERMS) {
                        moveTable_EdgePerm[2 * combIndex + permOrientIndex][moveIndex] = (short) (newPackedCoord >> 2);
                    }
                }
            }
        }

        // --- Build tables for the 3-corner twist subsystem ---
        final int NUM_CORNERS_SUBSET = 3;
        int[] tempOrientation = new int[NUM_CORNERS_SUBSET];
        for (int stateIndex = 0; stateIndex < NUM_3_CORNER_ORIENT_STATES; stateIndex++)
            for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                Utils.idxToOri(tempOrientation, stateIndex, NUM_CORNERS_SUBSET, false);
                // Apply the twist for the corresponding move.
                switch (moveIndex) {
                    case 0: // L-move affects the L corner (index 1).
                        tempOrientation[1] = (tempOrientation[1] + 1) % 3;
                        break;
                    case 1: // R-move affects the R corner (index 2).
                        tempOrientation[2] = (tempOrientation[2] + 1) % 3;
                        break;
                    case 2: // B-move affects the B corner (index 0).
                        tempOrientation[0] = (tempOrientation[0] + 1) % 3;
                        break;
                    // U-move (index 3) does not affect these 3 corners.
                }
                moveTable_CornerTwist[stateIndex][moveIndex] = (short) Utils.oriToIdx(tempOrientation, NUM_CORNERS_SUBSET, false);
            }

        // =================================================================================
        // Part 2: Generate the Combined Pruning Table 📊
        // =================================================================================
        final int PRUNING_DEPTH = 6;

        Arrays.fill(pruningTable, (byte) UNVISITED_STATE);
        // Set distance 0 for the three initial solved/target states.
        pruningTable[3 * 8] = SOLVED_STATE_DISTANCE;
        pruningTable[4 * 8] = SOLVED_STATE_DISTANCE;
        pruningTable[0] = SOLVED_STATE_DISTANCE;

        // Populate the table using a Breadth-First Search (BFS).
        for (int currentDepth = 0; currentDepth < PRUNING_DEPTH; currentDepth++) {
            //int p = 0;
            for (int cornerTwistCoord = 0; cornerTwistCoord < NUM_3_CORNER_ORIENT_STATES; cornerTwistCoord++) {
                for (int edgePermCoord = 0; edgePermCoord < NUM_2_EDGE_PERM_STATES; edgePermCoord++) {
                    for (int edgeOrientCoord = 0; edgeOrientCoord < NUM_2_EDGE_PO; edgeOrientCoord++) {
                        if (pruningTable[cornerTwistCoord * NUM_2_EDGE_PERM_STATES * NUM_2_EDGE_PO +
                                edgePermCoord * NUM_2_EDGE_PO + edgeOrientCoord] == currentDepth) {
                            for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                                int nextCornerTwist = cornerTwistCoord;
                                int nextEdgePerm = edgePermCoord;
                                int nextEdgeOrient = edgeOrientCoord;
                                for (int turn = 0; turn < NUM_TURNS_TYPE; turn++) {
                                    // Calculate the next state for all three coordinates.
                                    nextCornerTwist = moveTable_CornerTwist[nextCornerTwist][moveIndex];
                                    int comb = nextEdgePerm / 2;
                                    nextEdgeOrient = moveTable_EdgeOrient[comb * NUM_2_EDGE_PO + nextEdgeOrient % 4][moveIndex] % 4;
                                    nextEdgePerm = moveTable_EdgePerm[nextEdgePerm][moveIndex];

                                    int next = nextCornerTwist * NUM_2_EDGE_PO * NUM_2_EDGE_PERM_STATES
                                            + nextEdgePerm * NUM_2_EDGE_PO + nextEdgeOrient;
                                    if (pruningTable[next] < 0) {
                                        pruningTable[next] = (byte) (currentDepth + 1);
                                        //p++;
                                        //c++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            //Log.w("dct", d+1+"\t"+c);
        }
        isInitialized = true;
    }
    //</editor-fold>

    //<editor-fold desc="Public Solver Methods">
    /**
     * Public wrapper method to solve the Pyraminx V-shape problem for multiple
     * orientations specified by a bitmask.
     *
     * @param scramble The scramble string to solve from.
     * @param orientationBitmask A bitmask where each of the 4 bits corresponds to a
     * specific starting orientation.
     * @return A formatted string with all found solutions.
     */
    public static String solveV(String scramble, int orientationBitmask) {
        // --- Define Constants ---
        final String ORIENTATION_LABELS = "DLRF";

        // Ensure the necessary lookup tables are initialized.
        initializeTables();

        // Use a StringBuilder to efficiently build the final output string.
        StringBuilder resultBuilder = new StringBuilder("\n");

        // Iterate through each of the 4 possible orientations.
        for (int orientationIndex = 0; orientationIndex < NUM_FACES; orientationIndex++) {

            // Check if the bit for the current orientation is set in the bitmask.
            if (((orientationBitmask >> orientationIndex) & 1) != 0) {
                // Append a header for the solution (e.g., "D: ").
                resultBuilder.append('\n')
                        .append(ORIENTATION_LABELS.charAt(orientationIndex))
                        .append(": ")
                        .append(solve(scramble, orientationIndex));
            }
        }
        // Return the concatenated string of all found solutions.
        return resultBuilder.toString();
    }
    //</editor-fold>

    //<editor-fold desc="Internal Solver Logic">
    /**
     * The main solver for a single orientation of the Pyraminx V-problem.
     * <p>
     * This method tracks three potential solution sub-cases at the same time.
     * It applies the scramble to all three, then uses an iterative deepening search
     * to find the first sub-case that can be solved within the depth limit.
     *
     * @param scramble The scramble string to solve from.
     * @param orientationIndex The index (0-3) of the target orientation to solve for.
     * @return A formatted string with the solution, or " error" if none is found.
     */
    private static String solve(String scramble, int orientationIndex) {
        // --- Define Constants ---
        final int NUM_SUB_CASES = 3;

        // --- 1. Initialize Coordinates for the 3 Sub-Cases ---
        int cornerTwistCoord = 0;

        // Arrays to hold the coordinates for each of the 3 potential solution paths.
        int[] edgePermCoords = SOLVED_EDGE_PERMUTATIONS.clone();
        int[] edgeOrientCoords = {0, 12, 16};

        // --- 2. Apply the Scramble ---
        // Apply each move of the scramble to the corner twist and all 3 edge coordinate sets.
        String[] scrambleMoves = scramble.split(" ");
        for (String move : scrambleMoves) {
            if (!move.isEmpty()) {
                int faceIndex = MOVE_MAPS[orientationIndex].indexOf(move.charAt(0));
                if (faceIndex < 0) continue; // Skip invalid moves for this orientation

                // Apply the move 1 or 2 times based on the suffix (' or default).
                for (int i = 0; i < (move.length() > 1 ? 2 : 1); i++) {
                    cornerTwistCoord = moveTable_CornerTwist[cornerTwistCoord][faceIndex];
                    for (int j = 0; j < NUM_SUB_CASES; j++) {
                        edgePermCoords[j] = moveTable_EdgePerm[edgePermCoords[j]][faceIndex];
                        edgeOrientCoords[j] = moveTable_EdgeOrient[edgeOrientCoords[j]][faceIndex];
                    }
                }
            }
        }
        // --- 3. Iterative Deepening Search ---
        // Search for a solution, checking all 3 sub-cases at each depth.

        for (int searchDepth = 0; searchDepth < MAX_SOLUTION_DEPTH; searchDepth++) {
            for (int subcaseIndex = 0; subcaseIndex < NUM_SUB_CASES; subcaseIndex++) {
                if (search(
                        edgePermCoords[subcaseIndex],
                        edgeOrientCoords[subcaseIndex],
                        cornerTwistCoord,
                        SOLVED_EDGE_PERMUTATIONS[subcaseIndex],
                        searchDepth,
                        INITIAL_LAST_MOVE
                )) {
                    // --- 4. Format Solution ---
                    // If a solution is found for any sub-case, format and return it.
                    StringBuilder solutionBuilder = new StringBuilder();
                    for (int i = searchDepth; i > 0; i--) {
                        int moveCode = solutionSequence[i];
                        int face = moveCode / 2;
                        int turnType = moveCode % 2;
                        solutionBuilder.append(' ')
                                       .append(MOVE_MAPS[orientationIndex].charAt(face))
                                       .append(SUFFIXES[turnType]);
                    }
                    return solutionBuilder.toString();
                }

            }
        }
        return " error"; // If no solution is found.
    }

    /**
     * The recursive IDA* search function for the Pyraminx V-Solver.
     *
     * @param edgePermCoord The current coordinate for the 2-edge permutation.
     * @param edgeOrientCoord The current coordinate for the 2-edge orientation.
     * @param cornerTwistCoord The current coordinate for the 3-corner twist.
     * @param targetPermCoord The target "solved" coordinate for the edge permutation,
     * which varies depending on the sub-case being solved.
     * @param depthRemaining The number of moves left in the current search path.
     * @param lastMove The index of the last move made, to avoid redundant moves.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean search(int edgePermCoord, int edgeOrientCoord, int cornerTwistCoord,
                                  int targetPermCoord, int depthRemaining, int lastMove) {
        // --- Base Case: If no moves are left, check if we've reached the target solved state. ---
        if (depthRemaining == 0) {
            return edgePermCoord == targetPermCoord &&
                    edgeOrientCoord == targetPermCoord * 2 &&
                    cornerTwistCoord == SOLVED_CORNER_TWIST_COORD;
        }

        // --- Heuristic Pruning ---
        // Check the combined pruning table. If the state requires more moves than we
        // have left, this entire path is a dead end.
        if (pruningTable[cornerTwistCoord * NUM_2_EDGE_PO * NUM_2_EDGE_COMB +
                edgePermCoord * NUM_2_EDGE_PO + edgeOrientCoord % NUM_MOVES] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore all valid next moves. ---
        for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
            if (moveIndex != lastMove) {
                int nextEdgePerm = edgePermCoord;
                int nextEdgeOrient = edgeOrientCoord;
                int nextCornerTwist = cornerTwistCoord;

                // Try both turn types for the current face (e.g., L and L').
                for (int turnType = 0; turnType < NUM_TURNS_TYPE; turnType++) {
                    // Get the next state for all three coordinates from their respective move tables.
                    nextEdgePerm = moveTable_EdgePerm[nextEdgePerm][moveIndex];
                    nextEdgeOrient = moveTable_EdgeOrient[nextEdgeOrient][moveIndex];
                    nextCornerTwist = moveTable_CornerTwist[nextCornerTwist][moveIndex];

                    // Make the recursive call for the new state.
                    if (search(nextEdgePerm, nextEdgeOrient, nextCornerTwist, targetPermCoord, depthRemaining - 1, moveIndex)) {
                        // --- Solution Found! ---
                        // Record the successful move in the solution sequence array.
                        solutionSequence[depthRemaining] = moveIndex * NUM_TURNS_TYPE + turnType;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    //</editor-fold>

    //<editor-fold desc="Internal Table Generation Helpers">
    /**
     * Calculates the new coordinate for a 2-edge state after a given move.
     * <p>
     * This is the "Unpack -> Apply Move -> Repack" engine for the Stage V
     * 2-edge coordinate system.
     *
     * @param combinationIndex The starting combination coordinate (which 2 of 6 slots are occupied).
     * @param permutationIndex The starting permutation coordinate of the 2 tracked edges.
     * @param orientationIndex The starting orientation coordinate of the 2 tracked edges.
     * @param moveIndex The face turn to apply (0=L, 1=R, 2=B, 3=U).
     * @return The new packed coordinate after the move.
     */
    private static int getNewEdgeCoordinate(int combinationIndex, int permutationIndex,
                                            int orientationIndex, int moveIndex) {
        // --- Define Constants for this subsystem ---
        final int NUM_SLOTS = 6;
        final int NUM_PIECES = 2;

        // --- 1. Unpack Coordinates into a Physical Representation ---
        int[] edgeSlotArray = new int[NUM_SLOTS];
        int[] permutationArray = new int[NUM_PIECES];

        // Decode the permutation index and use the custom idxToComb helper to
        // place the two pieces into the 6 available slots.
        Utils.idxToPerm(permutationArray, permutationIndex, NUM_PIECES, false);
        idxToComb(edgeSlotArray, permutationArray, combinationIndex, orientationIndex);
        // --- 2. Apply the Physical Move ---
        // The move permutes the slots and flips the orientation of two edges.
        switch (moveIndex) {
            case 0:	// L-move
                Utils.circle(edgeSlotArray, 1, 5, 2);
                edgeSlotArray[2] ^= 1; edgeSlotArray[5] ^= 1;
                break;
            case 1: // R-move
                Utils.circle(edgeSlotArray, 0, 2, 4);
                edgeSlotArray[0] ^= 1; edgeSlotArray[2] ^= 1;
                break;
            case 2: // B-move
                Utils.circle(edgeSlotArray, 3, 4, 5);
                edgeSlotArray[3] ^= 1; edgeSlotArray[4] ^= 1;
                break;
            case 3: // U-move
                Utils.circle(edgeSlotArray, 0, 3, 1);
                edgeSlotArray[1] ^= 1; edgeSlotArray[3] ^= 1;
                break;
        }

        // --- 3. Repack the New State into a Single Coordinate ---
        int newCombinationIndex = 0;
        int newOrientationIndex = 0;
        int piecesToFind = NUM_PIECES;

        // Scan the 6 slots to find the new positions and orientations.
        for (int i = 0; i < NUM_SLOTS; i++) {
            if (edgeSlotArray[i] >= 0) {
                newCombinationIndex += Utils.Cnk[NUM_SLOTS - 1 - i][piecesToFind--];
                permutationArray[piecesToFind] = edgeSlotArray[i] >> 1;
                newOrientationIndex |= (edgeSlotArray[i] & 1) << 1 - piecesToFind;
            }
        }
        // Convert the new permutation array back to a compact index.
        int newPermutationIndex = Utils.permToIdx(permutationArray, NUM_PIECES, false);
        // Combine all new coordinates into a single integer and return it.
        return NUM_PIECES * newCombinationIndex + newPermutationIndex << 2 | newOrientationIndex;
    }

    /**
     * Unpacks coordinates into a physical representation of 2 edge pieces in 6 slots.
     */
    private static void idxToComb(int[] edgeSlotArray, int[] permutationArray, int combinationIndex, int orientationIndex) {
        // --- Define Constants for this subsystem ---
        final int NUM_SLOTS = 6;

        // The number of pieces we need to place in the slots.
        int piecesToPlace = 2;

        // Iterate through all 6 possible edge slots for this sub-problem.
        for (int slotIndex = 0; slotIndex < NUM_SLOTS; slotIndex++)
            if (combinationIndex >= Utils.Cnk[NUM_SLOTS - 1 - slotIndex][piecesToPlace]) {
                // This slot is occupied. Update the index.
                combinationIndex -= Utils.Cnk[NUM_SLOTS - 1 - slotIndex][piecesToPlace--];
                // Get the orientation for this specific piece (the last bit of the index).
                int orientation = orientationIndex & 1;
                // Place the piece, packing its permutation ID and its orientation bit together.
                edgeSlotArray[slotIndex] = permutationArray[piecesToPlace] << 1 | orientation;
                // Discard the orientation bit we just used.
                orientationIndex >>= 1;
            } else {
                // This slot is empty.
                edgeSlotArray[slotIndex] = UNVISITED_STATE;
            }
    }

    //</editor-fold>


}
