package solver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class Utils {

    //<editor-fold desc="Constants and Static Initializers">

    // Pre-calculated table for combinations C(n, k).

    private static final int NUM_ORIENTATIONS_PER_CORNER = 3;
    public static int[][] Cnk = new int[25][25];

    // Standard move names and suffixes.
    public static String[] turn = {"U", "D", "L", "R", "F", "B"};
    public static String[] turnSuffix = {"", "2", "'"};
    public static String[] turnSuffixInverse = {"'", "2", ""};

    // Pre-calculated table for factorials (n!).
    public static int[] factorial = {1, 1, 2, 6, 24, 120, 720, 5040, 40320, 362880, 3628800, 39916800, 479001600};

    // Static initializer to pre-compute the C(n, k) table on class load.
    static {
        for (int i = 0; i < 25; i++) {
            Cnk[i][0] = 1;
            for (int j = Cnk[i][i] = 1; j < i; j++)
                Cnk[i][j] = Cnk[i - 1][j - 1] + Cnk[i - 1][j];
        }
    }
    //</editor-fold>

    //<editor-fold desc="Coordinate Systems (Index <-> Array Conversion)">
    //<editor-fold desc="Permutation Coordinates">

    /**
     * Encodes a permutation array into a unique integer index (Lehmer code).
     * <p>
     * This method calculates the unique index for a given arrangement of elements,
     * which is essential for coordinate-based puzzle solvers.
     *
     * @param permArray    The input array containing the permutation (e.g., {2, 0, 1}).
     * @param length        The number of elements in the permutation.
     * @param evenParity If true, assumes an even parity constraint, where the positions
     * of the last two elements are dependent on the first n-2.
     * @return The unique integer index for the permutation.
     */
    public static int permToIdx(int[] permArray, int length, boolean evenParity) {
        int index = 0; // The final index to be built.

        // Determine the loop boundary. For even parity, the last two elements are
        // dependent on the others, so we only need to encode the first n-2 elements.
        int bound = evenParity ? length - 2 : length - 1;

        for (int i = 0; i < bound; i++) {
            // Make space for the next "digit" in the factorial number system.
            index *= length - i;

            // Inner loop counts the number of elements to the right of permArr[i]
            // that are smaller than it. This count is the factoradic digit.
            for (int j = i + 1; j < length; j++) {
                if (permArray[i] > permArray[j]) {
                    index++;
                }
            }
        }
        return index;
    }

    /**
     * Decodes a unique integer index into a specific permutation.
     * This method converts an index from a factorial number system (factoradic)
     * into a standard permutation array {p_0, p_1, ..., p_{n-1}}.
     *
     * @param permArray The output array to be filled with the generated permutation.
     * @param index       The unique integer representing the permutation (from 0 to n!-1).
     * @param length      The number of elements in the permutation (n).
     * @param evenParity        If true, ensures the generated permutation has an even parity.
     * This is crucial for puzzles like the Rubik's Cube where only
     * even permutations are reachable from a solved state.
     */
    public static void idxToPerm(int[] permArray, int index, int length, boolean evenParity) {
        // This variable will be used to track the parity of the permutation.
        // The parity of a permutation is the same as the parity of the sum of its
        // factoradic digits (Lehmer code).
        int paritySum = 0;

        // --- INITIALIZATION ---
        // Handle the placement of the last one or two elements based on the parity requirement.
        if (evenParity) {
            // If an even permutation is required, we fix the last two elements temporarily.
            // We will generate a permutation for the first (n-2) elements and then
            // adjust these last two if needed to fix the overall parity.
            permArray[length - 1] = 1;
            permArray[length - 2] = 0;
        } else {
            permArray[length - 1] = 0;
        }

        // Determine where the main loop should start.
        int startPos = evenParity ? length - 3 : length - 2;

        // --- MAIN GENERATION LOOP ---
        // Iterate backwards from the second-to-last (or third-to-last) element down to the first.
        // This process is equivalent to converting a number to its factoradic representation.
        for (int i = startPos; i >= 0; i--) {
            // Extract the next factoradic "digit".
            permArray[i] = index % (length - i);

            // Track the sum of these digits to determine the permutation's parity.
            paritySum += permArray[i];

            // Update the index for the next iteration (equivalent to an integer division
            // to get the next digit in a different number base).
            index /= length - i;

            // This crucial inner loop adjusts the values to its right to "make space"
            // for the newly placed item, ensuring the permutation has no duplicates.
            for (int j = i + 1; j < length; j++)
                if (permArray[j] >= permArray[i]) permArray[j]++;
        }
        // --- PARITY CORRECTION STEP ---
        // If an even permutation was required and the generated one is odd...
        if (evenParity && paritySum % 2 != 0) {
            // ...perform a single swap on the last two elements to flip the parity to even.
            swap(permArray, length - 1, length - 2);
        }
    }
    //</editor-fold>

    //<editor-fold desc="Combination Coordinates">

    // combinations
    /**
     * Converts a combination array into a unique integer index (combinadic).
     * <p>
     * This function takes an array representing a combination (e.g., which 4 of 12
     * slots are occupied) and calculates a single, unique integer that represents
     * that specific combination. It is the inverse of the `idxToComb` function.
     *
     * @param combinationArray An array of size `totalItems`, where non-zero values
     * mark the positions of the chosen items.
     * @param itemsToChoose The number of items in the combination (e.g., 4 for the cross).
     * @param totalItems The total number of items to choose from (e.g., 12 for all edges).
     * @return The unique integer index for the given combination.
     */
    public static int combToIdx(int[] combinationArray, int itemsToChoose, int totalItems) {
        int combinationIndex = 0; // The final index we are building.

        // Iterate backwards through all possible item positions.
        for (int position = totalItems - 1; position >= 0; position--) {

            // If the current position is part of our combination...
            if (combinationArray[position] != 0) {

                // ...add the number of combinations that can be formed using only
                // the items *before* this position. Cnk[position][itemsToChoose]
                // is a pre-calculated table for C(n, k) or "n choose k"
                combinationIndex += Cnk[position][itemsToChoose];

                // We have now accounted for one of our chosen items, so we
                // decrement the count of items we still need to find.
                itemsToChoose--;
            }
        }
        return combinationIndex;
    }

    /**
     * Decodes a unique integer index into a combination array.
     * <p>
     * This method uses the combinatorial number system (combinadic) to reconstruct
     * which 'k' items are chosen out of a total of 'n' items, based on the given index.
     * It is the inverse of the `combToIdx` function.
     *
     * @param combinationArray    The output array to be filled (non-zero for chosen items).
     * @param index                 The unique integer index representing the combination.
     * @param itemsToChoose          The number of items to choose in the combination.
     * @param totalItems            The total number of items to choose from.
     */
    public static void idxToComb(int[] combinationArray, int index, int itemsToChoose, int totalItems) {
        for (int pos = totalItems - 1; pos >= 0; pos--) {
            // Cnk[pos][itemsToChoose] is the number of combinations that can be formed using
            // only the items smaller than the current position 'pos'.

            // If our index is larger than or equal to this value, it means
            // the item at 'pos' *must* be part of our combination.
            if (index >= Cnk[pos][itemsToChoose]) {
                // Subtract this block of combinations from the index.
                index -= Cnk[pos][itemsToChoose];

                // We have now found one of our k items, so we need to find one less.
                // The post-decrement (k--) uses the current value of k in Cnk[pos][k]
                // before decrementing it for the next iteration.
                itemsToChoose--;

                // Mark the item at this position as chosen.
                combinationArray[pos] = 1;
            } else {
                // Otherwise, the item at this position is not in our combination.
                combinationArray[pos] = 0;
            }
        }
    }
    //</editor-fold>

    //<editor-fold desc="Flip Coordinates (for Edges, Base 2)">
    /**
     * Encodes a binary flip array (for edges) into a unique integer index.
     * <p>
     * This method treats the flip array as a sequence of bits and packs them
     * into a single integer for compact storage.
     *
     * @param flipArray   The input array of flips, containing 0s and 1s.
     * @param length       The number of elements in the array.
     * @param isZeroSum If true, assumes the sum of flips is always even (mod 2).
     * This means the last flip is determined by the others, so it
     * only needs to encode the first n-1 elements.
     * @return The unique integer index representing the flip state.
     */
    public static int flipToIdx(int[] flipArray, int length, boolean isZeroSum) {
        int index = 0;

        // If the last flip is redundant, we only need to process the first n-1 elements.
        if (isZeroSum) length--;

        // Iterate through the relevant flips.
        for (int i = 0; i < length; i++) {
            // Pack the next bit into the index.
            // (idx << 1) shifts the existing bits to the left to make space.
            // (| flipArr[i]) adds the new flip bit (0 or 1) to the rightmost position.
            index = index << 1 | flipArray[i];
        }
        return index;
    }

    /**
     * Decodes a unique integer index into a binary flip array (for edges).
     * <p>
     * This method unpacks an integer into a sequence of bits (0s and 1s),
     * effectively performing an integer-to-binary conversion.
     *
     * @param flipArray   The output array to be filled with the flip values (0s and 1s).
     * @param index       The unique integer index to decode.
     * @param length       The number of elements in the array.
     * @param isZeroSum If true, assumes the sum of flips must be even (mod 2).
     * The last flip is then calculated based on the others to
     * enforce this constraint.
     */
    public static void idxToFlip(int[] flipArray, int index, int length, boolean isZeroSum) {
        int paritySum = 0; // Tracks the XOR sum to calculate the last flip if needed.

        int bound = length;
        // If the last flip is dependent on the others, we only decode the first n-1.
        if (isZeroSum) {
            bound--;
        }
        for (int i = bound - 1; i >= 0; i--) {
            // Extract the last bit of the index (0 or 1).
            int currentFlip = index & 1;
            flipArray[i] = currentFlip;

            // Add this flip to our XOR sum.
            paritySum ^= currentFlip;

            // Discard the last bit to process the next one in the following iteration.
            index >>= 1;
        }
        // If the zero-sum constraint is active, calculate the last dependent flip.
        // The last flip must be equal to the XOR sum of all other flips.
        if (isZeroSum) {
            flipArray[length] = paritySum;
        }
    }
    //</editor-fold>

    //<editor-fold desc="Orientation Coordinates (for Corners, Base 3)">
    /**
     * Encodes a ternary orientation array (for corners) into a unique integer index.
     * <p>
     * This method treats the orientation array as a sequence of base-3 digits and
     * packs them into a single integer for compact storage.
     *
     * @param orientationArray    The input array of orientations, containing values 0, 1, or 2.
     * @param length       The number of elements in the array.
     * @param isZeroSum     If true, assumes the sum of orientations is a multiple of 3.
     * This means the last orientation is determined by the others, so it
     * only needs to encode the first n-1 elements.
     * @return The unique integer index representing the orientation state.
     */
    public static int oriToIdx(int[] orientationArray, int length, boolean isZeroSum) {
        int index = 0; // The final index to be built.

        // If the last orientation is redundant, we only need to process the first n-1.
        if (isZeroSum) {
            length--;
        }

        // Iterate through the relevant orientations.
        for (int i = 0; i < length; i++) {
            // This is a standard base conversion from base-3 to base-10.
            // It's like saying new_number = old_number * 3 + next_digit.
            index = NUM_ORIENTATIONS_PER_CORNER * index +
                    (orientationArray[i] % NUM_ORIENTATIONS_PER_CORNER);
        }
        return index;
    }

    /**
     * Decodes a unique integer index into a ternary orientation array (for corners).
     * <p>
     * This method unpacks an integer into a sequence of base-3 digits (0, 1, or 2),
     * effectively performing a base-10 to base-3 conversion.
     *
     * @param orientationArray    The output array to be filled with orientation values (0, 1, or 2).
     * @param index       The unique integer index to decode.
     * @param length       The number of elements in the array.
     * @param isZeroSum If true, assumes the sum of orientations must be a multiple of 3.
     * The last orientation is then calculated based on the others to
     * enforce this constraint.
     */
    public static void idxToOri(int[] orientationArray, int index, int length, boolean isZeroSum) {
        int sum = 0; // Tracks the sum to calculate the last orientation if needed.

        // Determine the starting position for the loop. If the last element is
        // dependent, we only decode the first n-1 elements.
        int startPos = isZeroSum ? length - 2 : length - 1;

        // Iterate backwards to fill the array from right to left.
        for (int i = startPos; i >= 0; i--) {
            // The remainder of a division by 3 gives the next base-3 digit.
            orientationArray[i] = index % NUM_ORIENTATIONS_PER_CORNER;

            // Integer division by 3 prepares the index for the next digit.
            index /= NUM_ORIENTATIONS_PER_CORNER;

            // Add the new digit to the running sum.
            sum += orientationArray[i];
        }

        // If the zero-sum constraint is active, calculate the last dependent orientation.
        if (isZeroSum) {
            // The last orientation must make the total sum a multiple of 3.
            orientationArray[length - 1] = NUM_ORIENTATIONS_PER_CORNER - sum % NUM_ORIENTATIONS_PER_CORNER;
        }
    }
    // </editor-fold>

    //<editor-fold desc="Pruning Table Utilities">
    /**
     * Gets a 4-bit distance value from a packed pruning table.
     * <p>
     * This function uses bitwise operations to read a specific 4-bit value (0-15)
     * from an integer array where 8 such values are packed into each integer.
     *
     * @param pruningTable The packed array storing the distance data.
     * @param stateIndex   The unique index of the puzzle state whose distance is needed.
     * @return The distance value (0-15), where 15 (0xF) often means "unvisited".
     */
    public static int getPruning(int[] pruningTable, int stateIndex) {

        /*The packed integer pruningTable[0] would look like this in binary:

        0001 0010 0001 0010 0010 0001 0001 0000
        ↑      ↑      ↑      ↑      ↑      ↑      ↑      ↑
        Dist 7 Dist 6 Dist 5 Dist 4 Dist 3 Dist 2 Dist 1 Dist 0
        (1)    (2)    (1)    (2)    (2)    (1)    (1)    (0)

        In hexadecimal, this single integer value is 0x12122110.

        The getPruning(table, 5) function would perform bitwise operations on this number to
        isolate the 6th nibble (for index 5) and return the value 1.

        - pruningTable[0] would store the distances for states 0-7.
        - pruningTable[1] would store the distances for states 8-15.
        - And so on for the entire table.*/

        // 1. Find which integer in the array holds our value.
        // Each integer holds 8 values, so we divide the index by 8.
        // (stateIndex >> 3) is a fast way to do integer division by 8.
        int packedInt = pruningTable[stateIndex >> 3];

        // 2. Find the slot of our value within that integer (0-7).
        // (stateIndex & 7) is a fast way to get the remainder of a division by 8.
        // We then multiply by 4 (<< 2) because each value occupies 4 bits.
        int bitShift = (stateIndex & 7) << 2;

        // 3. Shift the desired 4 bits to the rightmost position...
        // ...and mask with 15 (binary 1111) to isolate and return the value.
        return (packedInt >> bitShift) & 15;
    }

    /**
     * Sets a pruning table distance value for a given state index.
     * <p>
     * This function uses bitwise operations to store a 4-bit distance value (0-15)
     * into a packed integer array. Each integer in the table holds 8 such values,
     * significantly reducing memory usage.
     *
     * @param pruningTable The packed array storing the distance data.
     * @param stateIndex The unique index of the puzzle state (e.g., a specific cross pattern).
     * @param distance The distance (number of moves from solved) to store for this state.
     */
    public static void setPruning(int[] pruningTable, int stateIndex, int distance) {
        // --- 1. Find the correct integer in the array ---
        // Each integer stores 8 states, so we divide the index by 8.
        // (stateIndex >> 3) is a fast way to do integer division by 8.
        int arrayIndex = stateIndex >> 3;

        // --- 2. Find the correct 4-bit slot within that integer ---
        // (stateIndex & 7) gets the remainder when dividing by 8 (a value from 0 to 7),
        // which tells us which of the 8 slots to use.
        // We multiply by 4 because each slot is 4 bits wide.
        int bitShift = (stateIndex & 7) << 2;

        // --- 3. Set the 4-bit value ---
        // The XOR (^) operation is used to set the value. First, we clear the 4-bit slot
        // by XORing it with 15 (binary 1111), and then we set the new value by XORing
        // it with the desired distance. The expression (15 ^ distance) combines
        // this into a single operation.
        pruningTable[arrayIndex] ^= (15 ^ distance) << bitShift ;
    }

    /*  Example : Let's say we want to set the distance of state index 5 to a value of 2.

        setPruning(pruningTable, 5, 2);

        In binary, pruningTable[0] would be a 32-bit integer full of ones:
        1111 1111 1111 1111 1111 1111 1111 1111 (or 0xFFFFFFFF in hex)

        The function determines which integer in the array holds the data for state 5.

        arrayIndex = stateIndex >> 3

        arrayIndex = 5 >> 3 = (5 / 8)

        arrayIndex = 0  *The data is in pruningTable[0].

        Next, it finds the specific 4-bit slot within pruningTable[0].
        Index 5 corresponds to the 6th slot (counting from 0).

        bitShift = (stateIndex & 7) << 2
        bitShift = (5 & 7) << 2
        bitShift = 5 << 2 (5 * 4)

        bitShift = 20
        *The data must be written starting at the 20th bit.

        The function creates a mask to change the existing value (15) to the new value (2).

        mask = 15 ^ distance
        mask = 15 ^ 2

        In binary: 1111 ^ 0010 = 1101
        mask = 13

        The Bitwise Operation
        The final step is to apply this mask at the correct position. The operation is:
        pruningTable[0] ^= (13 << 20);

        Here's how it looks in binary:

        // Initial value of pruningTable[0]
          1111 1111 1111 1111 1111 1111 1111 1111

        // The mask (13, or 1101) shifted left by 20 bits
        ^ 0000 0000 0000 1101 0000 0000 0000 0000
        -------------------------------------------
        // Final value of pruningTable[0]
        1111 1111 1111 0010 1111 1111 1111 1111
                           ^
                           |
                        Slot 5
    */


    /**
     * Populates a pruning table using a Breadth-First Search (BFS) algorithm.
     * <p>
     * This method starts from the solved state(s) (which must be pre-set to a distance of 0)
     * and explores all reachable states layer by layer, recording the minimum number
     * of moves required to reach each state.
     *
     * @param pruningTable The pruning table array to be filled. Unvisited states
     * should be initialized to -1.
     * @param maxDepth The maximum distance (number of moves) to compute.
     * @param moveTable A pre-computed table where moveTable[state][move] gives the next state.
     * @param movesPerFace The number of turn types per face (e.g., 3 for R, R2, R').
     */
    public static void populatePruningTable(byte[] pruningTable, int maxDepth, short[][] moveTable, int movesPerFace) {
        int totalStates = pruningTable.length;
        int numFaces = moveTable[0].length;

        // This loop represents the layers of the Breadth-First Search.
        for (int currentDepth = 0; currentDepth < maxDepth; currentDepth++) {

            // Scan all possible states to find the ones at the current search depth.
            for (int currentStateCoord = 0; currentStateCoord < totalStates; currentStateCoord++) {
                if (pruningTable[currentStateCoord] == currentDepth) {

                    // For each state found, explore all possible next moves.
                    for (int faceIndex = 0; faceIndex < numFaces; faceIndex++) {
                        int nextStateCoord = currentStateCoord;

                        // Apply the move for each turn type (e.g., R, R2, R').
                        for (int turn = 0; turn < movesPerFace; turn++) {
                            nextStateCoord = moveTable[nextStateCoord][faceIndex];

                            // If this new state has not been visited yet...
                            if (pruningTable[nextStateCoord] < 0) {
                                // ...mark its distance as one greater than the current depth.
                                pruningTable[nextStateCoord] = (byte) (currentDepth + 1);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Populates a pruning table for a combined coordinate system using a BFS algorithm.
     * <p>
     * This method is used when a puzzle state is represented by two independent
     * coordinates that are packed into a single index. It explores all reachable
     * combined states and records their minimum distance from the solved state.
     *
     * @param pruningTable The pruning table array to be filled.
     * @param maxDepth The maximum distance (number of moves) to compute.
     * @param moveTableCoord1 The move table for the first coordinate.
     * @param moveTableCoord2 The move table for the second coordinate.
     * @param movesPerFace The number of turn types per face (e.g., 3 for R, R2, R').
     */
    public static void populatePruningTable(byte[] pruningTable, int maxDepth, short[][] moveTableCoord1,
                                            short[][] moveTableCoord2, int movesPerFace) {
        // Get the size of each individual coordinate space.
        int numStatesCoord1 = moveTableCoord1.length;
        int numStatesCoord2 = moveTableCoord2.length;
        int numFaces = moveTableCoord1[0].length;

        // This loop represents the layers of the Breadth-First Search.
        for (int currentDepth = 0; currentDepth < maxDepth; currentDepth++) {

            // Scan all possible combined states to find ones at the current depth.
            for (int coord1 = 0; coord1 < numStatesCoord1; coord1++) {
                for (int coord2 = 0; coord2 < numStatesCoord2; coord2++) {

                    // The two coordinates are packed into a single index.
                    if (pruningTable[coord1 * numStatesCoord2 + coord2] == currentDepth) {

                        // For each state found, explore all possible next moves.
                        for (int faceIndex = 0; faceIndex < numFaces; faceIndex++) {
                            int nextCoord1 = coord1;
                            int nextCoord2 = coord2;

                            // Apply the move for each turn type (e.g., R, R2, R').
                            for (int turn = 0; turn < movesPerFace; turn++) {
                                // Apply the same move to each coordinate independently.
                                nextCoord1 = moveTableCoord1[nextCoord1][faceIndex];
                                nextCoord2 = moveTableCoord2[nextCoord2][faceIndex];

                                // If the new combined state has not been visited yet...
                                if (pruningTable[nextCoord1 * numStatesCoord2 + nextCoord2] < 0) {
                                    // ...mark its distance as one greater than the current depth.
                                    pruningTable[nextCoord1 * numStatesCoord2 + nextCoord2] = (byte) (currentDepth + 1);
                                }
                            }
                        }
                    }
                }
            }

        }
    }

    /**
     * Populates a pruning table using a Breadth-First Search (BFS) algorithm.
     * <p>
     * This is an overloaded version that works with a move table of type char[][].
     * It starts from the solved state(s) (which must be pre-set to a distance of 0)
     * and explores all reachable states layer by layer, recording the minimum number
     * of moves required to reach each state.
     *
     * @param pruningTable The pruning table array to be filled. Unvisited states
     * should be initialized to -1.
     * @param maxDepth The maximum distance (number of moves) to compute.
     * @param moveTable A pre-computed table of type char[][] where moveTable[state][move]
     * gives the next state.
     * @param movesPerFace The number of turn types per face (e.g., 3 for R, R2, R').
     */
    public static void populatePruningTable(byte[] pruningTable, int maxDepth, char[][] moveTable, int movesPerFace) {
        int totalStates = pruningTable.length;
        int numFaces = moveTable[0].length;

        // This loop represents the layers of the Breadth-First Search.
        for (int currentDepth = 0; currentDepth < maxDepth; currentDepth++) {

            // Scan all possible states to find the ones at the current search depth.
            for (int currentStateCoord = 0; currentStateCoord < totalStates; currentStateCoord++) {
                if (pruningTable[currentStateCoord] == currentDepth) {

                    // For each state found, explore all possible next moves.
                    for (int faceIndex = 0; faceIndex < numFaces; faceIndex++) {
                        int nextStateCoord = currentStateCoord;

                        // Apply the move for each turn type (e.g., R, R2, R').
                        for (int k = 0; k < movesPerFace; k++) {
                            // Get the resulting state from the move table.
                            nextStateCoord = moveTable[nextStateCoord][faceIndex];

                            // If this new state has not been visited yet...
                            if (pruningTable[nextStateCoord] < 0) {
                                pruningTable[nextStateCoord] = (byte) (currentDepth + 1);
                            }
                        }
                    }
                }
            }
         }
    }
    // </editor-fold>

    //<editor-fold desc="Low-Level Utilities">
    //<editor-fold desc="Array Piece Manipulation">

    /**
     * Performs an in-place 4-element cyclic permutation on an array.
     * <p>
     * This method shifts the values at the given indices one position to the left,
     * with the first element wrapping around to the end. The cycle is:
     * posA ← posB ← posC ← posD ← posA.
     *
     * @param array The array to be modified.
     * @param posA  The index of the first element in the cycle.
     * @param posB  The index of the second element in the cycle.
     * @param posC  The index of the third element in the cycle.
     * @param posD  The index of the fourth element in the cycle.
     */    public static void circle(int[] array, int posA, int posB, int posC, int posD) {
        // Store the first element's value so it's not overwritten.
        int temp = array[posA];

        // Shift each element one position to the left in the cycle.
        array[posA] = array[posB];
        array[posB] = array[posC];
        array[posC] = array[posD];

        // Complete the cycle by placing the original first element at the end.
        array[posD] = temp;
    }

    /**
     * Performs an in-place 4-element cycle on an array while applying orientation changes.
     * <p>
     * This method shifts values like the basic circle function (posA ← posB ← posC ← posD ← posA),
     * but it also adds a corresponding value from the orientationChange array to each piece
     * as it moves to its new position.
     *
     * @param array The array of piece states to be modified.
     * @param posA  The index of the first element in the cycle.
     * @param posB  The index of the second element in the cycle.
     * @param posC  The index of the third element in the cycle.
     * @param posD  The index of the fourth element in the cycle.
     * @param orientationChange An array of 4 integers representing the orientation value
     * to add to each piece as it moves.
     */
    public static void circle(int[] array, int posA, int posB, int posC, int posD, int[] orientationChange) {
        // Store the first element's value so it's not overwritten.
        int temp = array[posA];

        // Shift each element one position to the left in the cycle,
        // adding the specified orientation change.
        array[posA] = array[posB] + orientationChange[0];
        array[posB] = array[posC] + orientationChange[1];
        array[posC] = array[posD] + orientationChange[2];

        // Complete the cycle, adding the final orientation change to the original first element.
        array[posD] = temp + orientationChange[3];
    }

    /**
     * Performs an in-place 3-element cyclic permutation on an array.
     * <p>
     * This method shifts the values at the given indices one position to the left,
     * with the first element wrapping around to the end. The cycle is:
     * posA ← posB ← posC ← posA.
     *
     * @param array The array to be modified.
     * @param posA  The index of the first element in the cycle.
     * @param posB  The index of the second element in the cycle.
     * @param posC  The index of the third element in the cycle.
     */
    public static void circle(int[] array, int posA, int posB, int posC) {
        // Store the first element's value so it's not overwritten.
        int temp = array[posA];

        // Shift each element one position to the left in the cycle

        array[posA] = array[posB];
        array[posB] = array[posC];

        // Complete the cycle by placing the original first element at the end.
        array[posC] = temp;
    }

    /**
     * Swaps two independent pairs of elements within an array.
     * <p>
     * This method performs two separate swaps: one between the elements at the first
     * two indices, and another between the elements at the third and fourth indices.
     *
     * @param array The array to be modified.
     * @param posA1 The index of the first element in the first pair to swap.
     * @param posB1 The index of the second element in the first pair to swap.
     * @param posA2 The index of the first element in the second pair to swap.
     * @param posB2 The index of the second element in the second pair to swap.
     */
    public static void swapTwoPairs(int[] array, int posA1, int posB1, int posA2, int posB2) {
        // --- Swap the first pair (posA1 <-> posB1) ---
        int temp = array[posA1];
        array[posA1] = array[posB1];
        array[posB1] = temp;

        // --- Swap the second pair (posA2 <-> posB2) ---
        temp = array[posA2];
        array[posA2] = array[posB2];
        array[posB2] = temp;
    }

    /**
     * Swaps a single pair of elements within an array.
     * <p>
     * This method performs a standard in-place swap of the values at the two
     * specified indices.
     *
     * @param array The array to be modified.
     * @param posA  The index of the first element to swap.
     * @param posB  The index of the second element to swap.
     */
    public static void swap(int[] array, int posA, int posB) {
        // Use a temporary variable to hold one value during the swap.
        int temp = array[posA];
        array[posA] = array[posB];
        array[posB] = temp;
    }
    //</editor-fold>

    //<editor-fold desc="Bit Manipulation">



    /**
     * Sets a single bit to 1 in a packed integer array (a bitmask).
     * <p>
     * This method uses bitwise operations to efficiently set a specific bit
     * at a given logical index without affecting any other bits.
     *
     * @param bitmaskArray The integer array acting as the bitmask, which will be modified.
     * @param bitIndex     The logical index of the bit to set to 1.
     */
    public static void setBit(int[] bitmaskArray, int bitIndex) {
        // 1. Find which integer in the array holds our bit.
        // An integer has 32 bits (2^5), so we divide the index by 32.
        // (bitIndex >> 5) is a fast way to do this.
        int arrayIndex = bitIndex >> 5;

        // 2. Find the position of our bit within that integer (0-31).
        // (bitIndex & 31) or (bitIndex & 0x1f) is a fast way to get the
        // remainder of a division by 32.
        int bitPosition = bitIndex & 31;

        // 3. Create a mask with a single '1' at the desired position.
        // (1 << bitPosition) creates a number like 00...1...00.
        int mask = 1 << bitPosition;

        // 4. Use the bitwise OR assignment operator (|=) to set the bit.
        // This operation sets the target bit to 1 and leaves all other bits unchanged.
        bitmaskArray[arrayIndex] |= mask;
    }

    /**
     * Gets the value of a single bit from a packed integer array (a bitmask).
     * <p>
     * This method uses bitwise operations to efficiently check if a specific bit
     * at a given logical index is set (1) or not (0).
     *
     * @param bitmaskArray The integer array acting as the bitmask.
     * @param bitIndex     The logical index of the bit to retrieve.
     * @return A non-zero integer if the bit is 1, and 0 if the bit is 0.
     */
    public static int getBit(int[] bitmaskArray, int bitIndex) {
        // 1. Find which integer in the array holds our bit.
        // An integer has 32 bits (2^5), so we divide the index by 32.
        // (bitIndex >> 5) is a fast way to do integer division by 32.
        int arrayElement = bitmaskArray[bitIndex >> 5];

        // 2. Find the position of our bit within that integer (0-31).
        // (bitIndex & 31) or (bitIndex & 0x1f) is a fast way to get the
        // remainder of a division by 32.
        int bitPosition = bitIndex & 31;

        // 3. Create a mask with a single '1' at the desired position.
        // (1 << bitPosition) creates a number like 00...1...00.
        int mask = 1 << bitPosition;

        // 4. Use the mask to check the value of the bit.
        // The bitwise AND will be non-zero only if the target bit is 1.
        return arrayElement & mask;
    }

    //</editor-fold>

    //<editor-fold desc="Specialized Permutations">
    /**
     * Decodes an index into a permutation of up to 8 elements using a fast,
     * low-level bitwise algorithm.
     * <p>
     * This method avoids traditional loops for tracking used elements by packing the
     * available items {7,6,5,4,3,2,1,0} into a single 32-bit integer and using
     * bit manipulation to "pick" and "remove" items.
     *
     * @param permArray The output array to be filled with the permutation.
     * @param length     The length of the permutation (must be <= 8).
     * @param index     The unique integer index to decode.
     */
    public static void set8Perm(int[] permArray, int length, int index) {
        // A 32-bit integer where each 4-bit nibble represents an available element.
        // Represents the ordered set {7, 6, 5, 4, 3, 2, 1, 0}.
        int packedElements = 0x76543210;

        // Decode the index using a factoradic (factorial number system) method.
        for (int i = 0; i < length - 1; i++) {
            // Get the factorial for the current position.
            int factorial = Utils.factorial[length - 1 - i];

            // Determine which available element to pick (this is the factoradic digit).
            int choiceIndex = index / factorial;
            index -= choiceIndex * factorial;

            // Get the bit offset for the chosen element (each element is 4 bits).
            int bitOffset = choiceIndex <<= 2;

            // "Pick" the element: shift the packed integer to get the chosen nibble
            // and mask it to get the value.
            permArray[i] = (packedElements >> bitOffset) & 0x7;

            // "Remove" the chosen element from the packed integer using a bitwise trick.
            // Create a mask for all bits to the right of the chosen element.
            int mask = (1 << choiceIndex) - 1;
            // Keep the right part, and shift the left part over to fill the gap.
            packedElements = (packedElements & mask) + ((packedElements >> 4) & ~mask);
        }
        permArray[length - 1] = packedElements & 0x7;
    }

    /*
    Example Scenario
    Imagine we have a simplified packedElements variable representing the set {3, 2, 1, 0}. The goal is to remove the element 2.

    packedElements = 0x3210

    In binary: 0011 0010 0001 0000

    The element 2 is the second nibble from the left. Its starting bit position (bitOffset) is 8.

    First, the code calculates the mask.  mask = (1 << bitOffset) - 1 = (1 << 8) - 1 = 256 - 1 = 255

    In binary, mask is 0000 0000 1111 1111.    The formula has two main parts that are added together.

    Part 1: (packedElements & mask) ! This part isolates everything to the right of the element we want to remove.

      0011 0010 0001 0000   (packedElements)
    & 0000 0000 1111 1111   (mask)
      ---------------------
      0000 0000 0001 0000   (Result 1 = 0x10)
    We have successfully kept the right side: {1, 0}.

    Part 2: ((packedElements >> 4) & ~mask)
    This part takes everything to the left of the element, shifts it right to fill the gap, and clears out the rest.

    Shift Left Side: First, packedElements is shifted right by 4 bits to align the left part.

    0011 0010 0001 0000 >> 4 becomes 0000 0011 0010 0001

    Invert the Mask: The mask ~mask keeps everything except the right side.

    ~0000 0000 1111 1111 becomes 1111 1111 0000 0000

    Combine Them:

      0000 0011 0010 0001   (The shifted value)
    & 1111 1111 0000 0000   (The inverted mask)
     ---------------------
      0000 0011 0000 0000   (Result 2 = 0x300)
    We have successfully kept the shifted left side: {3}.

    Part 3: Combine the Results
    Finally, the two parts are added together.

       0000 0000 0001 0000   (Result 1)
     + 0000 0011 0000 0000   (Result 2)
       ---------------------
       0000 0011 0001 0000   (Final Result)
    The final value is 0x310 in hexadecimal.


     */

    /**
     * Encodes a permutation of up to 8 elements into a unique integer index
     * using a fast, low-level bitwise algorithm.
     * <p>
     * This is the inverse of the `set8Perm` function. It converts a permutation
     * array into its factoradic representation to produce a compact integer index.
     *
     * @param permArray The input array containing the permutation.
     * @param length     The length of the permutation (must be <= 8).
     * @return The unique integer index for the permutation.
     */
    public static int get8Perm(int[] permArray, int length) {
        int index = 0; // The final index to be built.

        // A 32-bit integer where each 4-bit nibble represents an available element
        // from the ordered set {7, 6, 5, 4, 3, 2, 1, 0}.
        int packedElements = 0x76543210;

        // This loop builds the index using factoradic (factorial number system) logic.
        for (int i = 0; i < length - 1; i++) {
            // Get the bit offset for the current element in the permutation.
            int bitOffset = permArray[i] << 2;

            // This is the core factoradic calculation.
            // 1. (packedElements >> bitOffset) & 0x7: Finds the rank of the current
            //    element among the remaining available elements.
            // 2. (len - i) * idx + ...: Combines this rank with the previously
            //    calculated index value.
            index = (length - i) * index + ((packedElements >> bitOffset) & 0x7);

            // "Remove" the chosen element from the packed integer by subtracting
            // a mask. 0x11111110 is a clever mask that, when shifted,
            // decrements all elements to the left of the chosen one.
            packedElements -= 0x11111110 << bitOffset;
        }
        return index;
    }

    /**
     * Decodes an index into a permutation of up to 11 elements using a fast,
     * low-level bitwise algorithm on a 64-bit long.
     * <p>
     * This method packs the available items {10,9,..,0} into a single long and
     * uses bit manipulation to "pick" and "remove" items according to the
     * factoradic representation of the index.
     *
     * @param permArray The output array to be filled with the permutation.
     * @param index     The unique integer index to decode.
     * @param length     The length of the permutation (must be <= 11).
     */
    static void set11Perm(int[] permArray, int index, int length) {
        // A 64-bit long where each 4-bit nibble represents an available element.
        // Represents the ordered set {10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0}.
        long packedElements = 0xa9876543210L;

        // Decode the index using a factoradic (factorial number system) method.
        for (int i = 0; i < length - 1; i++) {
            // Get the factorial for the current position's weight.
            int factorial  = Utils.factorial[length - 1 - i];

            // Determine which available element to pick (this is the factoradic digit).
            int choiceIndex = index / factorial ;
            index %= factorial ; //

            // Get the bit offset for the chosen element (each element is 4 bits wide).
            int bitOffset = choiceIndex <<= 2;

            // "Pick" the element: shift the packed long to get the chosen nibble
            // and mask it with 0xf (15) to get its value.
            permArray[i] = (int) ((packedElements  >> choiceIndex) & 0xf);

            // "Remove" the chosen element from the packed long using a bitwise trick.
            // Create a mask for all bits to the right of the chosen element.
            long mask = (1L << bitOffset) - 1;
            // Keep the right part, and shift the left part over to fill the gap.
            packedElements  = (packedElements  & mask) + ((packedElements  >> 4) & ~mask);
        }
        // The last remaining nibble in the packed long is the final element.
        permArray[length - 1] = (int) (packedElements  & 0xf);
    }

    /**
     * Encodes a permutation of up to 11 elements into a unique integer index
     * using a fast, low-level bitwise algorithm.
     * <p>
     * This is the inverse of the `decodePermutationFast11` function. It converts a
     * permutation array into its factoradic representation to produce a compact integer index.
     *
     * @param permArray The input array containing the permutation.
     * @param length     The length of the permutation (must be <= 11).
     * @return The unique integer index for the permutation.
     */    static int get11Perm(int[] permArray, int length) {
        int index = 0; // The final index to be built.

        // A 64-bit long where each 4-bit nibble represents an available element
        // from the ordered set {10, 9, 8, ..., 0}.
        long packedElements = 0xa9876543210L;

        // This loop builds the index using factoradic (factorial number system) logic.
        for (int i = 0; i < length - 1; i++) {
            // Get the bit offset for the current element in the permutation.
            int bitOffset = permArray[i] << 2;

            // This is the core factoradic encoding calculation.
            // 1. `(packedElements >> bitOffset) & 0xf`: Finds the rank of the current
            //    element among the remaining available elements.
            // 2. `(len - i) * idx + ...`: Combines this rank with the previously
            //    calculated index value.
            index = (int) ((length - i) * index + ((packedElements >> bitOffset) & 0xf));

            // "Remove" the chosen element by subtracting a clever bitmask.
            // 0x11...10L is a mask that, when shifted, decrements all elements
            // to the left of the chosen one in the packed long.
            packedElements -= 0x11111111110L << bitOffset;
        }
        return index;
    }
    //</editor-fold>
    //</editor-fold>

    //<editor-fold desc="Solver and Visualization Helpers">

    /**
     * Determines the parity of a permutation by counting its inversions.
     * <p>
     * An inversion is any pair of elements (a, b) in the array such that a appears
     * before b, but a > b. A permutation is "even" if it has an even number of
     * inversions, and "odd" otherwise.
     *
     * @param permArray The input array containing the permutation.
     * @return True if the permutation is even, false if it is odd.
     */
    public static boolean permutationSign(int[] permArray) {
        int inversionCount = 0; // Counter for out-of-order pairs.

        // Use nested loops to check every unique pair of elements in the array.
        for (int i = 0; i < permArray.length; i++) {
            for (int j = i + 1; j < permArray.length; j++) {
                if (permArray[i] > permArray[j]) {
                    inversionCount++;
                }
            }
        }

        // The parity is even if the total number of inversions is even.
        return inversionCount % 2 == 0;
    }

    /**
     * Fills a facelet array based on the cube's permutation and orientation state.
     * <p>
     * This method translates the abstract coordinates of a solver (which piece is where,
     * and how is it twisted/flipped) into a visual representation of the 54 colored
     * stickers on the cube.
     *
     * @param pieceToFaceletsMap A lookup table where [pieceID] gives an array of the
     * corresponding facelet indices in the solved state.
     * @param faceletArray The output array of 54 characters to be filled with colors.
     * @param permutation An array where permutation[location] gives the ID of the piece
     * currently in that location.
     * @param orientation An array where orientation[location] gives the twist/flip state
     * of the piece in that location.
     * @param faceColors An array mapping a face index (0-5) to its color character (e.g., 'U', 'R').
     * @param stickersPerFace The number of stickers on one face (typically 9 for a 3x3x3).
     */    static void fillFacelet(byte[][] pieceToFaceletsMap, char[] faceletArray, int[] permutation,
                                   int[] orientation, char[] faceColors, int stickersPerFace) {
        // Iterate through each piece *location* on the cube (e.g., the UFR corner slot).
        for (int pieceLocation = 0; pieceLocation < pieceToFaceletsMap.length; pieceLocation++) {
            int numStickersOnPiece = pieceToFaceletsMap[pieceLocation].length;

            // Iterate through each sticker of the piece at the current location (e.g., for a corner, n=0,1,2).
            for (int stickerIndex = 0; stickerIndex < numStickersOnPiece; stickerIndex++) {

                // --- Determine the Destination Sticker on the Cube ---
                // The orientation value cyclically shifts where the stickers are placed.
                // For example, a twisted corner will have its sticker colors rotated.

                int destinationFaceletIndex = pieceToFaceletsMap[pieceLocation][(stickerIndex +
                        orientation[pieceLocation]) % numStickersOnPiece];

                // --- Determine the Source Color of that Sticker ---
                // 1. Find which piece is actually in the current location.
                int pieceId = permutation[pieceLocation];
                // 2. Find the original solved-state position of this sticker.
                int sourceFaceletIndex = pieceToFaceletsMap[pieceId][stickerIndex];
                // 3. Determine the face (and thus color) of that original sticker.
                char sourceColor = faceColors[sourceFaceletIndex / stickersPerFace];

                // --- Assign the Color to the Destination ---
                faceletArray[destinationFaceletIndex] = sourceColor;
            }

        }
    }
    // </editor-fold>

    //<editor-fold desc="Data Serialization (File I/O)">

    //<editor-fold desc="Read Methods (from InputStream)">

    /**
     * Reads a char array from an InputStream, deserializing it from a binary format.
     * <p>
     * This method assumes the data in the stream is a sequence of bytes representing
     * 16-bit characters in little-endian order (low byte first, high byte second).
     *
     * @param destinationArray The char array to be filled with the read data.
     * @param inputStream      The input stream to read from (e.g., a file stream).
     * @throws IOException If an I/O error occurs.
     */
    public static void read(char[] destinationArray, InputStream inputStream) throws IOException {
        int arrayLength = destinationArray.length;

        // Create a byte buffer to hold the raw data.
        // Each char is 2 bytes, so the buffer is twice the array length.
        byte[] byteBuffer = new byte[arrayLength * 2];

        // Read the entire block of data from the stream into the buffer.
        inputStream.read(byteBuffer);

        // Reconstruct each char from its two corresponding bytes.
        for (int i = 0; i < arrayLength; i++) {
            // Get the low byte (first byte) for the character.
            int lowByte = byteBuffer[i * 2] & 0xff;

            // Get the high byte (second byte) and shift it into position.
            int highByte = (byteBuffer[i * 2 + 1] << 8) & 0xff00;

            // Combine the low and high bytes to form the 16-bit char.
            destinationArray[i] = (char) (lowByte | highByte);
        }
    }

    /**
     * Reads an int array from an InputStream, deserializing it from a binary format.
     * <p>
     * This method assumes the data in the stream is a sequence of bytes representing
     * 32-bit integers in little-endian order (least significant byte first).
     *
     * @param destinationArray The int array to be filled with the read data.
     * @param inputStream      The input stream to read from (e.g., a file stream).
     * @throws IOException If an I/O error occurs.
     */
    public static void read(int[] destinationArray, InputStream inputStream) throws IOException {
        int arrayLength = destinationArray.length;

        // Create a byte buffer to hold the raw data.
        // Each int is 4 bytes, so the buffer is four times the array length.
        byte[] byteBuffer = new byte[arrayLength * 4];

        // Read the entire block of data from the stream into the buffer.
        inputStream.read(byteBuffer);

        // Reconstruct each integer from its four corresponding bytes.
        for (int i = 0; i < arrayLength; i++) {
            int baseIndex = i * 4;

            // Get the four bytes for the current integer.
            int byte0 = byteBuffer[baseIndex] & 0xff;
            int byte1 = (byteBuffer[baseIndex + 1] << 8) & 0xff00;
            int byte2 = (byteBuffer[baseIndex + 2] << 16) & 0xff0000;
            int byte3 = (byteBuffer[baseIndex + 3] << 24) & 0xff000000;

            // Combine the four bytes to form the 32-bit integer.
            destinationArray[i] = byte0 | byte1 | byte2 | byte3;
        }
    }

    /**
     * Reads a 2D char array from an InputStream, deserializing it row by row.
     * <p>
     * This method assumes the data in the stream is a sequence of rows, where each
     * row is a block of bytes representing 16-bit characters in little-endian order.
     *
     * @param destinationArray The 2D char array to be filled with the read data.
     * @param inputStream      The input stream to read from (e.g., a file stream).
     * @throws IOException If an I/O error occurs.
     */
    public static void read(char[][] destinationArray, InputStream inputStream) throws IOException {
        // Determine the dimensions of the 2D array.
        int numRows = destinationArray.length;
        int rowLength = destinationArray[0].length;

        // Create a byte buffer to hold the data for one single row.
        // Each char is 2 bytes, so the buffer is twice the row's length.
        byte[] byteBuffer = new byte[rowLength * 2];

        // Iterate through each row of the destination array.
        for (int rowIndex = 0; rowIndex < numRows; rowIndex++) {

            // Read the entire block of data for the current row into the buffer.
            inputStream.read(byteBuffer);

            // Reconstruct each char in the row from its two corresponding bytes.
            for (int colIndex = 0; colIndex < rowLength; colIndex++) {
                int baseIndex = colIndex * 2;

                // Get the low byte (first byte) for the character.
                int lowByte = byteBuffer[baseIndex] & 0xff;

                // Get the high byte (second byte) and shift it into position.
                int highByte = (byteBuffer[baseIndex + 1] << 8) & 0xff00;

                // Combine the low and high bytes to form the 16-bit char.
                destinationArray[rowIndex][colIndex] = (char) (lowByte | highByte);
            }
        }
    }

    /**
     * Reads a 2D short array from an InputStream, deserializing it column by column.
     * <p>
     * This method is specifically designed for a 2D array with 6 columns. It assumes
     * the data in the stream is a sequence of columns, where each column is a block of
     * bytes representing 16-bit shorts in little-endian order.
     *
     * @param destinationArray The 2D short array to be filled with the read data.
     * @param inputStream      The input stream to read from (e.g., a file stream).
     * @throws IOException If an I/O error occurs.
     */
    public static void read(short[][] destinationArray, InputStream inputStream) throws IOException {
        // Determine the dimensions of the 2D array.

        int numRows = destinationArray.length;

        // Create a byte buffer to hold the data for one single column.
        // Each short is 2 bytes, so the buffer is twice the column's length (number of rows).
        byte[] byteBuffer = new byte[numRows * 2];

        // Iterate through each column of the destination array.
        for (int colIndex = 0; colIndex < 6; colIndex++) {

            // Read the entire block of data for the current column into the buffer.
            inputStream.read(byteBuffer);

            // Reconstruct each short in the column from its two corresponding bytes.
            for (int rowIndex  = 0; rowIndex  < numRows; rowIndex ++) {
                int baseIndex = rowIndex * 2;

                // Get the low byte (first byte) for the short.
                int lowByte = byteBuffer[baseIndex] & 0xff;

                // Get the high byte (second byte) and shift it into position.
                int highByte = (byteBuffer[baseIndex + 1] << 8) & 0xff00;

                // Combine the low and high bytes to form the 16-bit short.
                destinationArray[rowIndex][colIndex] = (short) (lowByte | highByte);            }
        }
    }

    /**
     * Reads an int array from an InputStream, deserializing it from a chunked,
     * big-endian binary format.
     * <p>
     * This method reads data in fixed-size chunks and reconstructs 32-bit integers
     * assuming big-endian byte order (most significant byte first).
     *
     * @param destinationArray The int array to be filled with the read data.
     * @param chunkSize        The number of integers in each chunk of data in the stream.
     * @param inputStream      The input stream to read from (e.g., a file stream).
     * @throws IOException If an I/O error occurs.
     */
    public static void read(int[] destinationArray, int chunkSize, InputStream inputStream) throws IOException {
        int numChunks = destinationArray.length / chunkSize;

        // Create a byte buffer to hold the data for one single chunk.
        // Each int is 4 bytes.
        byte[] byteBuffer = new byte[chunkSize * 4];

        int destinationIndex = 0; // Tracks the current position in the destinationArray.

        // Iterate through each chunk of data in the stream.
        for (int i = 0; i < numChunks; i++) {

            // Reconstruct each integer in the chunk from its four corresponding bytes.
            inputStream.read(byteBuffer);
            for (int j = 0; j < chunkSize; j++) {
                int baseIndex = j * 4;

                // Get the four bytes for the current integer. Note the big-endian order.
                int byte3 = (byteBuffer[baseIndex] << 24) & 0xff000000;     // Most significant byte
                int byte2 = (byteBuffer[baseIndex + 1] << 16) & 0xff0000;
                int byte1 = (byteBuffer[baseIndex + 2] << 8) & 0xff00;
                int byte0 = byteBuffer[baseIndex + 3] & 0xff;               // Least significant byte

                // Combine the four bytes to form the 32-bit integer.
                destinationArray[destinationIndex++] = byte3 | byte2 | byte1 | byte0;
            }

        }
    }

    /**
     * Reads a 2D int array from an InputStream, deserializing it row by row.
     * <p>
     * This method assumes the data in the stream is a sequence of rows, where each
     * row is a block of bytes representing 32-bit integers in little-endian order
     * (least significant byte first).
     *
     * @param targetArray The 2D int array to be filled with the read data.
     * @param numRows          The number of rows in the 2D array (height).
     * @param numCols          The number of columns in the 2D array (width).
     * @param inputStream      The input stream to read from (e.g., a file stream).
     * @throws Exception If an I/O error occurs.
     */
    public static void read(int[][] targetArray, int numRows, int numCols, InputStream inputStream) throws Exception {
        byte[] byteBuffer = new byte[numCols * 4];
        for (int rowIndex = 0; rowIndex  < numRows; rowIndex ++) {
            inputStream.read(byteBuffer);
            for (int colIndex = 0; colIndex < numCols; colIndex++) {
                int baseIndex = colIndex * 4;

                // Get the four bytes for the current integer in little-endian order.
                int byte0 = byteBuffer[baseIndex] & 0xff;               // Least significant byte
                int byte1 = (byteBuffer[baseIndex + 1] << 8) & 0xff00;
                int byte2 = (byteBuffer[baseIndex + 2] << 16) & 0xff0000;
                int byte3 = (byteBuffer[baseIndex + 3] << 24) & 0xff000000; // Most significant byte

                // Combine the four bytes to form the 32-bit integer.
                targetArray[rowIndex][colIndex] = byte0 | byte1 | byte2 | byte3;
            }
        }
    }
    //</editor-fold>

    //<editor-fold desc="Write Methods (to OutputStream)">

    /**
     * Writes a char array to an OutputStream, serializing it into a binary format.
     * <p>
     * This method converts a char array into a byte array, storing each 16-bit
     * character as two bytes in little-endian order (low byte first, high byte second).
     *
     * @param sourceArray The char array to be serialized.
     * @param outputStream The output stream to write to (e.g., a file stream).
     * @throws IOException If an I/O error occurs.
     */
    public static void write(char[] sourceArray, OutputStream outputStream) throws IOException {
        int arrayLength = sourceArray.length;

        // Create a byte buffer to hold the serialized data.
        // Each char will be converted into 2 bytes.
        byte[] byteBuffer = new byte[arrayLength * 2];

        int bufferIndex = 0;// Tracks the current position in the byte buffer.

        // Deconstruct each char into its two corresponding bytes.
        for (char currentChar : sourceArray) {
            // Write the low byte (the first 8 bits).
            byteBuffer[bufferIndex++] = (byte) (currentChar & 0xff);

            // Write the high byte (the next 8 bits) using an unsigned right shift.
            byteBuffer[bufferIndex++] = (byte) ((currentChar >>> 8) & 0xff);
        }

        // Write the entire byte buffer to the stream in one operation.
        outputStream.write(byteBuffer);
    }

    /**
     * Writes an int array to an OutputStream, serializing it into a binary format.
     * <p>
     * This method converts an int array into a byte array, storing each 32-bit
     * integer as four bytes in little-endian order (least significant byte first).
     *
     * @param sourceArray The int array to be serialized.
     * @param outputStream The output stream to write to (e.g., a file stream).
     * @throws IOException If an I/O error occurs.
     */
    public static void write(int[] sourceArray, OutputStream outputStream) throws IOException {
        int arrayLength = sourceArray.length;

        // Create a byte buffer to hold the serialized data.
        // Each int will be converted into 4 bytes.
        byte[] buf = new byte[arrayLength * 4];

        int bufferIndex = 0; // Tracks the current position in the byte buffer.

        // Deconstruct each integer into its four corresponding bytes.
        for (int currentInt : sourceArray) {
            buf[bufferIndex++] = (byte) (currentInt & 0xff);
            buf[bufferIndex++] = (byte) ((currentInt >>> 8) & 0xff);
            buf[bufferIndex++] = (byte) ((currentInt >>> 16) & 0xff);
            buf[bufferIndex++] = (byte) ((currentInt >>> 24) & 0xff);
        }

        // Write the entire byte buffer to the stream in one operation.
        outputStream.write(buf);
    }

    /**
     * Writes a 2D char array to an OutputStream, serializing it row by row.
     * <p>
     * This method converts a 2D char array into a byte stream, storing each 16-bit
     * character as two bytes in little-endian order (low byte first, high byte second).
     * It processes and writes the data one complete row at a time.
     *
     * @param sourceArray The 2D char array to be serialized.
     * @param outputStream The output stream to write to (e.g., a file stream).
     * @throws IOException If an I/O error occurs.
     */
    public static void write(char[][] sourceArray, OutputStream outputStream) throws IOException {
        // Determine the dimensions of the 2D array.
        int numRows = sourceArray.length;
        int rowLength = sourceArray[0].length;

        // Create a byte buffer that can hold one single row of data.
        // Each char will be converted into 2 bytes.
        byte[] byteBuffer = new byte[numRows * 2];

        // Iterate through each row of the source array.
        for (int rowIndex = 0; rowIndex < numRows; rowIndex++) {
            int bufferIndex = 0; // Reset buffer index for each new row.

            // Deconstruct each char in the current row into its two bytes.
            for (int colIndex = 0; colIndex < numRows; colIndex++) {
                char currentChar = sourceArray[rowIndex][colIndex];

                // Write the low byte (the first 8 bits).
                byteBuffer[bufferIndex++] = (byte) (currentChar & 0xff);

                // Write the high byte (the next 8 bits) using an unsigned right shift.
                byteBuffer[bufferIndex++] = (byte) ((currentChar >>> 8) & 0xff);
            }
            // Write the entire byte buffer for the current row to the stream.
            outputStream.write(byteBuffer);
        }
    }

    /**
     * Writes a 2D short array to an OutputStream, serializing it column by column.
     * <p>
     * This method is specifically designed for a 2D array with 6 columns. It converts
     * the array into a byte stream by processing and writing one complete column at a time.
     * Each 16-bit short is stored as two bytes in little-endian order.
     *
     * @param sourceArray The 2D short array to be serialized.
     * @param outputStream The output stream to write to (e.g., a file stream).
     * @throws IOException If an I/O error occurs.
     */
    public static void write(short[][] sourceArray, OutputStream outputStream) throws IOException {
        // The number of rows is determined by the length of the first dimension.
        int numRows = sourceArray.length;

        // Create a byte buffer that can hold one single column of data.
        // Each short is 2 bytes, so the buffer is twice the column's length (numRows).
        byte[] byteBuffer = new byte[numRows * 2];

        for (int colIndex = 0; colIndex < 6; colIndex++) {
            int bufferIndex = 0; // Reset buffer index for each new column.

            // Deconstruct each short in the current column into its two bytes.
            for (int rowIndex = 0; rowIndex < numRows; rowIndex++) {
                short currentShort = sourceArray[rowIndex][colIndex];

                // Write the low byte (the first 8 bits).
                byteBuffer[bufferIndex++] = (byte) (currentShort & 0xff);

                // Write the high byte (the next 8 bits) using an unsigned right shift.
                byteBuffer[bufferIndex++] = (byte) ((currentShort >>> 8) & 0xff);
            }
            // Write the entire byte buffer for the current column to the stream.
            outputStream.write(byteBuffer);
        }
    }

    /**
     * Writes an int array to an OutputStream, serializing it in a chunked,
     * big-endian binary format.
     * <p>
     * This method processes the source array in fixed-size chunks, converting each
     * 32-bit integer into four bytes using big-endian byte order (most significant
     * byte first) before writing to the stream.
     *
     * @param sourceArray The int array to be serialized.
     * @param chunkSize   The number of integers in each chunk of data to be written.
     * @param outputStream The output stream to write to (e.g., a file stream).
     * @throws Exception If an I/O error occurs.
     */
    public static void write(int[] sourceArray, int chunkSize, OutputStream outputStream) throws Exception {
        int numChunks = sourceArray.length / chunkSize;

        // Create a byte buffer to hold the data for one single chunk.
        // Each int is 4 bytes.
        byte[] byteBuffer = new byte[chunkSize * 4];

        int sourceIndex = 0; // Tracks the current position in the sourceArray.
        for (int i = 0; i < numChunks; i++) {
            int bufferIndex = 0; // Reset buffer index for each new chunk.

            // Deconstruct each integer in the current chunk into its four bytes.
            for (int j = 0; j < chunkSize; j++) {
                int currentInt = sourceArray[sourceIndex++];
                // Write the four bytes in big-endian order.
                byteBuffer[bufferIndex++] = (byte) ((currentInt >>> 24) & 0xff); // Byte 3 (Most significant)
                byteBuffer[bufferIndex++] = (byte) ((currentInt >>> 16) & 0xff); // Byte 2
                byteBuffer[bufferIndex++] = (byte) ((currentInt >>> 8) & 0xff); // Byte 1
                byteBuffer[bufferIndex++] = (byte) (currentInt & 0xff); // Byte 0 (Least significant)
            }

            // Write the entire byte buffer for the current chunk to the stream.
            outputStream.write(byteBuffer);
        }
    }

    /**
     * Writes a 2D int array to an OutputStream, serializing it row by row.
     * <p>
     * This method converts a 2D int array into a byte stream, storing each 32-bit
     * integer as four bytes in little-endian order (least significant byte first).
     * It processes and writes the data one complete row at a time.
     *
     * @param sourceArray The 2D int array to be serialized.
     * @param numRows The number of rows in the 2D array (height).
     * @param numCols The number of columns in the 2D array (width).
     * @param outputStream The output stream to write to (e.g., a file stream).
     * @throws Exception If an I/O error occurs.
     */
    public static void write(int[][] sourceArray, int numRows, int numCols, OutputStream outputStream) throws Exception {

        // Create a byte buffer that can hold one single row of data.
        // Each int is 4 bytes.
        byte[] byteBuffer = new byte[numCols * 4];

        // Iterate through each row of the source array.
        for (int rowIndex = 0; rowIndex < numRows; rowIndex++) {
            int bufferIndex = 0; // Reset buffer index for each new row.

            // Deconstruct each integer in the current row into its four bytes.
            for (int colIndex = 0; colIndex < numCols; colIndex++) {
                int currentInt = sourceArray[rowIndex][colIndex];

                // Write the four bytes in little-endian order.
                byteBuffer[bufferIndex++] = (byte) (currentInt & 0xff);  // Byte 0 (Least significant)
                byteBuffer[bufferIndex++] = (byte) ((currentInt >>> 8) & 0xff); // Byte 1
                byteBuffer[bufferIndex++] = (byte) ((currentInt >>> 16) & 0xff); // Byte 2
                byteBuffer[bufferIndex++] = (byte) ((currentInt >>> 24) & 0xff); // Byte 3 (Most significant)
            }

            // Write the entire byte buffer for the current row to the stream.
            outputStream.write(byteBuffer);
        }
    }

    //</editor-fold>
    //</editor-fold>

}
