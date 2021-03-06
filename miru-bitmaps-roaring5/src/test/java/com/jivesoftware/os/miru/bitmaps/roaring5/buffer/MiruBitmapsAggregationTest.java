package com.jivesoftware.os.miru.bitmaps.roaring5.buffer;

import com.google.common.collect.Lists;
import com.jivesoftware.os.miru.bitmaps.roaring5.MiruBitmapsRoaring;
import gnu.trove.list.array.TIntArrayList;
import java.util.List;
import org.roaringbitmap.RoaringBitmap;
import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class MiruBitmapsAggregationTest {

    @Test
    public void testOr() throws Exception {
        MiruBitmapsRoaring bitmaps = new MiruBitmapsRoaring();
        List<RoaringBitmap> ors = Lists.newArrayList();
        int numBits = 10;
        for (int i = 0; i < numBits; i++) {
            RoaringBitmap or = bitmaps.createWithBits(i * 137);
            ors.add(or);
        }
        RoaringBitmap container = bitmaps.or(ors);
        for (int i = 0; i < numBits; i++) {
            assertFalse(bitmaps.isSet(container, i * 137 - 1));
            assertTrue(bitmaps.isSet(container, i * 137));
            assertFalse(bitmaps.isSet(container, i * 137 + 1));
        }
    }

    @Test
    public void testAnd() throws Exception {
        MiruBitmapsRoaring bitmaps = new MiruBitmapsRoaring();
        List<RoaringBitmap> ands = Lists.newArrayList();
        int numBits = 10;
        int andBits = 3;
        for (int i = 0; i < numBits - andBits; i++) {
            TIntArrayList bits = new TIntArrayList();
            for (int j = i + 1; j < numBits; j++) {
                bits.add(j * 137);
            }
            ands.add(bitmaps.createWithBits(bits.toArray()));
        }
        RoaringBitmap container = bitmaps.and(ands);
        for (int i = 0; i < numBits; i++) {
            if (i < (numBits - andBits)) {
                assertFalse(bitmaps.isSet(container, i * 137));
            } else {
                assertTrue(bitmaps.isSet(container, i * 137));
            }
        }
    }

    @Test
    public void testAndNot_2() throws Exception {
        MiruBitmapsRoaring bitmaps = new MiruBitmapsRoaring();
        int numOriginal = 10;
        int numNot = 3;
        TIntArrayList originalBits = new TIntArrayList();
        TIntArrayList notBits = new TIntArrayList();
        for (int i = 0; i < numOriginal; i++) {
            originalBits.add(i * 137);
            if (i < numNot) {
                notBits.add(i * 137);
            }
        }
        RoaringBitmap original = bitmaps.createWithBits(originalBits.toArray());
        RoaringBitmap not = bitmaps.createWithBits(notBits.toArray());
        RoaringBitmap container = bitmaps.andNot(original, not);
        for (int i = 0; i < numOriginal; i++) {
            if (i < numNot) {
                assertFalse(bitmaps.isSet(container, i * 137));
            } else {
                assertTrue(bitmaps.isSet(container, i * 137));
            }
        }
    }

    @Test
    public void testAndNot_multi() throws Exception {
        MiruBitmapsRoaring bitmaps = new MiruBitmapsRoaring();
        List<RoaringBitmap> nots = Lists.newArrayList();
        int numOriginal = 10;
        int numNot = 3;
        TIntArrayList originalBits = new TIntArrayList();
        for (int i = 0; i < numOriginal; i++) {
            originalBits.add(i * 137);
            if (i < numNot) {
                RoaringBitmap not = bitmaps.createWithBits(i * 137);
                nots.add(not);
            }
        }
        RoaringBitmap original = bitmaps.createWithBits(originalBits.toArray());
        RoaringBitmap container = bitmaps.andNot(original, nots);
        for (int i = 0; i < numOriginal; i++) {
            if (i < numNot) {
                assertFalse(bitmaps.isSet(container, i * 137));
            } else {
                assertTrue(bitmaps.isSet(container, i * 137));
            }
        }
    }

}
