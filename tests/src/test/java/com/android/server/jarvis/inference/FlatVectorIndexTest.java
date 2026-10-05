package com.android.server.jarvis.inference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** FlatVectorIndex: the vector index used by engines that have none of their own. */
class FlatVectorIndexTest {

    @TempDir Path dir;

    private static final float[] EAST  = {1f, 0f, 0f};
    private static final float[] NORTH = {0f, 1f, 0f};
    private static final float[] UP    = {0f, 0f, 1f};
    private static final float[] NORTH_EAST = {0.7071f, 0.7071f, 0f};

    private FlatVectorIndex open(int dim) {
        FlatVectorIndex index = FlatVectorIndex.open(dir.toString(), dim);
        assertNotNull(index);
        return index;
    }

    @Test
    @DisplayName("Query returns the nearest vectors, best first")
    void nearestFirst() {
        FlatVectorIndex index = open(3);
        index.add(1, EAST);
        index.add(2, NORTH);
        index.add(3, UP);
        assertArrayEquals(new int[] {1, 2}, index.query(new float[] {0.9f, 0.4f, 0f}, 2));
        assertArrayEquals(new int[] {3}, index.query(UP, 1));
    }

    @Test
    @DisplayName("Asking for more than there are returns them all")
    void topKLargerThanIndex() {
        FlatVectorIndex index = open(3);
        index.add(1, EAST);
        assertArrayEquals(new int[] {1}, index.query(NORTH_EAST, 5));
    }

    @Test
    @DisplayName("An empty index answers with nothing")
    void emptyIndex() {
        assertEquals(0, open(3).query(EAST, 3).length);
    }

    @Test
    @DisplayName("Adding an id again replaces its vector")
    void addReplaces() {
        FlatVectorIndex index = open(3);
        index.add(1, EAST);
        index.add(2, NORTH_EAST);
        index.add(1, UP);
        assertEquals(2, index.size());
        assertArrayEquals(new int[] {2}, index.query(EAST, 1));
    }

    @Test
    @DisplayName("Deleted ids no longer come back")
    void delete() {
        FlatVectorIndex index = open(3);
        index.add(1, EAST);
        index.add(2, NORTH);
        assertTrue(index.delete(new int[] {1, 99}));
        assertArrayEquals(new int[] {2}, index.query(EAST, 2));
    }

    @Test
    @DisplayName("A vector of the wrong size is refused")
    void wrongDimension() {
        FlatVectorIndex index = open(3);
        assertFalse(index.add(1, new float[] {1f, 0f}));
        assertEquals(0, index.query(new float[] {1f, 0f}, 1).length);
    }

    @Test
    @DisplayName("Vectors survive closing and reopening")
    void persists() {
        FlatVectorIndex first = open(3);
        first.add(7, NORTH);
        first.add(8, EAST);
        first.delete(new int[] {8});

        FlatVectorIndex second = open(3);
        assertEquals(1, second.size());
        assertArrayEquals(new int[] {7}, second.query(NORTH, 3));
    }

    @Test
    @DisplayName("An index written by a model of another dimension is not reused")
    void differentDimensionStartsEmpty() {
        open(3).add(1, EAST);
        FlatVectorIndex other = open(4);
        assertEquals(0, other.size());
        assertTrue(other.add(1, new float[] {1f, 0f, 0f, 0f}));
    }

    @Test
    @DisplayName("A damaged file gives an empty index, not a crash")
    void damagedFile() throws Exception {
        Files.write(new File(dir.toFile(), "vectors.bin").toPath(), new byte[] {1, 2, 3});
        assertEquals(0, open(3).size());
    }

    @Test
    @DisplayName("The caller's array can be changed afterwards without changing the index")
    void copiesInput() {
        FlatVectorIndex index = open(3);
        float[] vector = EAST.clone();
        index.add(1, vector);
        vector[0] = 0f;
        vector[2] = 1f;
        assertArrayEquals(new int[] {1}, index.query(EAST, 1));
        index.add(2, UP);
        assertEquals(1, index.query(EAST, 1)[0]);
    }
}
