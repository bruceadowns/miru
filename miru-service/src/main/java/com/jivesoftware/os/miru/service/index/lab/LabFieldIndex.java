package com.jivesoftware.os.miru.service.index.lab;

import com.google.common.primitives.Bytes;
import com.jivesoftware.os.filer.io.ByteArrayFiler;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.StripingLocksProvider;
import com.jivesoftware.os.filer.io.api.KeyRange;
import com.jivesoftware.os.filer.io.api.KeyedFilerStore;
import com.jivesoftware.os.filer.io.api.StackBuffer;
import com.jivesoftware.os.filer.io.map.MapContext;
import com.jivesoftware.os.filer.io.map.MapStore;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProvider;
import com.jivesoftware.os.lab.api.ValueIndex;
import com.jivesoftware.os.lab.api.ValueStream;
import com.jivesoftware.os.lab.io.api.UIO;
import com.jivesoftware.os.miru.api.base.MiruTermId;
import com.jivesoftware.os.miru.plugin.MiruInterner;
import com.jivesoftware.os.miru.plugin.bitmap.MiruBitmaps;
import com.jivesoftware.os.miru.plugin.index.BitmapAndLastId;
import com.jivesoftware.os.miru.plugin.index.IndexAlignedBitmapMerger;
import com.jivesoftware.os.miru.plugin.index.MiruFieldIndex;
import com.jivesoftware.os.miru.plugin.index.MiruInvertedIndex;
import com.jivesoftware.os.miru.plugin.index.MultiIndexTx;
import com.jivesoftware.os.miru.plugin.index.TermIdStream;
import com.jivesoftware.os.miru.plugin.partition.TrackError;
import com.jivesoftware.os.miru.service.index.lab.LabInvertedIndex.SizeAndBytes;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;
import org.apache.commons.lang.mutable.MutableLong;

/**
 *
 * @author jonathan.colt
 */
public class LabFieldIndex<BM extends IBM, IBM> implements MiruFieldIndex<BM, IBM> {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final OrderIdProvider idProvider;
    private final MiruBitmaps<BM, IBM> bitmaps;
    private final TrackError trackError;
    private final ValueIndex[] indexes;
    private final KeyedFilerStore<Integer, MapContext>[] cardinalities;
    // We could lock on both field + termId for improved hash/striping, but we favor just termId to reduce object creation
    private final StripingLocksProvider<MiruTermId> stripingLocksProvider;
    private final MiruInterner<MiruTermId> termInterner;

    public LabFieldIndex(OrderIdProvider idProvider,
        MiruBitmaps<BM, IBM> bitmaps,
        TrackError trackError,
        ValueIndex[] indexes,
        KeyedFilerStore<Integer, MapContext>[] cardinalities,
        StripingLocksProvider<MiruTermId> stripingLocksProvider,
        MiruInterner<MiruTermId> termInterner) throws Exception {

        this.idProvider = idProvider;
        this.bitmaps = bitmaps;
        this.trackError = trackError;
        this.indexes = indexes;
        this.cardinalities = cardinalities;
        this.stripingLocksProvider = stripingLocksProvider;
        this.termInterner = termInterner;
    }

    @Override
    public void append(int fieldId, MiruTermId termId, int[] ids, long[] counts, StackBuffer stackBuffer) throws Exception {
        getIndex("append", fieldId, termId).append(stackBuffer, ids);
        mergeCardinalities(fieldId, termId, ids, counts, stackBuffer);
    }

    @Override
    public void set(int fieldId, MiruTermId termId, int[] ids, long[] counts, StackBuffer stackBuffer) throws Exception {
        getIndex("set", fieldId, termId).set(stackBuffer, ids);
        mergeCardinalities(fieldId, termId, ids, counts, stackBuffer);
    }

    @Override
    public void setIfEmpty(int fieldId, MiruTermId termId, int id, long count, StackBuffer stackBuffer) throws Exception {
        if (getIndex("setIfEmpty", fieldId, termId).setIfEmpty(stackBuffer, id)) {
            mergeCardinalities(fieldId, termId, new int[]{id}, new long[]{count}, stackBuffer);
        }
    }

    @Override
    public void remove(int fieldId, MiruTermId termId, int[] ids, StackBuffer stackBuffer) throws Exception {
        getIndex("remove", fieldId, termId).remove(stackBuffer, ids);
        mergeCardinalities(fieldId, termId, ids, cardinalities[fieldId] != null ? new long[ids.length] : null, stackBuffer);
    }

    @Override
    public void streamTermIdsForField(String name,
        int fieldId,
        List<KeyRange> ranges,
        final TermIdStream termIdStream,
        StackBuffer stackBuffer) throws Exception {
        MutableLong bytes = new MutableLong();
        getFieldIndex(fieldId).rowScan((byte[] key, long timestamp, boolean tombstoned, long version, byte[] payload) -> {
            try {
                bytes.add(key.length);
                return termIdStream.stream(termInterner.intern(key, 4, key.length - 4));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        LOG.inc("count>streamTermIdsForField>total");
        LOG.inc("count>streamTermIdsForField>" + name + ">total");
        LOG.inc("count>streamTermIdsForField>" + name + ">" + fieldId);
        LOG.inc("bytes>streamTermIdsForField>total", bytes.longValue());
        LOG.inc("bytes>streamTermIdsForField>" + name + ">total", bytes.longValue());
        LOG.inc("bytes>streamTermIdsForField>" + name + ">" + fieldId, bytes.longValue());
    }

    private ValueIndex getFieldIndex(int fieldId) {
        return indexes[fieldId % indexes.length];
    }

    @Override
    public MiruInvertedIndex<BM, IBM> get(String name, int fieldId, MiruTermId termId) throws Exception {
        return getIndex(name, fieldId, termId);
    }

    @Override
    public MiruInvertedIndex<BM, IBM> getOrCreateInvertedIndex(String name, int fieldId, MiruTermId term) throws Exception {
        return getIndex(name, fieldId, term);
    }

    private MiruInvertedIndex<BM, IBM> getIndex(String name, int fieldId, MiruTermId termId) throws Exception {
        byte[] fieldIdBytes = UIO.intBytes(fieldId);

        return new LabInvertedIndex<>(idProvider, bitmaps, trackError, name, fieldId, Bytes.concat(fieldIdBytes, termId.getBytes()), getFieldIndex(fieldId),
            stripingLocksProvider.lock(termId, 0));
    }

    @Override
    public void multiGet(String name, int fieldId, MiruTermId[] termIds, BitmapAndLastId<BM>[] results, StackBuffer stackBuffer) throws Exception {
        byte[] fieldIdBytes = UIO.intBytes(fieldId);
        MutableLong bytes = new MutableLong();
        for (int i = 0; i < termIds.length; i++) {
            if (termIds[i] != null) {
                int ri = i;
                getFieldIndex(fieldId).get(Bytes.concat(fieldIdBytes, termIds[i].getBytes()),
                    (byte[] key, long timestamp, boolean tombstoned, long version, byte[] payload) -> {
                        if (payload != null) {
                            BitmapAndLastId<BM> bitmapAndLastId = LabInvertedIndex.deser(bitmaps, trackError, payload, -1, stackBuffer);
                            if (bitmapAndLastId != null) {
                                results[ri] = bitmapAndLastId;
                            }
                        }
                        return true;
                    });
            }
        }

        LOG.inc("count>multiGet>total");
        LOG.inc("count>multiGet>" + name + ">total");
        LOG.inc("count>multiGet>" + name + ">" + fieldId);
        LOG.inc("bytes>multiGet>total", bytes.longValue());
        LOG.inc("bytes>multiGet>" + name + ">total", bytes.longValue());
        LOG.inc("bytes>multiGet>" + name + ">" + fieldId, bytes.longValue());
    }

    @Override
    public void multiGetLastIds(String name, int fieldId, MiruTermId[] termIds, int[] results, StackBuffer stackBuffer) throws Exception {
        byte[] fieldIdBytes = UIO.intBytes(fieldId);
        MutableLong bytes = new MutableLong();
        for (int i = 0; i < termIds.length; i++) {
            if (termIds[i] != null) {
                int ri = i;
                getFieldIndex(fieldId).get(Bytes.concat(fieldIdBytes, termIds[i].getBytes()),
                    (byte[] key, long timestamp, boolean tombstoned, long version, byte[] payload) -> {
                        if (payload != null) {
                            bytes.add(payload.length);
                            results[ri] = UIO.bytesInt(payload);
                        }
                        return true;
                    });
            }
        }

        LOG.inc("count>multiGetLastIds>total");
        LOG.inc("count>multiGetLastIds>" + name + ">total");
        LOG.inc("count>multiGetLastIds>" + name + ">" + fieldId);
        LOG.inc("bytes>multiGetLastIds>total", bytes.longValue());
        LOG.inc("bytes>multiGetLastIds>" + name + ">total", bytes.longValue());
        LOG.inc("bytes>multiGetLastIds>" + name + ">" + fieldId, bytes.longValue());
    }

    @Override
    public void multiTxIndex(String name,
        int fieldId,
        MiruTermId[] termIds,
        int considerIfLastIdGreaterThanN,
        StackBuffer stackBuffer,
        MultiIndexTx<IBM> indexTx) throws Exception {
        byte[] fieldIdBytes = UIO.intBytes(fieldId);
        MutableLong bytes = new MutableLong();
        for (int i = 0; i < termIds.length; i++) {
            if (termIds[i] != null) {
                int ri = i;
                getFieldIndex(fieldId).get(Bytes.concat(fieldIdBytes, termIds[i].getBytes()),
                    (byte[] key, long timestamp, boolean tombstoned, long version, byte[] payload) -> {
                        if (payload != null) {
                            try {
                                bytes.add(payload.length);
                                int lastId = -1;
                                if (considerIfLastIdGreaterThanN >= 0) {
                                    lastId = UIO.bytesInt(payload);
                                }
                                if (lastId < 0 || lastId > considerIfLastIdGreaterThanN) {
                                    indexTx.tx(ri, null, new ByteArrayFiler(payload), LabInvertedIndex.LAST_ID_LENGTH, stackBuffer);
                                }
                            } catch (Exception e) {
                                throw new IOException(e);
                            }
                        }
                        return true;
                    });
            }
        }

        LOG.inc("count>multiTxIndex>total");
        LOG.inc("count>multiTxIndex>" + name + ">total");
        LOG.inc("count>multiTxIndex>" + name + ">" + fieldId);
        LOG.inc("bytes>multiTxIndex>total", bytes.longValue());
        LOG.inc("bytes>multiTxIndex>" + name + ">total", bytes.longValue());
        LOG.inc("bytes>multiTxIndex>" + name + ">" + fieldId, bytes.longValue());
    }

    @Override
    public long getCardinality(int fieldId, MiruTermId termId, int id, StackBuffer stackBuffer) throws Exception {
        if (cardinalities[fieldId] != null) {
            Long count = cardinalities[fieldId].read(termId.getBytes(), null, (monkey, filer, _stackBuffer, lock) -> {
                if (filer != null) {
                    synchronized (lock) {
                        byte[] payload = MapStore.INSTANCE.getPayload(filer, monkey, FilerIO.intBytes(id), _stackBuffer);
                        if (payload != null) {
                            return FilerIO.bytesLong(payload);
                        }
                    }
                }
                return null;
            }, stackBuffer);
            return count != null ? count : 0;
        }
        return -1;
    }

    @Override
    public long[] getCardinalities(int fieldId, MiruTermId termId, int[] ids, StackBuffer stackBuffer) throws Exception {
        long[] counts = new long[ids.length];
        if (cardinalities[fieldId] != null) {
            cardinalities[fieldId].read(termId.getBytes(), null, (monkey, filer, _stackBuffer, lock) -> {
                if (filer != null) {
                    synchronized (lock) {
                        for (int i = 0; i < ids.length; i++) {
                            if (ids[i] >= 0) {
                                byte[] payload = MapStore.INSTANCE.getPayload(filer, monkey, FilerIO.intBytes(ids[i]), _stackBuffer);
                                if (payload != null) {
                                    counts[i] = FilerIO.bytesLong(payload);
                                }
                            }
                        }
                    }
                }
                return null;
            }, stackBuffer);
        } else {
            Arrays.fill(counts, -1);
        }
        return counts;
    }

    @Override
    public long getGlobalCardinality(int fieldId, MiruTermId termId, StackBuffer stackBuffer) throws Exception {
        return getCardinality(fieldId, termId, -1, stackBuffer);
    }

    @Override
    public void multiMerge(int fieldId, MiruTermId[] termIds, IndexAlignedBitmapMerger<BM> merger, StackBuffer stackBuffer) throws Exception {
        byte[] fieldIdBytes = UIO.intBytes(fieldId);

        MutableLong bytesRead = new MutableLong();
        MutableLong bytesWrite = new MutableLong();
        int[] indexPowers = new int[32];

        SizeAndBytes[] sabs = new SizeAndBytes[termIds.length];
        for (int i = 0; i < termIds.length; i++) {
            if (termIds[i] != null) {
                int ri = i;
                getFieldIndex(fieldId).get(Bytes.concat(fieldIdBytes, termIds[i].getBytes()),
                    (byte[] key, long timestamp, boolean tombstoned, long version, byte[] payload) -> {
                        BitmapAndLastId<BM> backing = null;
                        if (payload != null) {
                            bytesRead.add(payload.length);
                            backing = LabInvertedIndex.deser(bitmaps, trackError, payload, -1, stackBuffer);
                        }
                        BitmapAndLastId<BM> merged = merger.merge(ri, backing);
                        if (merged != null) {
                            try {
                                SizeAndBytes sizeAndBytes = LabInvertedIndex.getSizeAndBytes(bitmaps, merged.bitmap, merged.lastId);
                                indexPowers[FilerIO.chunkPower(sizeAndBytes.filerSizeInBytes, 0)]++;
                                sabs[ri] = sizeAndBytes;
                            } catch (Exception e) {
                                throw new IOException("Failed to serialize bitmap", e);
                            }
                        }
                        return true;
                    });
            }
        }

        long timestamp = System.currentTimeMillis();
        long version = idProvider.nextId();
        getFieldIndex(fieldId).append((ValueStream stream) -> {
            for (int i = 0; i < termIds.length; i++) {
                if (sabs[i] != null) {
                    bytesWrite.add(sabs[i].bytes.length);
                    stream.stream(Bytes.concat(fieldIdBytes, termIds[i].getBytes()), timestamp, false, version, sabs[i].bytes);
                }
            }
            return true;
        });

        List<Future<Object>> commit = getFieldIndex(fieldId).commit(false);
        for (Future<Object> future : commit) {
            future.get();
        }

        LOG.inc("count>multiMerge>total");
        LOG.inc("count>multiMerge>" + fieldId);
        LOG.inc("bytes>multiMergeRead>total", bytesRead.longValue());
        LOG.inc("bytes>multiMergeRead>" + fieldId, bytesRead.longValue());
        LOG.inc("bytes>multiMergeWrite>total", bytesWrite.longValue());
        LOG.inc("bytes>multiMergeWrite>" + fieldId, bytesWrite.longValue());
        for (int i = 0; i < indexPowers.length; i++) {
            if (indexPowers[i] > 0) {
                LOG.inc("count>multiMerge>power>" + i, indexPowers[i]);
            }
        }
    }

    @Override
    public void mergeCardinalities(int fieldId, MiruTermId termId, int[] ids, long[] counts, StackBuffer stackBuffer) throws Exception {
        if (cardinalities[fieldId] != null && counts != null) {
            cardinalities[fieldId].readWriteAutoGrow(termId.getBytes(), ids.length, (monkey, filer, _stackBuffer, lock) -> {
                synchronized (lock) {
                    long delta = 0;
                    for (int i = 0; i < ids.length; i++) {
                        byte[] key = FilerIO.intBytes(ids[i]);
                        long keyHash = MapStore.INSTANCE.hash(key, 0, key.length);
                        byte[] payload = MapStore.INSTANCE.getPayload(filer, monkey, keyHash, key, stackBuffer);
                        long existing = payload != null ? FilerIO.bytesLong(payload) : 0;
                        MapStore.INSTANCE.add(filer, monkey, (byte) 1, keyHash, key, FilerIO.longBytes(counts[i]), _stackBuffer);
                        delta += counts[i] - existing;
                    }

                    byte[] globalKey = FilerIO.intBytes(-1);
                    byte[] globalPayload = MapStore.INSTANCE.getPayload(filer, monkey, globalKey, _stackBuffer);
                    long globalExisting = globalPayload != null ? FilerIO.bytesLong(globalPayload) : 0;
                    MapStore.INSTANCE.add(filer, monkey, (byte) 1, globalKey, FilerIO.longBytes(globalExisting + delta), _stackBuffer);
                }
                return null;
            }, stackBuffer);
        }
    }

}