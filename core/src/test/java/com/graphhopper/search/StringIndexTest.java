package com.graphhopper.search;

import com.carrotsearch.hppc.LongArrayList;
import com.graphhopper.storage.RAMDirectory;
import com.graphhopper.util.Helper;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;

import static com.graphhopper.search.StringIndex.MAX_UNIQUE_KEYS;
import static org.junit.jupiter.api.Assertions.*;

public class StringIndexTest {

    private final static String location = "./target/stringindex-store";

    private StringIndex create() {
        return new StringIndex(new RAMDirectory(), 1000).create(1000);
    }

    Map<String, Object> createMap(String... strings) {
        if (strings.length % 2 != 0)
            throw new IllegalArgumentException("Cannot create map from strings " + Arrays.toString(strings));
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < strings.length; i += 2) {
            map.put(strings[i], strings[i + 1]);
        }
        return map;
    }

    @Test
    public void putSame() {
        StringIndex index = create();
        long aPointer = index.add(createMap("a", "same name", "b", "same name"));

        assertNull(index.get(aPointer, ""));
        assertEquals("same name", index.get(aPointer, "a"));
        assertEquals("same name", index.get(aPointer, "b"));
        assertNull(index.get(aPointer, "c"));

        index = create();
        aPointer = index.add(createMap("a", "a name", "b", "same name"));
        assertEquals("a name", index.get(aPointer, "a"));
    }

    @Test
    public void putAB() {
        StringIndex index = create();
        long aPointer = index.add(createMap("a", "a name", "b", "b name"));

        assertNull(index.get(aPointer, ""));
        assertEquals("a name", index.get(aPointer, "a"));
        assertEquals("b name", index.get(aPointer, "b"));
    }

    @Test
    public void putEmpty() {
        StringIndex index = create();
        assertEquals(1, index.add(createMap("", "")));
        assertEquals(5, index.add(createMap("", null)));
        // cannot store null value if it is the first value of the key:
        assertThrows(IllegalArgumentException.class, () -> index.add(createMap("blup", null)));
        assertThrows(IllegalArgumentException.class, () -> index.add(createMap(null, null)));

        assertNull(index.get(0, ""));

        assertEquals(9, index.add(createMap("else", "else")));
    }

    @Test
    public void putMany() {
        StringIndex index = create();
        long aPointer = 0, tmpPointer = 0;

        for (int i = 0; i < 10000; i++) {
            aPointer = index.add(createMap("a", "a name " + i, "b", "b name " + i, "c", "c name " + i));
            if (i == 567)
                tmpPointer = aPointer;
        }

        assertEquals("b name 9999", index.get(aPointer, "b"));
        assertEquals("c name 9999", index.get(aPointer, "c"));

        assertEquals("a name 567", index.get(tmpPointer, "a"));
        assertEquals("b name 567", index.get(tmpPointer, "b"));
        assertEquals("c name 567", index.get(tmpPointer, "c"));
    }

    @Test
    public void putManyKeys() {
        StringIndex index = create();
        // one key is already stored => empty key
        for (int i = 1; i < MAX_UNIQUE_KEYS; i++) {
            index.add(createMap("a" + i, "a name"));
        }
        try {
            index.add(createMap("new", "a name"));
            fail();
        } catch (IllegalArgumentException ex) {
        }
    }

    @Test
    public void putDuplicate() {
        StringIndex index = create();
        long aPointer = index.add(createMap("a", "longer name", "b", "longer name"));
        long bPointer = index.add(createMap("c", "longer other name"));
        // value storage: 1 byte for count, 2 bytes for keyIndex and 4 bytes for delta of dup_marker and 3 bytes (keyIndex + length for "longer name")
        assertEquals(aPointer + 1 + (2 + 4) + 3 + "longer name".getBytes(Helper.UTF_CS).length, bPointer);
        // no de-duplication as too short:
        long cPointer = index.add(createMap("temp", "temp"));
        assertEquals(bPointer + 1 + 3 + "longer other name".getBytes(Helper.UTF_CS).length, cPointer);
        assertEquals("longer name", index.get(aPointer, "a"));
        assertEquals("longer name", index.get(aPointer, "b"));
        assertEquals("longer other name", index.get(bPointer, "c"));
        assertEquals("temp", index.get(cPointer, "temp"));

        index = create();
        index.add(createMap("a", "longer name", "b", "longer name"));
        bPointer = index.add(createMap("a", "longer name", "b", "longer name"));
        cPointer = index.add(createMap("a", "longer name", "b", "longer name"));
        assertEquals(bPointer, cPointer);

        assertEquals("{a=longer name, b=longer name}", index.getAll(aPointer).toString());
        assertEquals("{a=longer name, b=longer name}", index.getAll(cPointer).toString());
    }

    @Test
    public void testNoErrorOnLargeName() {
        StringIndex index = create();
        // 127 => bytes.length == 254
        String str = "";
        for (int i = 0; i < 127; i++) {
            str += "ß";
        }
        long result = index.add(createMap("", str));
        assertEquals(127, ((String) index.get(result, "")).length());
    }

    @Test
    public void testTooLongNameNoError() {
        StringIndex index = create();
        index.throwExceptionIfTooLong = true;
        try {
            index.add(createMap("", "Бухарестская улица (http://ru.wikipedia.org/wiki/%D0%91%D1%83%D1%85%D0%B0%D1%80%D0%B5%D1%81%D1%82%D1%81%D0%BA%D0%B0%D1%8F_%D1%83%D0%BB%D0%B8%D1%86%D0%B0_(%D0%A1%D0%B0%D0%BD%D0%BA%D1%82-%D0%9F%D0%B5%D1%82%D0%B5%D1%80%D0%B1%D1%83%D1%80%D0%B3))"));
            fail();
        } catch (IllegalStateException ex) {
        }

        String str = "sdfsdfds";
        for (int i = 0; i < 256 * 3; i++) {
            str += "Б";
        }
        try {
            index.add(createMap("", str));
            fail();
        } catch (IllegalStateException ex) {
        }

        index.throwExceptionIfTooLong = false;
        long pointer = index.add(createMap("", "Бухарестская улица (http://ru.wikipedia.org/wiki/%D0%91%D1%83%D1%85%D0%B0%D1%80%D0%B5%D1%81%D1%82%D1%81%D0%BA%D0%B0%D1%8F_%D1%83%D0%BB%D0%B8%D1%86%D0%B0_(%D0%A1%D0%B0%D0%BD%D0%BA%D1%82-%D0%9F%D0%B5%D1%82%D0%B5%D1%80%D0%B1%D1%83%D1%80%D0%B3))"));
        assertTrue(((String) index.get(pointer, "")).startsWith("Бухарестская улица (h"));
    }

    @Test
    public void testFlush() {
        Helper.removeDir(new File(location));

        StringIndex index = new StringIndex(new RAMDirectory(location, true).create(), 1000);
        long pointer = index.add(createMap("", "test"));
        index.flush();
        index.close();

        index = new StringIndex(new RAMDirectory(location, true), 1000);
        assertTrue(index.loadExisting());
        assertEquals("test", index.get(pointer, ""));
        // make sure bytePointer is correctly set after loadExisting
        long newPointer = index.add(createMap("", "testing"));
        assertEquals(pointer + 1 + 3 + "test".getBytes().length, newPointer, newPointer + ">" + pointer);
        index.close();

        Helper.removeDir(new File(location));
    }

    @Test
    public void testLoadKeys() {
        Helper.removeDir(new File(location));

        StringIndex index = new StringIndex(new RAMDirectory(location, true).create(), 1000).create(1000);
        long pointerA = index.add(createMap("c", "test value"));
        assertEquals(2, index.getKeys().size());
        long pointerB = index.add(createMap("a", "value", "b", "another value"));
        // empty string is always the first key
        assertEquals("[, c, a, b]", index.getKeys().toString());
        index.flush();
        index.close();

        index = new StringIndex(new RAMDirectory(location, true), 1000);
        assertTrue(index.loadExisting());
        assertEquals("[, c, a, b]", index.getKeys().toString());
        assertEquals("test value", index.get(pointerA, "c"));
        assertNull(index.get(pointerA, "b"));

        assertNull(index.get(pointerB, ""));
        assertEquals("value", index.get(pointerB, "a"));
        assertEquals("another value", index.get(pointerB, "b"));
        assertEquals("{a=value, b=another value}", index.getAll(pointerB).toString());
        index.close();

        Helper.removeDir(new File(location));
    }

    @Test
    public void testEmptyKey() {
        StringIndex index = create();
        long pointerA = index.add(createMap("", "test value"));
        long pointerB = index.add(createMap("a", "value", "b", "another value"));

        assertEquals("test value", index.get(pointerA, ""));
        assertNull(index.get(pointerA, "a"));

        assertEquals("value", index.get(pointerB, "a"));
        assertNull(index.get(pointerB, ""));
    }

    @RepeatedTest(20)
    public void testRandom() {
        final long seed = new Random().nextLong();
        try {
            StringIndex index = new StringIndex(new RAMDirectory(location, true).create()).create(1000);
            Random random = new Random(seed);
            List<String> keys = createRandomStringList(random, "_key", 100);
            List<Integer> values = createRandomList(random, 500);

            int size = 10000;
            LongArrayList pointers = new LongArrayList(size);
            for (int i = 0; i < size; i++) {
                Map<String, Object> map = createRandomMap(random, keys, values);
                long pointer = index.add(map);
                try {
                    assertEquals(map.size(), index.getAll(pointer).size(), "" + i);
                } catch (Exception ex) {
                    throw new RuntimeException(i + " " + map + ", " + pointer, ex);
                }
                pointers.add(pointer);
            }

            for (int i = 0; i < size; i++) {
                Map<String, Object> map = index.getAll(pointers.get(i));
                assertTrue(map.size() > 0, i + " " + map);
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    Object value = index.get(pointers.get(i), entry.getKey());
                    assertEquals(entry.getValue(), value, i + " " + map);
                }
            }
            index.flush();
            index.close();

            index = new StringIndex(new RAMDirectory(location, true).create());
            assertTrue(index.loadExisting());
            for (int i = 0; i < size; i++) {
                Map<String, Object> map = index.getAll(pointers.get(i));
                assertTrue(map.size() > 0, i + " " + map);
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    Object value = index.get(pointers.get(i), entry.getKey());
                    assertEquals(entry.getValue(), value, i + " " + map);
                }
            }
            index.close();
        } catch (Throwable t) {
            throw new RuntimeException("EdgeKVStorageTest.testRandom seed:" + seed, t);
        }
    }

    private List<Integer> createRandomList(Random random, int size) {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(random.nextInt(size * 5));
        }
        return list;
    }

    private List<String> createRandomStringList(Random random, String postfix, int size) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(random.nextInt(size * 5) + postfix);
        }
        return list;
    }

    private Map<String, Object> createRandomMap(Random random, List<String> keys, List<Integer> values) {
        int count = random.nextInt(10) + 2;
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < count; i++) {
            String key = keys.get(random.nextInt(keys.size()));
            Object o = values.get(random.nextInt(values.size()));
            map.put(key, key.endsWith("_s") ? o + "_s" : o);
        }
        return map;
    }
}