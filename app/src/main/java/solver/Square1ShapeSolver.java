package solver;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * A solver for the shape-shifting stage of the Square-1 puzzle.
 * <p>
 * This class uses coordinate systems to represent the unique shapes of the puzzle.
 * It pre-computes lookup tables to find optimal solutions for two main goals:
 * <ul>
 * <li>Transforming any shape back into the cube shape.</li>
 * <li>Solving the corner and edge swaps once in cube shape.</li>
 * </ul>
 */
public class Square1ShapeSolver {

    //<editor-fold desc="Constants & Class Variables">
    // --- Constants ---
    // The solved state coordinate for the cube shape.
    private static int SOLVED_SHAPE_COORD = 7191405;
    // The number of unique shapes the Square-1 can form.
    private static final int NUM_SHAPES = 3678;
    private static  final int NUM_HALF_LAYER_SHAPES = 13;
    // Total possible combinations of 4 quadrants: 13^4 = 28561
    private static final int TOTAL_QUADRANT_COMBINATIONS = 28561;
    private static final int MAX_PRUNING_DEPTH = 14;
    private static final int MAX_PRUNING_TWISTING_DEPTH = 7;

    private static final int NUM_LAYER_ROTATIONS = 11; // A layer can be rotated 11 times before repeating.
    private static final int UNVISITED_STATE = -1;
    private static final int SOLVED_STATE_DISTANCE = 0;
    private static final int INITIAL_LAST_MOVE = -1;

    // --- Lookup Tables ---
    // A list of the 13 possible half-layer configurations.
    private static int[] HALF_LAYER_SHAPES = {0x15, 0x17, 0x1B, 0x1D, 0x1F, 0x2B, 0x2D, 0x2F,
            0x35, 0x37, 0x3B, 0x3D, 0x3F};
    // Stores the 3678 valid shape coordinates for quick lookup.
    private static int[] shapeCoordinates = new int[NUM_SHAPES];
    // Pruning table for transforming a shape back to the cube shape.
    private static byte[] prunTable_ShapeToCube = new byte[NUM_SHAPES];
    // Pruning table for solving the puzzle once it is in cube shape.
    private static byte[] prunTable_Twist = new byte[NUM_SHAPES];
    // --- Solver State ---
    private static int[] solutionSequence = new int[24];
    private static int solutionLength;
    private static boolean isInitialized_base = false;
    private static boolean isInitialized_shapeToCube = false;
    private static boolean isInitialized_twist = false;

    //</editor-fold>

    //<editor-fold desc="Initialization">
    /* Static initializer to generate the base shape data when the class is loaded. */
    static {
        initializeBaseShapes();
    }

    /**
     * Initializes the list of all 3,678 valid Square-1 shapes.
     * <p>
     * This method iterates through all possible combinations of the four puzzle
     * quadrants (Up-Left, Up-Right, Down-Left, Down-Right), validates them,
     * and stores the integer coordinates of the valid shapes in a lookup table.
     */
    static void initializeBaseShapes() {
        // A guard to ensure this computation is only run once.
        if (isInitialized_base) {
            return;
        }

        // --- Define Constants for Shape Generation ---

        // A valid shape must have a specific bit count (representing 8 corners and 8 edges).
        final int VALID_SHAPE_BIT_COUNT = 16;
        // Bit shift amounts for packing the four 6-bit quadrant shapes into one integer.
        final int UP_LEFT_SHIFT = 18;
        final int UP_RIGHT_SHIFT = 12;
        final int DOWN_LEFT_SHIFT = 6;

        int validShapeCount = 0;
        // Iterate through all possible ways to combine the 13 half-layer shapes.
        for (int i = 0; i < TOTAL_QUADRANT_COMBINATIONS; i++) {
            // Unpack the counter 'i' to get four quadrant shapes.
            int downRightQuadrant = HALF_LAYER_SHAPES[i % NUM_HALF_LAYER_SHAPES];
            int downLeftQuadrant = HALF_LAYER_SHAPES[i / NUM_HALF_LAYER_SHAPES % NUM_HALF_LAYER_SHAPES];
            int upRightQuadrant = HALF_LAYER_SHAPES[i / NUM_HALF_LAYER_SHAPES / NUM_HALF_LAYER_SHAPES % NUM_HALF_LAYER_SHAPES];
            int upLeftQuadrant = HALF_LAYER_SHAPES[i / NUM_HALF_LAYER_SHAPES / NUM_HALF_LAYER_SHAPES / NUM_HALF_LAYER_SHAPES];

            // Combine the four 6-bit quadrant shapes into a single 24-bit integer.
            int packedShape = upLeftQuadrant << UP_LEFT_SHIFT |
                              upRightQuadrant << UP_RIGHT_SHIFT |
                              downLeftQuadrant << DOWN_LEFT_SHIFT |
                              downRightQuadrant;

            // Validate the shape. A physically possible shape must have a total of
            // 8 corner pieces and 8 edge pieces, which corresponds to a bit count of 16.
            if (Integer.bitCount(packedShape) == VALID_SHAPE_BIT_COUNT) {
                shapeCoordinates[validShapeCount++] = packedShape;
            }
        }
        isInitialized_base = true;
    }

    /**
     * Initializes the pruning table for the shape-to-cube solving phase.
     * <p>
     * This method uses a Breadth-First Search (BFS) to populate a pruning table.
     * The table stores the minimum number of moves required to transform any of the
     * 3,678 possible shapes back into the solved cube shape.
     */
    static void initializeShapeToCubePruning() {
        // A guard to ensure this heavy computation is only run once.
        if (isInitialized_shapeToCube) {
            return;
        }

        // --- 1. Setup ---
        // Initialize the entire table with an "unvisited" marker.
        Arrays.fill(prunTable_ShapeToCube, (byte) UNVISITED_STATE);

        // Set the distance of the solved cube shape to 0.
        prunTable_ShapeToCube[getShape2Idx(SOLVED_SHAPE_COORD)] = SOLVED_STATE_DISTANCE;

        int statesFound = 1;

        // --- 2. Breadth-First Search (BFS) to Populate Table ---
        for (int currentDepth = 0; currentDepth < MAX_PRUNING_DEPTH; currentDepth++) {
            // Scan all possible shapes to find the ones at the current search depth.
            for (int shapeIndex = 0; shapeIndex < NUM_SHAPES; shapeIndex++) {
                if (prunTable_ShapeToCube[shapeIndex] == currentDepth) {
                    int currentState = shapeCoordinates[shapeIndex];

                    // --- A. Explore the Twist move ("/") ---
                    if (canTwist(currentState)) {
                        int nextState = twist(currentState);
                        int nextIndex = getShape2Idx(nextState);
                        if (prunTable_ShapeToCube[nextIndex] == UNVISITED_STATE) {
                            prunTable_ShapeToCube[nextIndex] = (byte) (currentDepth + 1);
                            statesFound++;
                        }
                    }

                    // --- B. Explore all Top Layer rotations ---
                    int nextTopState = currentState;
                    for (int i = 0; i < NUM_LAYER_ROTATIONS; i++) {
                        nextTopState = rotateTopLayer(nextTopState);
                        // After each rotation, check if a twist is possible.
                        if (canTwist(nextTopState)) {
                            int temp=getShape2Idx(nextTopState);
                            if (prunTable_ShapeToCube[temp] == UNVISITED_STATE) {
                                prunTable_ShapeToCube[temp] = (byte) (currentDepth + 1);
                                statesFound++;
                            }
                        }
                    }
                    // --- C. Explore all Bottom Layer rotations ---
                    int nextBottomState = currentState;
                    for (int i = 0; i < NUM_LAYER_ROTATIONS; i++) {
                        nextBottomState = rotateBottomLayer(nextBottomState);
                        if (canTwist(nextBottomState)) {
                            int nextIndex = getShape2Idx(nextBottomState);
                            if (prunTable_ShapeToCube[nextIndex] == UNVISITED_STATE) {
                                prunTable_ShapeToCube[nextIndex] = (byte) (currentDepth + 1);
                                statesFound++;
                            }
                        }
                    }
                }
            }
            //Log.w("sq", d+1+"\t"+tol);
        }
        isInitialized_shapeToCube = true;
    }

    /**
     * Initializes the pruning table for the final twist/solve phase.
     * <p>
     * This method uses a Breadth-First Search (BFS) to populate a pruning table.
     * The table stores the minimum number of moves required to solve the puzzle
     * from any state that is already in the cube shape.
     */
    static void initializeTwistPruning() {
        // A guard to ensure this heavy computation is only run once.
        if (isInitialized_twist) {
            return;
        }

        // --- Define Constants ---
        Arrays.fill(prunTable_Twist, (byte) UNVISITED_STATE);

        // Set the distance of the four initial solved/target states to 0.
        prunTable_Twist[1170] = SOLVED_STATE_DISTANCE;
        prunTable_Twist[1192] = SOLVED_STATE_DISTANCE;
        prunTable_Twist[2640] = SOLVED_STATE_DISTANCE;
        prunTable_Twist[2662] = SOLVED_STATE_DISTANCE;

        int statesFound = 4;

        // --- 2. Breadth-First Search (BFS) to Populate Table ---
        for (int currentDepth = 0; currentDepth < MAX_PRUNING_TWISTING_DEPTH; currentDepth++) {
            //int count = 0;
            for (int shapeIndex = 0; shapeIndex < NUM_SHAPES; shapeIndex++) {
                if (prunTable_Twist[shapeIndex] == currentDepth) {
                    // --- Explore all possible moves from the current state ---
                    int nextState = twist(shapeCoordinates[shapeIndex]);
                    int nextIndex = getShape2Idx(nextState);

                    // This check is a bit unusual. It seems to apply a twist and then
                    // explore all layer rotations from that new state.
                    if (prunTable_Twist[nextIndex] == UNVISITED_STATE) {
                        prunTable_Twist[nextIndex] = (byte) (currentDepth + 1);
                        statesFound++;
                        //count++;
                        // Explore all U/D rotations reachable after the initial twist.
                        for (int topTurns = 0; topTurns < NUM_HALF_LAYER_SHAPES; topTurns++) {
                            for (int bottomTurns = 0; bottomTurns < NUM_HALF_LAYER_SHAPES; bottomTurns++) {
                                if (canTwist(nextState)) {
                                    int tempIndex = getShape2Idx(nextState);
                                    if (prunTable_Twist[tempIndex] == UNVISITED_STATE) {
                                        prunTable_Twist[tempIndex] = (byte) (currentDepth + 1);
                                        statesFound++;
                                    }
                                }
                                nextState = rotateBottomLayer(nextState);
                            }
                            nextState = rotateTopLayer(nextState);
                        }
                    }
                }
                //Log.w("sq", d+1+"\t"+tol);
            }//System.out.println(d+1+" "+count);
        }
        isInitialized_twist = true;
    }
    //</editor-fold>

    //<editor-fold desc="Public API">
    /**
     * Main public method to solve a scrambled Square-1 shape.
     * <p>
     * This function takes a scramble string, applies it to a solved state, and then
     * dispatches the resulting state to the appropriate solver based on the solve type.
     *
     * @param solveType  The type of solve to perform:
     * <ul>
     * <li>1: Solve the shape back to a cube (shape-to-cube).</li>
     * <li>2: Solve the final corner/edge permutation (twist phase).</li>
     * <li>0: No solve needed.</li>
     * </ul>
     * @param scramble A scramble string in "(top,bottom) /" format.
     * @return A formatted string with the solution moves.
     */
    public static String solve(int solveType, String scramble) {

        // --- Define Constants for Solve Types ---
        final int NO_SOLVE = 0;
        final int SHAPE_TO_CUBE_SOLVE = 1;

        if (solveType == NO_SOLVE) {
            return "";
        }
        // 1. Apply the scramble to a solved state to get the starting coordinate.
        int startState = applySequence(scramble.split(" "));
        // 2. Call the appropriate sub-solver based on the requested type.
        if (solveType == SHAPE_TO_CUBE_SOLVE) {
            // Find the moves to transform the puzzle back into a cube.
            return solveShapeToCube(startState);
        }

        // Find the moves to solve the corner/edge permutation.
        return solveTwist(startState);
    }

    /**
     * Applies a sequence of moves to a solved state to find the resulting shape coordinate.
     *
     * @param moveSequence An array of moves in "(top,bottom)" and "/" format.
     * @return The final integer coordinate of the resulting shape.
     */
    public static int applySequence(String[] moveSequence) {
        // Start with the coordinate of the solved cube shape.
        int currentState = SOLVED_SHAPE_COORD;

        // Sequentially apply each move in the array.
        for (String move : moveSequence) {
            currentState = applyMove(currentState, move);
        }

        // Return the final coordinate after all moves have been applied.
        return currentState;
    }

    /**
     * Applies a single move to a given shape state.
     * <p>
     * This method parses a move string, which can either be a slice move ("/")
     * or a layer turn "(top,bottom)", and applies the corresponding transformation
     * to the provided state coordinate.
     *
     * @param currentState The starting shape coordinate.
     * @param moveString The move to apply, e.g., "(3,-1)" or "/".
     * @return The resulting shape coordinate after the move.
     */
    public static int applyMove(int currentState, String moveString) {
        // Return immediately if the move string is empty.
        if (moveString.isEmpty()) {
            return currentState;
        }
        // --- Case 1: Handle the Slice Move ---
        if (moveString.equals("/")) {
            currentState = twist(currentState);
        }
        // --- Case 2: Handle a Layer Turn ---
        else {
            // Use a regular expression to parse the "(top,bottom)" notation.
            Pattern pattern = Pattern.compile("\\((-?\\d+),(-?\\d+)\\)");
            Matcher matcher = pattern.matcher(moveString);
            matcher.find();  // Find the first match in the string.

            // --- Apply Top Layer Turns ---
            // Parse the number of top layer turns from the first group in the regex match.
            int topTurns = Integer.parseInt(Objects.requireNonNull(matcher.group(1)));
            // Apply the rotation. The "+ 12" handles negative numbers to ensure the
            // loop count is always positive (e.g., -1 becomes 11
            for (int i = 0; i < topTurns + 12; i++) {
                currentState = rotateTopLayer(currentState);
            }
            // --- Apply Bottom Layer Turns ---
            // Parse the number of bottom layer turns from the second group.
            int bottomTurns = Integer.parseInt(Objects.requireNonNull(matcher.group(2)));
            // Apply the rotation.
            for (int i = 0; i < bottomTurns + 12; i++) {
                currentState = rotateBottomLayer(currentState);
            }
        }
        return currentState;
    }
    //</editor-fold>


    //<editor-fold desc="Internal Solver Logic">
    /**
     * Solves the puzzle from any non-cubic shape back to the cube shape.
     * <p>
     * This method uses a greedy search algorithm guided by the shape-to-cube pruning table.
     * At each step, it finds the move (twist or layer rotation) that leads to a state
     * that is exactly one step closer to the solved cube shape.
     *
     * @param startState The integer coordinate of the starting shape.
     * @return A formatted string with the solution moves.
     */
    private static String solveShapeToCube(int startState) {
        // --- Define Constants ---
        final int NUM_LAYER_ROTATIONS = 12; // 0 to 11 possible turn amounts
        final int HALF_ROTATION = 6;

        // Ensure the required pruning table is initialized.
        initializeShapeToCubePruning();
        //        int state = applySequence(scr.split(" "));
        int currentState = startState;
        StringBuilder solutionBuilder = new StringBuilder("\n\nftm: ");
        // --- Greedy Search Loop ---
        // Continue until the current state's distance to solved is 0.
        int currentIndex = getShape2Idx(currentState);

        while (prunTable_ShapeToCube[currentIndex] > 0) {
            int currentDistance = prunTable_ShapeToCube[currentIndex];

            // --- 1. Try a Twist Move ---
            // Check if a twist ("/") is the optimal next move.
            if (canTwist(startState)) {
                int nextState_twist = twist(currentState);
                int nextIndex_twist = getShape2Idx(nextState_twist);

                if (prunTable_ShapeToCube[nextIndex_twist] == prunTable_ShapeToCube[currentDistance] - 1) {
                    solutionBuilder.append("/ ");
                    currentState  = nextState_twist;
                    currentIndex = nextIndex_twist;
                    continue; // Move to the next step of the solution.

                }
            }
            // --- 2. Find the Optimal Layer Turn ---
            // If a twist wasn't optimal, find the correct (top, bottom) layer rotation.
            int topTurns = 0;
            int bottomTurns = 0;

            // A) Find the required top layer rotation.
            int nextState_top = currentState;
            for (int i = 0; i < NUM_LAYER_ROTATIONS; i++) {
                nextState_top = rotateTopLayer(nextState_top);
                int nextIndex = getShape2Idx(nextState_top);
                if (nextIndex >= 0 && prunTable_ShapeToCube[nextIndex] == currentDistance  - 1) {
                    topTurns = i;
                    currentState = nextState_top; // Apply the successful top turn
                    currentIndex = nextIndex;
                    break;
                }
            }
            // B) Find the required bottom layer rotation.
            int nextState_bottom = currentState;
            for (int j = 0; j < NUM_LAYER_ROTATIONS; j++) {
                nextState_bottom = rotateBottomLayer(nextState_bottom);

                int nextIndex = getShape2Idx(nextState_bottom);
                if (nextIndex >= 0 && prunTable_ShapeToCube[nextIndex] == prunTable_ShapeToCube[getShape2Idx(startState)] - 1) {
                    bottomTurns = j;
                    currentState = nextState_bottom;  // Apply the successful bottom turn
                    currentIndex = nextIndex;
                    break;
                }
            }

            // --- 3. Format and Append the Layer Turn ---
            if (topTurns != 0 || bottomTurns != 0) {
                // Convert turn counts (0-11) to standard notation (-5 to 6).
                int topNotation = (topTurns <= HALF_ROTATION) ? topTurns : topTurns - NUM_LAYER_ROTATIONS;
                int bottomNotation = (bottomTurns <= HALF_ROTATION) ? bottomTurns : bottomTurns - NUM_LAYER_ROTATIONS;
                solutionBuilder.append('(')
                        .append(topNotation).append(',')
                        .append(bottomNotation)
                        .append(") ");
            }
        }
        return solutionBuilder.toString();
    }

    /**
     * The main solver for the final twist/permutation phase of a Square-1 solve.
     * <p>
     * This method is called when the puzzle is already in a cube shape. It uses an
     * iterative deepening approach to call the recursive search function until the
     * shortest solution is found.
     *
     * @param startState The integer coordinate of the starting state (must be a cube shape).
     * @return A formatted string with the solution moves.
     */
    private static String solveTwist(int startState) {
        // Ensure the required pruning table for this phase is initialized.
        initializeTwistPruning();
//        int state = applySequence(scr.split(" "));
        // Reset the length of the global solution sequence.
        solutionLength = 0;
        // --- Iterative Deepening Search ---
        // Start searching at depth 0 and increase indefinitely until a solution is found.
        for (int searchDepth = 0; ; searchDepth++) {
            // Call the recursive search function for the current depth.
            if (searchTwistSolve(startState, searchDepth, INITIAL_LAST_MOVE)) {
                // If a solution is found, format it and return the result.

                return formatSolution();
            }
        }
    }

    /**
     * The recursive search function for the final twist/solve phase.
     * <p>
     * This method uses a backtracking search algorithm to explore all possible
     * sequences of top/bottom rotations and twists to find a path to the solved state.
     * It builds the solution path in the `solutionSequence` array as it searches.
     *
     * @param currentState The current shape coordinate to search from.
     * @param depthRemaining The number of moves left to reach the solved state.
     * @param lastMoveType The type of the last move made (0 for twist, non-zero for layer turn),
     * used to prune redundant paths.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean searchTwistSolve(int     currentState, int depthRemaining, int lastMoveType) {
        // --- Base Case: If max depth is reached, check if the state is solved. ---
        if (depthRemaining == 0) {
            return currentState == SOLVED_SHAPE_COORD;
        }
        //prunTws[getShape2Idx(shape)] == 0;
        // --- Heuristic Pruning ---
        // Use the pre-computed pruning table to cut off inefficient branches.
        if (prunTable_Twist[getShape2Idx(currentState)] > depthRemaining) {
            return false;
        }
        // --- Recursive Step: Explore all valid next moves ---
        // A) Explore all top layer rotations.
        final int NUM_LAYER_ROTATIONS = 12;
        for (int topTurns = 0; topTurns < NUM_LAYER_ROTATIONS; topTurns++) {
            // Record the top turn in the solution path.
            if (topTurns != 0) {
                solutionSequence[solutionLength++] = topTurns;
            }

            // B) Explore all bottom layer rotations.
            for (int bottomTurns = 0; bottomTurns < NUM_LAYER_ROTATIONS; bottomTurns++) {
                // Record the bottom turn in the solution path.
                if (bottomTurns != 0) {
                    solutionSequence[solutionLength++] = -bottomTurns;
                }
                //twist
                // C) Explore the twist move, if valid.
                // Pruning: Don't do a twist immediately after another twist.
                if ((lastMoveType != 0 || (topTurns != 0 || bottomTurns != 0)) && canTwist(currentState)) {
                    int nextState = twist(currentState);
                    solutionSequence[solutionLength++] = 0; // 0 represents the twist move.

                    // Make the recursive call.
                    if (searchTwistSolve(nextState, depthRemaining - 1, 0)) {
                        return true;
                    }
                    // Backtrack: remove the twist from the solution path.
                    solutionLength--;
                }
                // Backtrack: remove the bottom turn if it was added.
                if (bottomTurns != 0) {
                    solutionLength--;
                }
                // Apply the bottom rotation to the state for the next iteration of this loop.
                currentState = rotateBottomLayer(currentState);
            }
            // Backtrack: remove the top turn if it was added.
            if (topTurns != 0) {
                solutionLength--;
            }
            // Apply the top rotation to the state for the next iteration of the outer loop.
            currentState = rotateTopLayer(currentState);
        }

        // If all paths from this state fail, backtrack fully.
        return false;
    }

    /**
     * Formats the found solution sequence into a human-readable string.
     * <p>
     * This method converts the raw move codes stored in the solutionSequence array
     * into the standard "(top,bottom) /" notation for Square-1.
     *
     * @return A formatted string representing the solution.
     */
    private static String formatSolution() {
        // --- Define Constants ---
        final int HALF_ROTATION = 6;
        final int FULL_ROTATION = 12;

        StringBuilder solutionBuilder = new StringBuilder("\n\nttm: ");
        int topTurns = 0, bottomTurns = 0;

        // Iterate through the raw move codes in the solution path.
        for (int i = 0; i < solutionLength; i++) {
            int moveCode = solutionSequence[i];

            if (moveCode > 0) {  // Positive codes are for top layer turns.
                topTurns = (moveCode > HALF_ROTATION) ? (moveCode - FULL_ROTATION) : moveCode;
            } else if (moveCode < 0) { // Negative codes are for bottom layer turns.
                bottomTurns = (moveCode < -HALF_ROTATION) ? (-FULL_ROTATION - moveCode) : -moveCode;
            } else  { // A move code of 0 represents a slice move ("/").
                // If there were pending layer turns, print them first.
                if (topTurns == 0 && bottomTurns == 0) {
                    solutionBuilder.append(" / ");
                } else {
                    solutionBuilder.append('(').append(topTurns).append(',').append(bottomTurns).append(") / ");
                }
                // Reset the turn counters after the slice.
                topTurns = bottomTurns = 0;
            }
        }

        // After the loop, print any remaining layer turns that were not followed by a slice.
        if (topTurns != 0 || bottomTurns != 0) {
            solutionBuilder.append('(').append(topTurns).append(",").append(bottomTurns).append(")");
        }
        return solutionBuilder.toString();
    }
    //</editor-fold>

    //<editor-fold desc="Low-Level Move & State Physics">
    /**
     * Gets the coordinate for the top layer from a packed state integer.
     *
     * @param packedState The 24-bit integer representing the full puzzle shape.
     * @return An integer representing only the top layer's 12-bit coordinate.
     */
    private static int getTopLayer(int packedState) {
        // --- Define Constant (Bitmask) ---
        // A mask to isolate the lower 12 bits (FFF in hex = 1111 1111 1111 in binary).
        final int LAYER_MASK = 0xFFF;

        // The bitwise AND operation with the mask clears all bits except the
        // lower 12, effectively extracting the top layer's data.
        return packedState & LAYER_MASK;
    }

    /**
     * Gets the coordinate for the bottom layer from a packed state integer.
     *
     * @param packedState The 24-bit integer representing the full puzzle shape.
     * @return An integer representing only the bottom layer's 12-bit coordinate.
     */
    private static int getBottomLayer(int packedState) {
        // --- Define Constant (Bitmask) ---
        // A mask to isolate 12 bits (FFF in hex = 1111 1111 1111 in binary).
        final int LAYER_MASK = 0xFFF;

        // 1. First, shift the entire 24-bit integer right by 12 bits.
        // This moves the upper 12 bits (the bottom layer) into the lower 12 bit positions.

        int shiftedState = packedState >> 12;

        // 2. Use the bitwise AND with the mask to clear all other bits,
        // effectively extracting the bottom layer's data.
        return shiftedState & LAYER_MASK;
    }

    /** Performs a rotation of a single layer's bits. */
    private static int rotate(int layer) {
        return ((layer << 1) & 0xFFE) | ((layer >> 11) & 1);
    }

    /** Applies a rotation to the top layer of a given state. */
    private static int rotateTopLayer(int state) {
        return (getBottomLayer(state) << 12) | rotate(getTopLayer(state));
    }

    /** Applies a rotation to the bottom layer of a given state. */
    private static int rotateBottomLayer(int state) {
        return (rotate(getBottomLayer(state)) << 12) | getTopLayer(state);
    }

    /**
     * Performs the slice move ("/"), twisting the puzzle's halves.
     * <p>
     * This method simulates a slice move by swapping the front 7 bits of the top
     * layer's coordinate with the front 7 bits of the bottom layer's coordinate.
     *
     * @param state The integer coordinate of the shape to twist.
     * @return The new shape coordinate after the twist.
     */
    private static int twist(int state) {
        // --- Define Constants (Bitmasks) ---
        // Mask for the 5 bits of the back half of a layer (binary ...111110000000).
        final int BACK_HALF_MASK = 0xF80;

        // Mask for the 7 bits of the front half of a layer (binary ...000001111111).
        final int FRONT_HALF_MASK = 0x7F;

        // --- 1. Get Current Layer States ---
        int topLayer = getTopLayer(state);
        int bottomLayer = getBottomLayer(state);

        // --- 2. Calculate New Layer States ---
        // The new top layer is the back half of the old top layer combined
        // with the front half of the old bottom layer.
        int newTopLayer  = (getTopLayer(state) & BACK_HALF_MASK) | (getBottomLayer(state) & FRONT_HALF_MASK);

        // The new bottom layer is the back half of the old bottom layer combined
        // with the front half of the old top layer.
        int newBottomLayer  = (getBottomLayer(state) & BACK_HALF_MASK) | (getTopLayer(state) & FRONT_HALF_MASK);
        return (newBottomLayer  << 12) | newTopLayer ;
    }

    /**
     * Checks if a slice move ("/") is possible from the current shape state.
     * <p>
     * A slice move is only possible if the pieces adjacent to the slice plane on
     * both the top and bottom layers are corner pieces. This method uses bitmasks
     * to check if those specific slots are occupied.
     *
     * @param state The integer coordinate of the shape to check.
     * @return True if a slice move is possible, false otherwise.
     *
     */
    private static boolean canTwist(int state) {
        // --- Define Constants (Bitmasks) ---
        // Mask to check the right-most slot adjacent to the slice.
        final int RIGHT_SLOT_MASK = 1; // Binary ...000001
        // Mask to check the left-most slot adjacent to the slice.
        final int LEFT_SLOT_MASK = 1 << 6; // Binary ...100000

        // --- 1. Unpack the State ---
        // Get the bit patterns for the top and bottom layers.
        int topLayer = getTopLayer(state);
        int bottomLayer = getBottomLayer(state);

        // --- 2. Check All Four Critical Slots ---
        // A twist is possible if and only if all four slots adjacent to the
        // slice plane are occupied (i.e., their corresponding bits are 1).
        return (topLayer & RIGHT_SLOT_MASK) != 0 &&         // Check top-right slot
                (topLayer & LEFT_SLOT_MASK) != 0 &&         // Check top-left slot
                (bottomLayer  & RIGHT_SLOT_MASK) != 0 &&    // Check bottom-right slot
                (bottomLayer  & LEFT_SLOT_MASK) != 0;       // Check bottom-left slot
    }

    /**
     * Gets the sequential index for a given shape coordinate using a binary search.
     * <p>
     * The solver uses a packed integer to represent a shape, but needs a simple
     * index (0-3677) to access pruning tables. This function provides that fast

     * conversion.
     *
     * @param shapeCoordinate The integer coordinate of the shape to find.
     * @return The sequential index of the shape in the `shapeCoordinates` array,
     * or a negative number if not found.
     */
    private static int getShape2Idx(int shapeCoordinate) {
        return Arrays.binarySearch(shapeCoordinates, shapeCoordinate);
    }
    //</editor-fold>

















    










}
