package com.jivesoftware.os.miru.service.index;

import com.google.common.base.Optional;
import com.jivesoftware.os.filer.io.api.StackBuffer;
import com.jivesoftware.os.miru.api.MiruHost;
import com.jivesoftware.os.miru.api.MiruPartitionCoord;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.api.wal.RCVSSipCursor;
import com.jivesoftware.os.miru.bitmaps.roaring5.buffer.MiruBitmapsRoaringBuffer;
import com.jivesoftware.os.miru.plugin.index.MiruSipIndex;
import com.jivesoftware.os.miru.service.index.delta.MiruDeltaSipIndex;
import org.apache.commons.lang3.ArrayUtils;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static com.jivesoftware.os.miru.service.IndexTestUtil.buildInMemoryContext;
import static com.jivesoftware.os.miru.service.IndexTestUtil.buildOnDiskContext;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class MiruSipIndexTest {

    @Test(dataProvider = "miruSipIndexDataProviderWithData")
    public void storeAndGetSip(MiruSipIndex<RCVSSipCursor> sipIndex, RCVSSipCursor expected) throws Exception {

        Optional<RCVSSipCursor> actual = sipIndex.getSip(new StackBuffer());
        assertTrue(actual.isPresent());
        assertEquals(actual.get(), expected);
    }

    @DataProvider(name = "miruSipIndexDataProviderWithData")
    public Object[][] miruSipIndexDataProviderWithData() throws Exception {
        MiruBitmapsRoaringBuffer bitmaps = new MiruBitmapsRoaringBuffer();
        MiruTenantId tenantId = new MiruTenantId(new byte[] { 1 });
        MiruPartitionCoord coord = new MiruPartitionCoord(tenantId, MiruPartitionId.of(1), new MiruHost("logicalName"));

        return ArrayUtils.addAll(buildSipDataProvider(bitmaps, coord, false),
            buildSipDataProvider(bitmaps, coord, true));
    }

    private Object[][] buildSipDataProvider(MiruBitmapsRoaringBuffer bitmaps,
        MiruPartitionCoord coord,
        boolean useLabIndexes) throws Exception {

        RCVSSipCursor initial = new RCVSSipCursor((byte) 0, 1L, 2L, false);
        RCVSSipCursor expected = new RCVSSipCursor((byte) 1, 3L, 4L, true);

        MiruSipIndex<RCVSSipCursor> unmergedInMemorySipIndex = buildInMemoryContext(4, useLabIndexes, bitmaps, coord).sipIndex;
        populateSipIndex(unmergedInMemorySipIndex, initial, expected, false, false);

        MiruSipIndex<RCVSSipCursor> unmergedOnDiskSipIndex = buildOnDiskContext(4, useLabIndexes, bitmaps, coord).sipIndex;
        populateSipIndex(unmergedOnDiskSipIndex, initial, expected, false, false);

        MiruSipIndex<RCVSSipCursor> mergedInMemorySipIndex = buildInMemoryContext(4, useLabIndexes, bitmaps, coord).sipIndex;
        populateSipIndex(mergedInMemorySipIndex, initial, expected, false, true);

        MiruSipIndex<RCVSSipCursor> mergedOnDiskSipIndex = buildOnDiskContext(4, useLabIndexes, bitmaps, coord).sipIndex;
        populateSipIndex(mergedOnDiskSipIndex, initial, expected, false, true);

        MiruSipIndex<RCVSSipCursor> partiallyMergedInMemorySipIndex = buildInMemoryContext(4, useLabIndexes, bitmaps, coord).sipIndex;
        populateSipIndex(partiallyMergedInMemorySipIndex, initial, expected, true, false);

        MiruSipIndex<RCVSSipCursor> partiallyMergedOnDiskSipIndex = buildOnDiskContext(4, useLabIndexes, bitmaps, coord).sipIndex;
        populateSipIndex(partiallyMergedOnDiskSipIndex, initial, expected, true, false);

        return new Object[][] {
            { unmergedInMemorySipIndex, expected },
            { unmergedOnDiskSipIndex, expected },
            { mergedInMemorySipIndex, expected },
            { mergedOnDiskSipIndex, expected },
            { partiallyMergedInMemorySipIndex, expected },
            { partiallyMergedOnDiskSipIndex, expected }
        };
    }

    private void populateSipIndex(MiruSipIndex<RCVSSipCursor> sipIndex,
        RCVSSipCursor initial,
        RCVSSipCursor expected,
        boolean mergeMiddle,
        boolean mergeEnd) throws Exception {

        StackBuffer stackBuffer = new StackBuffer();

        sipIndex.setSip(initial, stackBuffer);

        if (mergeMiddle) {
            ((MiruDeltaSipIndex<RCVSSipCursor>) sipIndex).merge(null, stackBuffer);
        }

        sipIndex.setSip(expected, stackBuffer);

        if (mergeEnd) {
            ((MiruDeltaSipIndex<RCVSSipCursor>) sipIndex).merge(null, stackBuffer);
        }
    }
}