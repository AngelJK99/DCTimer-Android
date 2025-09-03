package solver;

import static solver.Utils.suff;
import static solver.Utils.turn;

/**
 * Represents the EOline (Edge Orientation and Line) solver for a Rubik's Cube.
 * This class provides methods to find solutions for the EOline step, which involves
 * orienting all edges correctly and placing the DF and DB (or other specified pair)
 * edges correctly relative to each other.
 */
public class EOline {
    //<editor-fold desc="Static Fields and Initializers">

    // ---Pruning tables and move tables for Edge Orientation (EO) and Edge Permutation (EP)---
    private static short[][] edgeOrientationMoveTable = new short[2048][6]; // Edge Orientation Move table. Index: [currentEdgeOrientationState][move]
    private static short[][] lineEdgePermutationMoveTable = new short[132][6]; // Edge Permutation Move table (for DF/DB edges). Index: [currentLineEdgePermutationState][move]
    private static byte[] edgeOrientationDistanceTable = new byte[2048]; // Edge Orientation Distance (pruning table). Index: [edgeOrientationState]
    private static byte[] edgePermutationDistanceTable = new byte[132]; // Edge Permutation Distance (pruning table for DF/DB edges). [edgePermutationState]

    // Array to store the sequence of moves for the current solution search
    private static int[] solutionMoveSequence = new int[10];

    // ---String arrays for user-friendly output---
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
        // Initialize Edge Orientation Move table (edgeOrientationMoveTable)
        int[] edgeOrientations = new int[12];
        for (int i = 0; i < 2048; i++) {
            for (int j = 0; j < 6; j++) {
                Utils.idxToFlip(edgeOrientations, i, 12, true); // Convert index to edge orientation array
                Cross.applyMoveToEdgeArray(edgeOrientations, j); // Apply a move
                edgeOrientationMoveTable[i][j] = (short) Utils.flipToIdx(edgeOrientations, 12, true); // Convert back to index
            }
        }

        // Initialize Edge Permutation Move table (epm) for the two specific line edges (e.g., DF, DB)
        for (int combIdx = 0; combIdx < 66; combIdx++) // 66 possible combinations for 2 edges out of 12
            for (int permIdx = 0; permIdx < 2; permIdx++) // 2 permutations for the 2 edges
                for (int move = 0; move < 6; move++) // 6 possible moves
                    lineEdgePermutationMoveTable[combIdx * 2 + permIdx][move] = (short) calculateNextLineEdgePermutationState(combIdx, permIdx, move);

        // Initialize Edge Orientation Distance (pruning) table (eod)
        for (int i = 1; i < 2048; i++) edgeOrientationDistanceTable[i] = -1; // -1 indicates unreachable or not yet calculated
        edgeOrientationDistanceTable[0] = 0; // Solved state has distance 0
        Utils.createPrun(edgeOrientationDistanceTable, 7, edgeOrientationMoveTable, 3); // Populate pruning table (max depth 7 for EO)

        for (int i = 0; i < 132; i++) edgePermutationDistanceTable[i] = -1;
        edgePermutationDistanceTable[106] = 0; // Index 106 represents the solved state for DF/DB edges (specific t
        Utils.createPrun(edgePermutationDistanceTable, 4, lineEdgePermutationMoveTable, 3); // Populate pruning table (max depth 4 for this EP subset)
    }

    //</editor-fold>

    //<editor-fold desc="Public Methods">

    /**
     * Solves the EOline for the given scramble and specified face(s).
     *
     * @param scramble The scramble string (e.g., "R U R' U'").
     * @param targetLineBitmask     An integer where each bit represents a face to solve for.
     *                 For example, if the LSB is 1, it solves for DF/DB.
     *                 If the next bit is 1, it solves for DL/DR, and so on.
     * @return A string containing the EOline solutions for the specified faces.
     */
    public static String solveEOline(String scramble, int targetLineBitmask) {
        StringBuilder solutions = new StringBuilder("\n");
        // Iterate through the 6 possible pairs of line edges (e.g., DF/DB, DL/DR, etc.)
        for (int i = 0; i < 6; i++) {
            if (((targetLineBitmask >> i) & 1) != 0) {// Check if the current face (pair of edges) is requested
                solutions.append(findEOLineSolution(scramble, i * 2)); // Solve for one orientation
                solutions.append(findEOLineSolution(scramble, i * 2 + 1)); // Solve for the other orientation
            }
        }
        return solutions.toString();
    }

    //</editor-fold>

    //<editor-fold desc="Private Helper Methods">

    /**
     * Calculates the next Edge Permutation (EP) state for the line edges given the current state and a move.
     * This method is specifically for the two edges that form the "line" (e.g., DF and DB).
     *
     * @param currentCombinationIndex The current combination index of the two line edges (0-65).
     * @param currentPermutationIndex The current permutation index of the two line edges (0-1).
     * @param moveIndex   The move to apply (0-5, representing U, D, L, R, F, B in some order).
     * @return The index of the next EP state after applying the move.
     */
    private static int calculateNextLineEdgePermutationState(int currentCombinationIndex, int currentPermutationIndex, int moveIndex) {
        int[] edgeSlots = new int[12]; // Represents which edge slots are occupied by
        Utils.idxToComb(edgeSlots, currentCombinationIndex, 2, 12); // Convert combination index to array

        int[] lineEdgeOrder = new int[2]; // Represents the permutation of the two line edges
        Utils.idxToPerm(lineEdgeOrder, currentPermutationIndex, 2, false); // Convert permutation index to array

        // The actual edges being tracked (e.g., 8 could be DF, 10 could be DB, depends on internal mapping)
        byte[] lineEdges = {8, 10};  // Example: tracking edges 8 and 10

        int next = 0;
        int[] currentEdgePositions = { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 }; // Stores the positions of the selected edges
        for (int i = 0; i < 12; i++) {
            if (edgeSlots[i] != 0) { // If this slot contains one of the line edges
                currentEdgePositions[i] = lineEdges[lineEdgeOrder[next++]]; // Place the permuted edge value here
            }
        }

        // Apply the move k to the edge permutation array 'ep'
        // Utils.circle permutes elements within the array based on the move
        switch (moveIndex) {
            case 0: Utils.circle(currentEdgePositions, 4, 7,  6,  5); break; // U face turn (example, actual mapping depends
            case 1: Utils.circle(currentEdgePositions, 8, 9, 10, 11); break; // D face turn
            case 2: Utils.circle(currentEdgePositions, 7, 3, 11,  2); break; // L face turn
            case 3: Utils.circle(currentEdgePositions, 5, 1,  9,  0); break; // R face turn
            case 4: Utils.circle(currentEdgePositions, 6, 2, 10,  1); break; // F face turn
            case 5: Utils.circle(currentEdgePositions, 4, 0,  8,  3); break; // B face turn
        }

        // After the move, convert the new edge positions back to combination and permutation indices
        byte[] permMap = {0, 1, 2, 3}; // Mapping if selectedEdges were more than 2, used for permToIdx
        int[] newEdgeSlots = new int[12]; // New combination array
        for (int i = 0; i < 12; i++) {
            newEdgeSlots[i] = currentEdgePositions[i] > 0 ? 1 : 0; // Mark slots occupied by the line edges
        }
        int newCombinationIndex = Utils.combToIdx(newEdgeSlots, 2, 12); // Convert new combination array to index

        int[] newLineEdgeOrder = new int[2]; // New permutation array
        next = 0;
        for (int i = 0; i < 12; i++) {
            if (newEdgeSlots[i] != 0) { // If this slot is occupied by a line edge
                // Map the edge value (e.g., 8, 10) to a smaller index (0, 1) for permToIdx
                newLineEdgeOrder[next++] = currentEdgePositions[i] > -1 ? permMap[currentEdgePositions[i] - 8] : -1;
            }
        }
        int newPermutationIndex = Utils.permToIdx(newLineEdgeOrder, 2, false); // Convert new permutation array to index

        return newCombinationIndex * 2 + newPermutationIndex; // Combine combination and permutation indices into a single state index
    }

    /**
     * Performs a depth-first search (IDA*) to find an EOline solution.
     *
     * @param currentEOState    The current Edge Orientation state index.
     * @param currentLineEPState    The current Edge Permutation state index for the line edges.
     * @param depthRemaining The current remaining depth allowed for the search.
     * @param lastMoveAxis     The last move made (to avoid redundant sequences like R R').
     * @return True if a solution is found within the current depth, false otherwise.
     */
    private static boolean searchForSolution(int currentEOState, int currentLineEPState,
                                             int depthRemaining, int lastMoveAxis) {
        if (depthRemaining == 0) {
            // Base case: If depth is 0, check if it's the solved state
            // EO solved state is 0. EP solved state for DF/DB is 106 (depends on indexing).
            return currentEOState == 0 && currentLineEPState == 106;
        }
        // Pruning: If the lower bound estimate from pruning tables is greater than remaining depth, prune this branch
        if (edgeOrientationDistanceTable[currentEOState] > depthRemaining ||
                edgePermutationDistanceTable[currentLineEPState] > depthRemaining) {
            return false;
        }

        // Try all 6 possible moves (U, D, L, R, F, B)
        for (int moveAxis = 0; moveAxis < 6; moveAxis++)
            if (moveAxis != lastMoveAxis) { // Avoid applying the same move or its inverse immediately
                int nextEOState = currentEOState; // Current EO state
                int nextLineEPState = currentLineEPState; // Current EP state

                // Try 1, 2, or 3 turns of the current move (e.g., R, R2, R')
                for (int turnCount = 0; turnCount < 3; turnCount++) {
                    nextEOState = edgeOrientationMoveTable[nextEOState][moveAxis]; // Apply move i to EO state
                    nextLineEPState = lineEdgePermutationMoveTable[nextLineEPState][moveAxis]; // Apply move i to EP state
                    if (searchForSolution(nextEOState, nextLineEPState, depthRemaining - 1, moveAxis)) { // Recursive call
                        solutionMoveSequence[depthRemaining] = moveAxis * 3 + turnCount; // Store the move in the solution sequence
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
     * @param orientationIndex     An index representing the pair of line edges and their orientation
     *                 (e.g., 0 for DF DB, 1 for DB DF, 2 for DL DR, etc.).
     * @return A string representing the solution, or an error message if no solution is found.
     */
    private static String findEOLineSolution(String scramble, int orientationIndex) {
        String[] scrambleMoves = scramble.split(" "); // Split scramble into individual moves
        int currentLineEPState = 106; // Initial Edge Permutation state (solved for line edges)
        int currentEOState = 0;  // Initial Edge Orientation state (all edges oriented)

        // Apply the scramble to the initial EO and EP states
        // The 'face' parameter determines how the scramble moves are interpreted based on
        for (String move : scrambleMoves) {
            if (!move.isEmpty()) {
                int moveAxis = moveStringPerOrientation[orientationIndex].indexOf(move.charAt(0)); // Get the move index based on current orientation
                if (moveAxis == -1) continue; // Should not happen with valid scramble

                currentLineEPState = lineEdgePermutationMoveTable[currentLineEPState][moveAxis]; // Apply move to EP
                currentEOState = edgeOrientationMoveTable[currentEOState][moveAxis]; // Apply move to EO

                if (move.length() > 1) { // Check for double (e.g., R2) or inverse (e.g., R') moves
                    currentEOState = edgeOrientationMoveTable[currentEOState][moveAxis];
                    currentLineEPState = lineEdgePermutationMoveTable[currentLineEPState][moveAxis]; // Apply second turn for R2
                    if (move.charAt(1) == '\'') { // If it's an inverse move (R')
                        currentEOState = edgeOrientationMoveTable[currentEOState][moveAxis];
                        currentLineEPState = lineEdgePermutationMoveTable[currentLineEPState][moveAxis]; // Apply third turn for R' (equivalent to R R R)
                    }
                }
            }
        }

        // Iteratively deepen the search for a solution
        for (int depth = 0; depth < 10; depth++) { // Max depth of 9 (0 to 9)
            if (searchForSolution(currentEOState, currentLineEPState, depth, -1)) { // Call the search function
                StringBuilder solutionString = new StringBuilder();
                // Reconstruct the solution string from the sequence array
                for (int j = depth; j > 0; j--) { // Reconstruct solution from sequence
                    solutionString.append(' ').append(turn[solutionMoveSequence[j] / 3]).append(suff[solutionMoveSequence[j] % 3]);
                }
                // Prepend rotation and edge pair info
                return "\n" + lineEdgePairNames[orientationIndex] + ": " + setupRotations[orientationIndex] + solutionString.toString();
            }
        }
        return "\nError: No solution found for " + lineEdgePairNames[orientationIndex];  // Should ideally always find a solution within max depth
    }
    //</editor-fold>
}
