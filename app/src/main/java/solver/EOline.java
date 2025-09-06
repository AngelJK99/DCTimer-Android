package solver;

import static solver.Utils.turnSuffix;
import static solver.Utils.turn;

import java.util.Arrays;

/**
 * A solver module for the "EOLine" (Edge Orientation + Line) sub-problem.
 * This class pre-computes move tables and pruning tables to find optimal solutions
 * for orienting all 12 edges and solving a specific pair of opposite edges (the "line").
 */
public class EOline {
    //<editor-fold desc="Static Fields and Initializers">
    // Constants declaration
    private static final int NUM_CORNERS = 8;
    private static final int NUM_EDGES = 12;
    private static final int NUM_ORIENTATIONS_PER_EDGE = 2;
    private static final int NUM_ORIENTATIONS_PER_CORNER = 3;
    private static final int NUM_FACES = 6; // D U L R F B
    private static final int NUM_LINES_PER_FACE = 2;


    private static final int NUM_EDGES_ORIENTATIONS = (int) Math.pow(NUM_ORIENTATIONS_PER_EDGE,11); // 2^12/2

    private static final int NUM_LINE_EDGES_COMBINATIONS = 66; // C(12,2)
    private static final int NUM_LINE_EDGES_PERMUTATIONS = 2; // 2!
    private static final int NUM_LINE_EDGES_ORIENTATION = 1; //
    private static final int NUM_LINE_EDGES = NUM_LINE_EDGES_ORIENTATION * NUM_LINE_EDGES_PERMUTATIONS *
            NUM_LINE_EDGES_COMBINATIONS; //

    private static final int NUM_MAX_TURN = 3; // 0 = clockwise, 1 = double turn, 2 = counterclockwise

    private static final int SOLVED_LINE_EDGE_PERMUTATION = 106; //
    private static final int ALL_EDGES_ORIENTED_FLAG = 0; // The orientation bits in this coordinate must be zero.



    // ---Pruning tables and move tables for Edge Orientation (EO) and Edge Permutation (EP)---
    // Move table for the orientation of all 12 edges (2^11 = 2048 states).
    private static short[][] edgeOrientationMoveTable = new short[NUM_EDGES_ORIENTATIONS][NUM_FACES];

    // Move table for the permutation of the 2 "line" edges (C(12,2) * 2 = 132 states).
    private static short[][] lineEdgePermutationMoveTable = new short[NUM_LINE_EDGES][NUM_FACES]; // Edge Permutation Move table (for DF/DB edges). Index: [currentLineEdgePermutationState][move]

    // Pruning table for edge orientation distance.
    private static byte[] edgeOrientationDistanceTable = new byte[NUM_EDGES_ORIENTATIONS]; // Edge Orientation Distance (pruning table). Index: [edgeOrientationState]

    // Pruning table for the "line" edge permutation distance.
    private static byte[] lineEdgePermutationDistanceTable = new byte[NUM_LINE_EDGES]; // Edge Permutation Distance (pruning table for DF/DB edges). [edgePermutationState]

    // Array to store the sequence of moves for the current solution search
    private static int[] solutionMoveSequence = new int[10];

    // Names of the edge pairs forming the line (e.g., "DF DB"). Index corresponds to `face` parameter logic.
    private static String[] lineEdgePairNames = {"DF DB", "DL DR", "UF UB", "UL UR",
            "LF LB", "LU LD", "RF RB", "RU RD", "FU FD", "FL FR", "BU BD", "BL BR"};
    // Defines the order of face moves (U,D,L,R,F,B) for different cube orientations.
    protected static String[] moveStringPerOrientation = {"UDLRFB", "UDFBRL", "DURLFB", "DUFBLR",
            "RLUDFB", "RLFBDU", "LRDUFB", "LRFBUD", "BFLRUD", "BFUDRL", "FBLRDU", "FBDURL"};
    // Defines the whole cube rotation needed to bring a specific line to FR/BL.
    protected static String[] setupRotations = {"", "y", "z2", "z2 y", "z'", "z' y", "z", "z y", "x'", "x' y", "x", "x y"};

    /*
     * Static initializer block to precompute the move tables and pruning tables.
     * This is done once when the class is loaded.
     */
    static {
        // --- 1. Generate Edge Orientation Move Table (eom) ---
        int[] edgeOrientations = new int[NUM_EDGES];
        for (int i = 0; i < NUM_EDGES_ORIENTATIONS; i++) {
            for (int move = 0; move < NUM_FACES; move++) {
                Utils.idxToFlip(edgeOrientations, i, NUM_EDGES, true); // Convert index to edge orientation array
                Cross.applyMoveToEdgeArray(edgeOrientations, move); // Apply a move
                edgeOrientationMoveTable[i][move] = (short) Utils.flipToIdx(edgeOrientations, NUM_EDGES, true); // Convert back to index
            }
        }

        // --- 2. Generate Line Edge Permutation Move Table (epm) ---
        // This table tracks the position of two specific edges.
        for (int combIdx = 0; combIdx < NUM_LINE_EDGES_COMBINATIONS; combIdx++) { // 66 possible combinations for 2 edges out of 12
            for (int permIdx = 0; permIdx < NUM_LINE_EDGES_PERMUTATIONS; permIdx++) { // 2 permutations for the 2 edges
                for (int move = 0; move < NUM_FACES; move++) { // 6 possible moves
                    lineEdgePermutationMoveTable[combIdx * NUM_LINE_EDGES_PERMUTATIONS + permIdx][move] =
                            (short) getNewLineEdgePermutation(combIdx, permIdx, move);
                }
            }
        }

        // --- 3. Generate Edge Orientation Pruning Table (eod) ---
        Arrays.fill(lineEdgePermutationDistanceTable, (byte) -1); // -1 indicates unreachable or not yet calculated
        edgeOrientationDistanceTable[0] = 0; // Solved state has distance 0
        Utils.populatePruningTable(edgeOrientationDistanceTable, 7, edgeOrientationMoveTable, 3); // Populate pruning table (max depth 7 for EO)

        Arrays.fill(lineEdgePermutationDistanceTable, (byte) -1);
        lineEdgePermutationDistanceTable[106] = 0; // Index 106 represents the solved state for DF/DB edges (specific t
        Utils.populatePruningTable(lineEdgePermutationDistanceTable, 4, lineEdgePermutationMoveTable, 3); // Populate pruning table (max depth 4 for this EP subset)
    }

    //</editor-fold>

    //<editor-fold desc="Public Methods">

    /**
     * Solves the EOline for the given scramble and specified face(s).
     *
     * @param scramble The scramble string (e.g., "R U R' U'").
     * @param targetFaceBitmask     An integer where each bit represents a face to solve for.
     *                 For example, if the LSB is 1, it solves for DF/DB.
     *                 If the next bit is 1, it solves for DL/DR, and so on.
     * @return A string containing the EOline solutions for the specified faces.
     */
    public static String solveEOline(String scramble, int targetFaceBitmask) {
        StringBuilder solutions = new StringBuilder("\n");
        // Use a bitmask to solve for user-selected faces.
        for (int faceIndex = 0; faceIndex < NUM_FACES; faceIndex++) {
            if (((targetFaceBitmask >> faceIndex) & 1) != 0) {//
                // For each face, solve both possible axes (e.g., DF-DB and DL-DR for the D face).
                solutions.append(eoLine(scramble, faceIndex * NUM_LINES_PER_FACE)); // Solve for one axe
                solutions.append(eoLine(scramble, faceIndex * NUM_LINES_PER_FACE + 1)); // Solve for the other axe
            }
        }
        return solutions.toString();
    }

    //</editor-fold>

    //<editor-fold desc="Private Helper Methods">

    /**
     * Calculates a single entry for the line-edge-permutation move table.
     * <p>
     * This function performs one step in the pre-computation of a move table. It takes a
     * coordinate representing the state of two specific edges, simulates a single face turn,
     * and returns the new coordinate for the resulting state
     *
     * @param combinationIndex The current combination index of the two line edges (0-65).
     * @param permutationIndex The current permutation index of the two line edges (0-1).
     * @param moveIndex   The move to apply (0-5, representing U, D, L, R, F, B in some order).
     * @return The index of the next EP state after applying the move.
     */
    private static int getNewLineEdgePermutation(int combinationIndex,
                                                 int permutationIndex,
                                                 int moveIndex) {
        // --- 1. Unpack Coordinates into a Physical Representation ---

        // Decode the combination index to find which 2 of the 12 slots are occupied.
        int[] edgeSlotCombination = new int[NUM_EDGES]; // Represents which edge slots are occupied by
        Utils.idxToComb(edgeSlotCombination, combinationIndex, NUM_LINE_EDGES_PERMUTATIONS, NUM_EDGES); // Convert combination index to array

        // Decode the permutation index to find the arrangement of the two pieces.
        int[] piecePermutation = new int[NUM_LINE_EDGES_PERMUTATIONS]; // Represents the permutation of the two line edges
        Utils.idxToPerm(piecePermutation, permutationIndex, NUM_LINE_EDGES_PERMUTATIONS, false); // Convert permutation index to array

        // Define the specific edge piece IDs that this coordinate system tracks.
        byte[] trackedPieceIDs = {8, 10};  // Example: tracking edges 8 and 10

        // Create an array representing all 12 edge slots and place the two tracked pieces.
        int[] edgeSlotArray = { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 }; // Initialize all slots as empty.

        int pieceCounter = 0;
        for (int i = 0; i < NUM_EDGES; i++) {
            if (edgeSlotCombination[i] != 0) { // If this slot contains one of the line edges
                edgeSlotArray[i] = trackedPieceIDs[piecePermutation[pieceCounter++]]; // Place the permuted edge value here
            }
        }

        // --- 2. Apply the Physical Move ---
        // The move permutes the slots in the array, effectively moving our tracked pieces.
        switch (moveIndex) {
            case 0: Utils.circle(edgeSlotArray, 4, 7,  6,  5); break; // U face turn (example, actual mapping depends
            case 1: Utils.circle(edgeSlotArray, 8, 9, 10, 11); break; // D face turn
            case 2: Utils.circle(edgeSlotArray, 7, 3, 11,  2); break; // L face turn
            case 3: Utils.circle(edgeSlotArray, 5, 1,  9,  0); break; // R face turn
            case 4: Utils.circle(edgeSlotArray, 6, 2, 10,  1); break; // F face turn
            case 5: Utils.circle(edgeSlotArray, 4, 0,  8,  3); break; // B face turn
        }

        // --- 3. Repack the New State into a Single Coordinate ---

        // Find the new combination (positions) of the two tracked pieces.
        int[] newSlotCombination = new int[NUM_EDGES]; // New combination array
        for (int i = 0; i < NUM_EDGES; i++) {
            newSlotCombination[i] = edgeSlotArray[i] > 0 ? 1 : 0; // Mark slots occupied by the line edges
        }
        int newCombinationIndex = Utils.combToIdx(newSlotCombination, NUM_LINE_EDGES_PERMUTATIONS, NUM_EDGES); // Convert new combination array to index

        // Determine the new permutation of the two pieces.
        int[] newPiecePermutation = new int[NUM_LINE_EDGES_PERMUTATIONS]; // New permutation array
        // This map converts the global piece IDs (8, 10) back to local indices (0, 1) for the permutation index.
        byte[] pieceIdToLocalIndexMap = {0, 1, 2, 3}; // Only the first two indices are used.

        pieceCounter = 0;
        for (int i = 0; i < NUM_EDGES; i++) {
            if (newSlotCombination[i] != 0) { // If this slot is occupied by a line edge
                // Map the edge value (e.g., 8, 10) to a smaller index (0, 1) for permToIdx
                newPiecePermutation[pieceCounter++] = edgeSlotArray[i] > -1 ? pieceIdToLocalIndexMap[edgeSlotArray[i] - 8] : -1;
            }
        }
        int newPermutationIndex = Utils.permToIdx(newPiecePermutation, NUM_LINE_EDGES_PERMUTATIONS, false); // Convert new permutation array to index

        // Combine the new combination and permutation indices into a single return value.
        return newCombinationIndex * NUM_LINE_EDGES_PERMUTATIONS + newPermutationIndex;
    }

    /**
     * Performs a depth-first search (IDA*) to find an EOline solution.
     *
     * @param currentEdgeOrientation    The current Edge Orientation state index.
     * @param currentLineEdgePermutation    The current Edge Permutation state index for the line edges.
     * @param depthRemaining The current remaining depth allowed for the search.
     * @param lastFace     The last move made (to avoid redundant sequences like R R').
     * @return True if a solution is found within the current depth, false otherwise.
     */
    private static boolean searchForEOLine(int currentEdgeOrientation, int currentLineEdgePermutation,
                                           int depthRemaining, int lastFace) {
        if (depthRemaining == 0) {
            // Base case: If depth is 0, check if it's the solved state
            // EO solved state is 0. EP solved state for DF/DB is 106 (depends on indexing).
            return currentEdgeOrientation == ALL_EDGES_ORIENTED_FLAG && currentLineEdgePermutation == SOLVED_LINE_EDGE_PERMUTATION;
        }
        // Pruning: If the lower bound estimate from pruning tables is greater than remaining depth, prune this branch
        if (edgeOrientationDistanceTable[currentEdgeOrientation] > depthRemaining ||
                lineEdgePermutationDistanceTable[currentLineEdgePermutation] > depthRemaining) {
            return false;
        }

        // Try all 6 possible moves (U, D, L, R, F, B)
        for (int face = 0; face < NUM_FACES; face++)
            if (face != lastFace) { // Avoid applying the same move or its inverse immediately
                int nextEdgeOrientation  = currentEdgeOrientation; // Current EO state
                int nextLineEdgePermutation  = currentLineEdgePermutation; // Current EP state

                // Try 1, 2, or 3 turns of the current move (e.g., R, R2, R')
                for (int turnCount = 0; turnCount < NUM_MAX_TURN; turnCount++) {
                    nextEdgeOrientation  = edgeOrientationMoveTable[nextEdgeOrientation][face]; // Apply move i to EO state
                    nextLineEdgePermutation  = lineEdgePermutationMoveTable[nextLineEdgePermutation][face]; // Apply move i to EP state
                    if (searchForEOLine(
                            nextEdgeOrientation,
                            nextLineEdgePermutation,
                            depthRemaining - 1,
                            face
                    )) { // Recursive call
                        solutionMoveSequence[depthRemaining] = face * NUM_MAX_TURN + turnCount; // Store the move in the solution sequence
                        //sb.insert(0, " " + turn[i] + suff[j]); // Original way to build string (can be done later)
                        return true; // Solution found
                    }
                }
            }
        return false; // No solution found at this depth
    }

    /**
     * Finds the EOline solution for a given scramble and a specific pair of line edges.
     *
     * @param scramble The scramble string.
     * @param lineEdgePairIndex     An index representing the pair of line edges and their orientation
     *                 (e.g., 0 for DF DB, 1 for DB DF, 2 for DL DR, etc.).
     * @return A string representing the solution, or an error message if no solution is found.
     */
    private static String eoLine(String scramble, int lineEdgePairIndex) {
        String[] scrambleMoves = scramble.split(" "); // Split scramble into individual moves

        int currentLineEdgePermutation = SOLVED_LINE_EDGE_PERMUTATION; // Initial Edge Permutation state (solved for line edges)
        int currentEdgeOrientation = ALL_EDGES_ORIENTED_FLAG;  // Initial Edge Orientation state (all edges oriented)

        // Apply the scramble to the initial EO and EP states
        // The 'face' parameter determines how the scramble moves are interpreted based on
        for (String move : scrambleMoves) {
            if (!move.isEmpty()) {
                int faceIndex = moveStringPerOrientation[lineEdgePairIndex].indexOf(move.charAt(0)); // Get the move index based on current orientation

                for(int i = 0; i < (move.length() > 1 && move.charAt(1) == '\'' ? 3 :
                        (move.length() > 1 && move.charAt(1) == '2' ? 2 : 1)); i++) {
                    currentLineEdgePermutation = lineEdgePermutationMoveTable[currentLineEdgePermutation][faceIndex];
                    currentEdgeOrientation = edgeOrientationMoveTable[currentEdgeOrientation][faceIndex];
                }
            }
        }

        // Use iterative deepening to find the shortest solution.
        for (int depth = 0; depth < 10; depth++) { // Max depth of 9 (0 to 9)
            if (searchForEOLine(currentEdgeOrientation, currentLineEdgePermutation, depth, -1)) { // Call the search function
                StringBuilder solutionString = new StringBuilder();
                // Reconstruct the solution string from the sequence array
                for (int j = depth; j > 0; j--) { // Reconstruct solution from sequence
                    int moveCode = solutionMoveSequence[j];
                    int moveAxis = moveCode / 3;
                    int turnType = moveCode % 3; // 0=', 1=2, 2='
                    solutionString.append(' ')
                            .append(turn[moveAxis])
                            .append(turnSuffix[turnType]);
                }
                // Prepend rotation and edge pair info
                return "\n" + lineEdgePairNames[lineEdgePairIndex] + ": " + setupRotations[lineEdgePairIndex] + solutionString.toString();
            }
        }
        return "\nError: No solution found for " + lineEdgePairNames[lineEdgePairIndex];  // Should ideally always find a solution within max depth
    }
    //</editor-fold>
}
