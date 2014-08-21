package com.jivesoftware.os.miru.service.index;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.googlecode.javaewah.EWAHCompressedBitmap;
import com.jivesoftware.os.jive.utils.chunk.store.ChunkStore;
import com.jivesoftware.os.jive.utils.chunk.store.ChunkStoreInitializer;
import com.jivesoftware.os.jive.utils.id.Id;
import com.jivesoftware.os.miru.api.base.MiruStreamId;
import com.jivesoftware.os.miru.service.bitmap.MiruBitmapsEWAH;
import com.jivesoftware.os.miru.service.index.disk.MiruOnDiskInboxIndex;
import com.jivesoftware.os.miru.service.index.memory.MiruInMemoryInboxIndex;
import com.jivesoftware.os.miru.service.index.memory.MiruInMemoryInboxIndex.InboxAndLastActivityIndex;
import com.jivesoftware.os.miru.service.index.memory.MiruInMemoryInvertedIndex;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class MiruInboxIndexTest {

    @Test(dataProvider = "miruInboxIndexDataProvider")
    public void testGetEmptyInboxWithoutCreating(MiruInboxIndex miruInboxIndex, MiruStreamId miruStreamId)
        throws Exception {

        Optional<EWAHCompressedBitmap> inbox = miruInboxIndex.getInbox(miruStreamId);
        assertNotNull(inbox);
        assertFalse(inbox.isPresent());
    }

    @Test(dataProvider = "miruInboxIndexDataProvider")
    public void testGetEmptyInboxAndCreate(MiruInboxIndex miruInboxIndex, MiruStreamId miruStreamId)
        throws Exception {

        MiruInvertedIndexAppender inbox = miruInboxIndex.getAppender(miruStreamId);
        assertNotNull(inbox);
        Optional<EWAHCompressedBitmap> inboxIndex = miruInboxIndex.getInbox(miruStreamId);
        assertNotNull(inboxIndex);
        assertTrue(inboxIndex.isPresent());
        assertEquals(inboxIndex.get().sizeInBytes(), 8);
    }

    @Test(dataProvider = "miruInboxIndexDataProvider")
    public void testIndexIds(MiruInboxIndex miruInboxIndex, MiruStreamId miruStreamId) throws Exception {
        miruInboxIndex.index(miruStreamId, 1);
    }

    @Test(dataProvider = "miruInboxIndexDataProvider")
    public void testSetLastActivityIndex(MiruInboxIndex miruInboxIndex, MiruStreamId miruStreamId) throws Exception {
        miruInboxIndex.setLastActivityIndex(miruStreamId, 1);
    }

    @Test(dataProvider = "miruInboxIndexDataProviderWithData")
    public void testIndexedData(MiruInboxIndex miruInboxIndex, MiruStreamId streamId, List<Integer> indexedIds) throws Exception {
        Optional<EWAHCompressedBitmap> inbox = miruInboxIndex.getInbox(streamId);
        assertNotNull(inbox);
        assertTrue(inbox.isPresent());
        assertTrue(inbox.get().get(indexedIds.get(0)));
        assertTrue(inbox.get().get(indexedIds.get(1)));
        assertTrue(inbox.get().get(indexedIds.get(2)));
    }

    @Test(dataProvider = "miruInboxIndexDataProviderWithData")
    public void testLastActivityIndexNotSetAutomatically(MiruInboxIndex miruInboxIndex, MiruStreamId streamId, List<Integer> indexedIds) throws Exception {

        int lastActivityIndex = miruInboxIndex.getLastActivityIndex(streamId);
        assertEquals(lastActivityIndex, -1);

        int nextId = Integer.MAX_VALUE / 2;
        miruInboxIndex.index(streamId, nextId);
        lastActivityIndex = miruInboxIndex.getLastActivityIndex(streamId);
        assertEquals(lastActivityIndex, -1);
    }

    @Test(dataProvider = "miruInboxIndexDataProviderWithData")
    public void testDefaultLastActivityIndexWithNewStreamId(MiruInboxIndex miruInboxIndex, MiruStreamId streamId, List<Integer> indexedIds) throws Exception {

        MiruStreamId newStreamId = new MiruStreamId(new Id(1337).toBytes());
        int lastActivityIndex = miruInboxIndex.getLastActivityIndex(newStreamId);
        assertEquals(lastActivityIndex, -1);

        int nextId = Integer.MAX_VALUE / 2;
        miruInboxIndex.index(newStreamId, nextId);
        lastActivityIndex = miruInboxIndex.getLastActivityIndex(newStreamId);
        assertEquals(lastActivityIndex, -1);
    }

    @Test(dataProvider = "miruInboxIndexDataProviderWithData")
    public void testLastActivityIndex(MiruInboxIndex miruInboxIndex, MiruStreamId streamId, List<Integer> indexedIds) throws Exception {

        int nextId = Integer.MAX_VALUE / 3;
        miruInboxIndex.setLastActivityIndex(streamId, nextId);
        int lastActivityIndex = miruInboxIndex.getLastActivityIndex(streamId);
        assertEquals(lastActivityIndex, nextId);
    }

    @DataProvider(name = "miruInboxIndexDataProvider")
    public Object[][] miruInboxIndexDataProvider() throws Exception {
        MiruStreamId miruStreamId = new MiruStreamId(new Id(12345).toBytes());

        MiruBitmapsEWAH bitmaps = new MiruBitmapsEWAH(10);
        MiruInMemoryInboxIndex<EWAHCompressedBitmap> miruInMemoryInboxIndex = new MiruInMemoryInboxIndex<>(bitmaps);

        File mapDir = Files.createTempDirectory("map").toFile();
        File swapDir = Files.createTempDirectory("swap").toFile();
        Path chunksDir = Files.createTempDirectory("chunks");
        File chunks = new File(chunksDir.toFile(), "chunks.data");
        ChunkStore chunkStore = new ChunkStoreInitializer().initialize(chunks.getAbsolutePath(), 4096, false);
        MiruOnDiskInboxIndex<EWAHCompressedBitmap> miruOnDiskInboxIndex = new MiruOnDiskInboxIndex<>(bitmaps, mapDir, swapDir, chunkStore);
        miruOnDiskInboxIndex.bulkImport(miruInMemoryInboxIndex);

        return new Object[][] {
            { miruInMemoryInboxIndex, miruStreamId },
            { miruOnDiskInboxIndex, miruStreamId }
        };
    }

    @DataProvider(name = "miruInboxIndexDataProviderWithData")
    public Object[][] miruInboxIndexDataProviderWithData() throws Exception {
        MiruStreamId streamId = new MiruStreamId(new Id(12345).toBytes());

        // Create in-memory inbox index
        MiruBitmapsEWAH bitmaps = new MiruBitmapsEWAH(10);

        MiruInMemoryInboxIndex miruInMemoryInboxIndex = new MiruInMemoryInboxIndex(bitmaps);

        ConcurrentMap<MiruStreamId, MiruInvertedIndex> index = new ConcurrentHashMap<>();
        Map<MiruStreamId, Integer> lastActivityIndex = Maps.newHashMap();

        // Add activities to index
        EWAHCompressedBitmap invertedIndex = new EWAHCompressedBitmap();
        invertedIndex.set(1);
        invertedIndex.set(2);
        invertedIndex.set(3);
        MiruInMemoryInvertedIndex ii = new MiruInMemoryInvertedIndex(bitmaps);
        ii.or(invertedIndex);
        index.put(streamId, ii);

        final InboxAndLastActivityIndex inboxAndLastActivityIndex = new InboxAndLastActivityIndex(index, lastActivityIndex);
        miruInMemoryInboxIndex.bulkImport(new BulkExport<InboxAndLastActivityIndex>() {
            @Override
            public InboxAndLastActivityIndex bulkExport() throws Exception {
                return inboxAndLastActivityIndex;
            }
        });

        // Copy to on disk index
        File mapDir = Files.createTempDirectory("map").toFile();
        File swapDir = Files.createTempDirectory("swap").toFile();
        Path chunksDir = Files.createTempDirectory("chunks");
        File chunks = new File(chunksDir.toFile(), "chunks.data");
        ChunkStore chunkStore = new ChunkStoreInitializer().initialize(chunks.getAbsolutePath(), 4096, false);
        MiruOnDiskInboxIndex miruOnDiskInboxIndex = new MiruOnDiskInboxIndex(bitmaps, mapDir, swapDir, chunkStore);
        miruOnDiskInboxIndex.bulkImport(miruInMemoryInboxIndex);

        return new Object[][] {
            { miruInMemoryInboxIndex, streamId, ImmutableList.of(1, 2, 3) },
            { miruOnDiskInboxIndex, streamId, ImmutableList.of(1, 2, 3) }
        };
    }
}
