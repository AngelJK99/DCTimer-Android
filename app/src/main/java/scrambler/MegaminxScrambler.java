package scrambler;

/**
 * A state model and official WCA scramble generator for the Megaminx puzzle.
 * <p>
 * This class contains the permutation data for Megaminx moves and provides
 * methods to generate random scrambles in the official WCA format (sequences
 * of "R++", "D--", and "U/U'").
 */
public class MegaminxScrambler {
    //<editor-fold desc="Constants & Move Permutation Data">
    // --- Constants ---
    private static final int MOVES_PER_LINE = 10;
    private static final int NUM_FACES = 12;
    private static final int NUM_FACELETS_PER_FACE = 11;
    private static final int NUM_TOTAL_FACELETS = NUM_FACES * NUM_FACELETS_PER_FACE;

    // --- Move Permutation Tables ---
    // These large arrays define the permutation of facelets for each move.
    private static short[] PERMUTATION_U = {
             4,   0,  1,  2,  3,  9,  5,  6,  7,  8, 10,
            11,  12, 13, 58, 59, 16, 17, 18, 63, 20, 21,
            22,  23, 24, 14, 15, 27, 28, 29, 19, 31, 32,
            33,  34, 35, 25, 26, 38, 39, 40, 30, 42, 43,
            44,  45, 46, 36, 37, 49, 50, 51, 41, 53, 54,
            55,  56, 57, 47, 48, 60, 61, 62, 52, 64, 65,
            66,  67, 68, 69, 70, 71, 72, 73, 74, 75, 76,
            77,  78, 79, 80, 81, 82, 83, 84, 85, 86, 87,
            88,  89, 90, 91, 92, 93, 94, 95, 96, 97, 98,
            99, 100,101,102,103,104,105,106,107,108,109,
            110,111,112,113,114,115,116,117,118,119,120,
            121,122,123,124,125,126,127,128,129,130,131};
    private static short[] PERMUTATION_U_INVERSE = {
             1,   2,   3,   4,   0,   6,   7,   8,   9,   5,  10,
            11,  12,  13,  25,  26,  16,  17,  18,  30,  20,  21,
            22,  23,  24,  36,  37,  27,  28,  29,  41,  31,  32,
            33,  34,  35,  47,  48,  38,  39,  40,  52,  42,  43,
            44,  45,  46,  58,  59,  49,  50,  51,  63,  53,  54,
            55,  56,  57,  14,  15,  60,  61,  62,  19,  64,  65,
            66,  67,  68,  69,  70,  71,  72,  73,  74,  75,  76,
            77,  78,  79,  80,  81,  82,  83,  84,  85,  86,  87,
            88,  89,  90,  91,  92,  93,  94,  95,  96,  97,  98,
            99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109,
           110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120,
           121, 122, 123, 124, 125, 126, 127, 128, 129, 130, 131};
    private static short[] PERMUTATION_D_PLUS_PLUS = {
             0,   1,   2,   3,   4,   5,   6,   7,   8,   9,  10,
            33,  34,  35,  14,  15,  38,  39,  40,  19,  42,  43,
            44,  45,  46,  25,  26,  49,  50,  51,  30,  53,  54,
            55,  56,  57,  36,  37,  60,  61,  62,  41,  64,  65,
            11,  12,  13,  47,  48,  16,  17,  18,  52,  20,  21,
            22,  23,  24,  58,  59,  27,  28,  29,  63,  31,  32,
            88,  89,  90,  91,  92,  93,  94,  95,  96,  97,  98,
            99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109,
           110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120,
            66,  67,  68,  69,  70,  71,  72,  73,  74,  75,  76,
            77,  78,  79,  80,  81,  82,  83,  84,  85,  86,  87,
           124, 125, 121, 122, 123, 129, 130, 126, 127, 128, 131};
    private static short[] PERMUTATION_D_MINUS_MINUS = {
             0,   1,   2,   3,   4,   5,   6,   7,   8,   9,  10,
            44,  45,  46,  14,  15,  49,  50,  51,  19,  53,  54,
            55,  56,  57,  25,  26,  60,  61,  62,  30,  64,  65,
            11,  12,  13,  36,  37,  16,  17,  18,  41,  20,  21,
            22,  23,  24,  47,  48,  27,  28,  29,  52,  31,  32,
            33,  34,  35,  58,  59,  38,  39,  40,  63,  42,  43,
            99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109,
           110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120,
            66,  67,  68,  69,  70,  71,  72,  73,  74,  75,  76,
            77,  78,  79,  80,  81,  82,  83,  84,  85,  86,  87,
            88,  89,  90,  91,  92,  93,  94,  95,  96,  97,  98,
           123, 124, 125, 121, 122, 128, 129, 130, 126, 127, 131};
    private static short[] PERMUTATION_R_PLUS_PLUS = {
            81,  77,  78,   3,   4,  86,  82,  83,   8,  85,  87,
           122, 123, 124, 125, 121, 127, 128, 129, 130, 126, 131,
            89,  90,  24,  25,  88,  94,  95,  29,  97,  93,  98,
            33,  34,  35,  36,  37,  38,  39,  40,  41,  42,  43,
            44,  26,  22,  23,  48,  30,  31,  27,  28,  53,  32,
            69,  70,  66,  67,  68,  74,  75,  71,  72,  73,  76,
           101, 102, 103,  99, 100, 106, 107, 108, 104, 105, 109,
            46,  47,  79,  80,  45,  51,  52,  84,  49,  50,  54,
             0,   1,   2,  91,  92,   5,   6,   7,  96,   9,  10,
            15,  11,  12,  13,  14,  20,  16,  17,  18,  19,  21,
            13, 114, 110, 111, 112, 118, 119, 115, 116, 117, 120,
            55,  56,  57,  58,  59,  60,  61,  62,  63,  64,  65};
    private static short[] PERMUTATION_R_MINUS_MINUS = {
            88,  89,  90,   3,   4,  93,  94,  95,   8,  97,  98,
           100, 101, 102, 103,  99, 105, 106, 107, 108, 104, 109,
            46,  47,  24,  25,  45,  51,  52,  29,  49,  50,  54,
            33,  34,  35,  36,  37,  38,  39,  40,  41,  42,  43,
            44,  81,  77,  78,  48,  85,  86,  82,  83,  53,  87,
           121, 122, 123, 124, 125, 126, 127, 128, 129, 130, 131,
            57,  58,  59,  55,  56,  62,  63,  64,  60,  61,  65,
             1,   2,  79,  80,   0,   6,   7,  84,   9,   5,  10,
            26,  22,  23,  91,  92,  31,  27,  28,  96,  30,  32,
            69,  70,  66,  67,  68,  74,  75,  71,  72,  73,  76,
           112, 113, 114, 110, 111, 117, 118, 119, 115, 116, 120,
            15,  11,  12,  13,  14,  20,  16,  17,  18,  19,  21};

    //</editor-fold>

    //<editor-fold desc="Class State Variables">
    // The number of lines in the generated scramble.
    private int numLines;

    // The current state of the puzzle, storing the original face of each sticker.
    public int[] faceletState = new int[NUM_TOTAL_FACELETS];

    // An array to hold the randomly generated sequence of moves.
    private int[] randomMoveSequence;	// move sequences

    //</editor-fold>

    //<editor-fold desc="Public API (Scramble & Image Generation)">
    /**
     * Generates a scramble string of a given length in the official WCA format for Megaminx.
     * <p>
     * The scramble consists of a series of lines. Each line contains 10 alternating
     * "R++ / R--" and "D++ / D--" moves, followed by a final "U" or "U'" move.
     *
     * @param scrambleLength The total number of R/D moves in the scramble.
     * @return A formatted string representing the Megaminx scramble.
     */
    public String scramblestring(int scrambleLength) {
        // --- Define Constants ---
        final double MOVES_PER_LINE_FLOAT = 10.0;

        // --- 1. Setup ---
        // Calculate how many lines are needed for the given scramble length.
        numLines = (int) Math.ceil(scrambleLength / MOVES_PER_LINE_FLOAT);

        // Create and populate an array with random binary choices for the moves.
        randomMoveSequence = new int[MOVES_PER_LINE * numLines];
        generateRandomScramble();

        StringBuilder scrambleBuilder = new StringBuilder();

        // Reset the internal puzzle state to solved to track the scramble's result.
        initializeState();

        // --- 2. Build Scramble String Line by Line ---
        for (int lineIndex = 0; lineIndex < numLines; lineIndex++) {
            // Build the main body of the line with 10 R/D moves.
            for (int moveIndexInLine = 0; moveIndexInLine < MOVES_PER_LINE; moveIndexInLine++) {

                // Moves alternate between R and D based on their position in the line.
                if (moveIndexInLine % 2 != 0) { // Odd-indexed moves (1, 3, 5...) are D moves.
                    if (randomMoveSequence[lineIndex * MOVES_PER_LINE + moveIndexInLine] != 0) {
                        scrambleBuilder.append("D++ ");
                        faceletState = applyMove(faceletState, PERMUTATION_D_PLUS_PLUS);
                    } else {
                        scrambleBuilder.append("D-- ");
                        faceletState = applyMove(faceletState, PERMUTATION_D_MINUS_MINUS);
                    }
                } else { // Even-indexed moves (0, 2, 4...) are R moves.
                    if (randomMoveSequence[lineIndex * MOVES_PER_LINE + moveIndexInLine] != 0) {
                        scrambleBuilder.append("R++ ");
                        faceletState = applyMove(faceletState, PERMUTATION_R_PLUS_PLUS);
                    } else {
                        scrambleBuilder.append("R-- ");
                        faceletState = applyMove(faceletState, PERMUTATION_R_MINUS_MINUS);
                    }
                }
            }

            // --- 3. Add Line-Ending U Move ---
            // Append a U or U' move at the end of each line based on the last random value.
            int finalMoveIndexInSequence = (lineIndex + 1) * MOVES_PER_LINE - 1;
            if (randomMoveSequence[finalMoveIndexInSequence] != 0) {
                scrambleBuilder.append("U \n");
                faceletState = applyMove(faceletState, PERMUTATION_U);
            } else {
                scrambleBuilder.append("U'\n");
                faceletState = applyMove(faceletState, PERMUTATION_U_INVERSE);
            }
        }
        return scrambleBuilder.toString();
    }

    /**
     * Generates a visual representation of the puzzle state from a scramble string.
     * <p>
     * This method applies a WCA-formatted Megaminx scramble to a solved-state model
     * and returns an integer array representing the original face of each of the 132 facelets.
     *
     * @param scramble The scramble string to apply (e.g., "R++ D-- U'").
     * @return An integer array representing the final state of the facelets.
     */
    public int[] image(String scramble) {
        // 1. Reset the internal puzzle state to solved.
        initializeState();

        // 2. Parse the scramble string and apply each move.
        String[] scrambleMoves = scramble.split("[ \n]"); // Split by spaces or newlines
        for (String moveString : scrambleMoves) {
            if (!moveString.isEmpty()) {
                // Apply the correct move permutation based on the move string.
                switch (moveString) {
                    case "D++":
                        faceletState = applyMove(faceletState, PERMUTATION_D_PLUS_PLUS);
                        break;
                    case "D--":
                        faceletState = applyMove(faceletState, PERMUTATION_D_MINUS_MINUS);
                        break;
                    case "R++":
                        faceletState = applyMove(faceletState, PERMUTATION_R_PLUS_PLUS);
                        break;
                    case "R--":
                        faceletState = applyMove(faceletState, PERMUTATION_R_MINUS_MINUS);
                        break;
                    case "U'":
                        faceletState = applyMove(faceletState, PERMUTATION_U_INVERSE);
                        break;
                    case "U":
                        faceletState = applyMove(faceletState, PERMUTATION_U);
                        break;
                }
            }
        }
        // 3. Return the final state.
        return faceletState;
    }

    /**
     * Gets the current state of the puzzle.
     *
     * @return The current facelet state array.
     */
    public int[] getFaceletState() {
        return faceletState;
    }
    //</editor-fold>

//<editor-fold desc="Internal State Management">
    /**
     * Resets the puzzle to the solved state.
     */
    private void initializeState() {
        // Iterate through each of the 12 faces.
        for (int faceIndex = 0; faceIndex < NUM_FACES; faceIndex++)
            // For each face, assign its index (as its "color") to its 11 pieces.
            for (int faceletIndex = 0; faceletIndex < NUM_FACELETS_PER_FACE; faceletIndex++)
                faceletState[faceIndex * NUM_FACELETS_PER_FACE + faceletIndex] = faceIndex;
    }

    /**
     * Applies a move permutation to a given state array.
     * <p>
     * This method takes the current state of the puzzle and a move permutation array.
     * It returns a new state array representing the puzzle after the move has been applied.
     *
     * @param currentState The state array to apply the move to.
     * @param movePermutation The permutation array for the specific move.
     * @return A new array representing the state after the move.
     */
    private int[] applyMove(int[] currentState, short[] movePermutation) {
        // Create a new array to hold the state after the move.
        int[] stateNew = new int[NUM_TOTAL_FACELETS];

        // For each facelet in the new state...
        for (int i = 0; i < NUM_TOTAL_FACELETS; i++) {
            // ...find which facelet from the old state moves into this position.
            // The movePermutation array maps the new position (i) to the old position.
            stateNew[i] = currentState[movePermutation[i]];
        }
        return stateNew;
    }
    //</editor-fold>

    //<editor-fold desc="Internal Scramble Logic">
    /**
     * Fills the move sequence array with random binary choices.
     * <p>
     * This helper method populates the class's `randomMoveSequence` array with
     * random 0s and 1s, which are later used to decide the direction of
     * each turn in the scramble.
     */
    private void generateRandomScramble() {
        // --- Define Constant ---
        final int NUM_CHOICES = 2; // For a binary choice (0 or 1)

        // Iterate through the entire length of the sequence array.
        for (int i = 0; i < numLines * MOVES_PER_LINE; i++) {
            // Assign a random integer (either 0 or 1) to each position.
            randomMoveSequence[i] = (int) (Math.random() * NUM_CHOICES);
        }
    }
    //</editor-fold>






}
