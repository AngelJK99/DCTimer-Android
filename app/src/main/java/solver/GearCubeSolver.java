package solver;

import java.util.Random;

/**
 * A solver for the Gear Cube puzzle.
 * <p>
 * This class uses coordinate systems to track the state of the corners and edges.
 * It pre-computes lookup tables to find optimal solutions using only U, R, and F
 * gear turns. It is primarily used to generate random-state scrambles.
 */
public class GearCubeSolver {
    //<editor-fold desc="Constants & Class Variables">
    // --- Constants ---
    private static final int NUM_4_PIECE_PERM_STATES = 24;  // 4!
    private static final int NUM_3_PIECE_ORIENT_STATES = 27;  // 3^3
    private static final int NUM_MOVES = 3;                 // U, R, F
    private static final int MAX_SOLUTION_DEPTH = 8;
    private static final int NUM_CORNERS_TO_TRACK = 4;
    private static final int NUM_EDGES_TO_TRACK = 4;
    private static final int NUM_COMBINED_PERM_STATES = 24 * 24 ;
    private static final byte UNVISITED_STATE = -1;
    private static final int SOLVED_STATE_DISTANCE = 0;
    private static final int SOLVED_STATE_COORD = 0;
    private static final int INITIAL_LAST_MOVE = -1;
    private static final int NUM_TURN_AMOUNTS = 11;

    // --- Lookup Tables ---
    // Move tables for corner perm, edge perm, and edge orientation.
    private static short[][] moveTableCornerPerm = new short[NUM_4_PIECE_PERM_STATES][NUM_MOVES];
    private static short[][] moveTableEdgePerm = new short[NUM_4_PIECE_PERM_STATES][NUM_MOVES];
    private static short[][] moveTableEdgeOrient = new short[NUM_3_PIECE_ORIENT_STATES][NUM_MOVES];

    // Three separate pruning tables for different corner/edge combinations.
    private static byte[][] pruningTable = new byte[NUM_MOVES][NUM_COMBINED_PERM_STATES];
    // --- Solver & State Variables ---zdzdZ
    // Array to store the found solution sequence.
    private static int[] solutionSequence = new int[8];
    // String arrays for formatting the output.
    private static String[] MOVE_CHARS = {"U", "R", "F"};
    private static String[] SUFFIXES = {"'", "2'", "3'", "4'", "5'", "6", "5", "4", "3", "2", ""};
    //</editor-fold>

    //<editor-fold desc="Initialization">
    /** Static initializer to generate all tables when the class is loaded. */
    static {
        initializeTables();
    }

    /**
     * Initializes and pre-computes all lookup tables for the Gear Cube solver.
     * <p>
     * This heavy computation is run only once. It generates the move tables for the
     * three coordinate systems (corner perm, edge perm, edge orient) and then
     * builds the corresponding pruning tables.
     */
    private static void initializeTables() {
        // Part 1: Generate Move Tables"
        // --- Build Corner Permutation Move Table ---
        int[] tempPermutation = new int[NUM_CORNERS_TO_TRACK];
        for (int stateIndex = 0; stateIndex < NUM_4_PIECE_PERM_STATES; stateIndex++) {
            for (int moveIndex = 0; moveIndex < NUM_4_PIECE_PERM_STATES; moveIndex++) {
                Utils.idxToPerm(tempPermutation, stateIndex, NUM_CORNERS_TO_TRACK, false);
                // A gear move is equivalent to swapping a corner with one of the other three.
                Utils.swap(tempPermutation, 3, moveIndex);
                moveTableCornerPerm[stateIndex][moveIndex] = (short) Utils.permToIdx(tempPermutation, NUM_CORNERS_TO_TRACK, false);
            }
        }

        // --- Build Edge Permutation Move Table ---
        for (int stateIndex = 0; stateIndex < NUM_4_PIECE_PERM_STATES; stateIndex++) {
            for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                Utils.idxToPerm(tempPermutation, stateIndex, NUM_MOVES, false);
                switch (moveIndex) {
                    case 0: Utils.circle(tempPermutation, 0, 3, 2, 1); break; // U-move
                    case 1: Utils.swap(tempPermutation, 0, 1); break;                     // R-move
                    case 2: Utils.swap(tempPermutation, 1, 2); break;                     // F-move
                }
                moveTableEdgePerm[stateIndex][moveIndex] = (short) Utils.permToIdx(tempPermutation, NUM_EDGES_TO_TRACK, false);
            }
        }

        // --- Build Edge Orientation Move Table ---
        final int NUM_EDGES_TO_ORIENT = 3;

        tempPermutation = new int[NUM_EDGES_TO_ORIENT];
        for (int stateIndex = 0; stateIndex < NUM_3_PIECE_ORIENT_STATES; stateIndex++) {
            for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                Utils.idxToOri(tempPermutation, stateIndex, NUM_EDGES_TO_ORIENT, false);
                tempPermutation[moveIndex] = (tempPermutation[moveIndex] + 1) % 3;
                moveTableEdgeOrient[stateIndex][moveIndex] = (short) Utils.oriToIdx(tempPermutation, NUM_EDGES_TO_ORIENT, false);
            }
        }

        // Part 2: Generate Pruning Tables
        final int PRUNING_DEPTH = 5;
        final int NUM_TURN_AMOUNTS = 11; // Gear turns can be from 1/6 to 11/6 of a full rotation.

        // This solver uses three separate pruning tables, one for each orientation of the R/F moves.
        for (int tableIndex = 0; tableIndex < 3; tableIndex++) {
            // Initialize the table with an "unvisited" marker.
            for (int j = 1; j < NUM_COMBINED_PERM_STATES; j++) {
                pruningTable[tableIndex][j] = UNVISITED_STATE;
            }
            pruningTable[tableIndex][SOLVED_STATE_COORD] = SOLVED_STATE_DISTANCE;// Solved state is at distance 0.

            // Populate the table using a Breadth-First Search (BFS).
            for (int currentDepth = 0; currentDepth < PRUNING_DEPTH; currentDepth++) {
                //n = 0;
                for (int combinedCoord = 0; combinedCoord < NUM_COMBINED_PERM_STATES; combinedCoord++) {
                    if (pruningTable[tableIndex][combinedCoord] == currentDepth) {
                        // Explore all 3 possible moves (U, R, F).
                        for (int moveIndex = 0; moveIndex < 3; moveIndex++) {
                            int nextCombinedCoord = combinedCoord;
                            // A single gear "click" can be repeated up to 11 times.
                            for (int turn = 0; turn < NUM_TURN_AMOUNTS; turn++) {
                                int edgePerm = nextCombinedCoord % NUM_4_PIECE_PERM_STATES;
                                int cornerPerm = nextCombinedCoord / NUM_4_PIECE_PERM_STATES;

                                // Get the new coordinates from the move tables.
                                // The edge move table used depends on the pruning table index.
                                cornerPerm = moveTableCornerPerm[cornerPerm][moveIndex];
                                edgePerm = moveTableEdgePerm[edgePerm][(moveIndex + tableIndex) % 3];

                                // Repack the coordinates.
                                nextCombinedCoord = NUM_4_PIECE_PERM_STATES * cornerPerm + edgePerm;
                                if (pruningTable[tableIndex][nextCombinedCoord] == UNVISITED_STATE) {
                                    pruningTable[tableIndex][nextCombinedCoord] = (byte) (currentDepth + 1);
                                    //n++;
                                }
                            }
                        }
                    }
                }//System.out.println(d+" "+n);
            }
        }
    }
    //</editor-fold>

    //<editor-fold desc="Public Scramble Generator">
    /**
     * Generates a random-state scramble for the Gear Cube.
     * <p>
     * This method creates a scramble by picking a random, reachable state for all
     * piece groups and then finding its optimal solution. The reversed solution becomes
     * the scramble. It includes logic to ensure the scramble is not trivially short.
     *
     * @return A string representing the scramble moves.
     */
    public static String scramble() {
        // --- Define Constants ---
        final int MIN_LENGTH_FOR_RETRY = 2;
        final int MIN_LENGTH_TO_ACCEPT = 3;

        Random randomGenerator = new Random();
        // --- 1. Pick a Valid Random Starting State ---

        // Pick a random coordinate for the corner permutation.
        int startCornerPerm = randomGenerator.nextInt(NUM_4_PIECE_PERM_STATES);

        // Create an array to hold the coordinates for the 3 separate edge groups.
        int[] startEdgePerms = new int[3];

        // For each edge group, pick a random coordinate that is reachable,
        // given the chosen corner permutation.
        for (int i = 0; i < 3; i++) {
            do startEdgePerms[i] = randomGenerator.nextInt(NUM_4_PIECE_PERM_STATES );
            while (pruningTable[i][NUM_4_PIECE_PERM_STATES  * startCornerPerm + startEdgePerms[i]] < 0);
        }

        // Pick a random coordinate for the edge orientation.
        int startEdgeOrient = randomGenerator.nextInt(27);

        // --- 2. Iterative Deepening Search ---
        // Find the shortest solution from the valid random state.
        for (int searchDepth = 0; searchDepth < 7; searchDepth++) {
            if (search(startCornerPerm, startEdgePerms[0], startEdgePerms[1], startEdgePerms[2], startEdgeOrient, searchDepth, INITIAL_LAST_MOVE)) {

                // --- 3. Validate and Format Solution ---

                if (searchDepth < MIN_LENGTH_FOR_RETRY) {
                    return scramble();
                }
                // If the solution is still short, continue searching for a longer, more interesting scramble.
                if (searchDepth < MIN_LENGTH_TO_ACCEPT) {
                    continue;
                }

                // If a suitable solution is found, format it into a scramble string.
                StringBuilder scrambleBuilder = new StringBuilder();
                for (int i = 1; i <= searchDepth; i++) {
                    int moveCode = solutionSequence[i];
                    int faceIndex = moveCode / NUM_TURN_AMOUNTS; // 11 possible turn amounts
                    int turnAmount = moveCode % NUM_TURN_AMOUNTS;
                    scrambleBuilder.append(MOVE_CHARS[faceIndex])
                                    .append(SUFFIXES[turnAmount])
                                    .append(" ");
                }
                return scrambleBuilder.toString();
            }
        }
        // If no solution is found (should not happen in practice), return an error.
        return "error";
    }
    //</editor-fold>

    //<editor-fold desc="Internal Solver Logic">

    /**
     * The recursive IDA* search function.
     *
     * @param cornerPerm The current corner permutation coordinate.
     * @param edgePerm1  The coordinate for the first edge group.
     * @param edgePerm2  The coordinate for the second edge group.
     * @param edgePerm3  The coordinate for the third edge group.
     * @param edgeOrient The current edge orientation coordinate.
     * @param depthRemaining The number of moves left in the current search path.
     * @param lastMove   The index of the last move made.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean search(int cornerPerm, int edgePerm1, int edgePerm2, int edgePerm3,
                                  int edgeOrient, int depthRemaining, int lastMove) {
        // --- Base Case: If no moves are left, check if the state is solved. ---
        if (depthRemaining == 0) {
            return cornerPerm == SOLVED_STATE_COORD &&
                    edgePerm1 == SOLVED_STATE_COORD &&
                    edgePerm2 == SOLVED_STATE_COORD &&
                    edgePerm3 == SOLVED_STATE_COORD &&
                    edgeOrient == SOLVED_STATE_COORD;
        }
        // --- Heuristic Pruning ---
        // Check all three pruning tables. The heuristic is the maximum of the three distances.
        // If this max distance is greater than the depth we have left, this path is a dead end.
        if (Math.max(
                Math.max(
                        pruningTable[0][NUM_4_PIECE_PERM_STATES  * cornerPerm + edgePerm1],
                        pruningTable[1][NUM_4_PIECE_PERM_STATES  * cornerPerm + edgePerm2]
                ),
                pruningTable[2][NUM_4_PIECE_PERM_STATES  * cornerPerm + edgePerm3]
        ) > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore all valid next moves. ---
        for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
            if (moveIndex != lastMove) {
                int nextCornerPerm = cornerPerm;
                int nextEdgePerm1 = edgePerm1;
                int nextEdgePerm2 = edgePerm2;
                int nextEdgePerm3 = edgePerm3;
                int nextEdgeOrient = edgeOrient;

                // A single "move" on a gear cube can be a turn of 1/6, 2/6, etc.
                // This loop explores all 11 possible turn amounts.
                for (int turnAmount = 0; turnAmount < NUM_TURN_AMOUNTS; turnAmount++) {
                    // Get the next state for all five coordinates from their respective move tables.
                    nextCornerPerm = moveTableCornerPerm[nextCornerPerm][moveIndex];
                    nextEdgePerm1 = moveTableEdgePerm[nextEdgePerm1][moveIndex];
                    nextEdgePerm2 = moveTableEdgePerm[nextEdgePerm2][(moveIndex + 1) % 3];
                    nextEdgePerm3 = moveTableEdgePerm[nextEdgePerm3][(moveIndex + 2) % 3];
                    nextEdgeOrient = moveTableEdgeOrient[nextEdgeOrient][moveIndex];

                    // Make the recursive call for the new state.
                    if (search(nextCornerPerm, nextEdgePerm1, nextEdgePerm2, nextEdgePerm3, nextEdgeOrient, depthRemaining - 1, moveIndex)) {
                        // --- Solution Found! ---
                        // Record the successful move in the solution sequence array.
                        solutionSequence[depthRemaining] = moveIndex * NUM_TURN_AMOUNTS + turnAmount;
                        //sb.insert(0, turn[n] + suff[m] + " ");
                        return true;
                    }
                }
            }
        }
        return false;
    }

    //</editor-fold>

}
