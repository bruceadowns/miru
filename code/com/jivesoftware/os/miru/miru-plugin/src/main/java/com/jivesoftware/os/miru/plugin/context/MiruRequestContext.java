package com.jivesoftware.os.miru.plugin.context;

import com.jivesoftware.os.filer.io.StripingLocksProvider;
import com.jivesoftware.os.miru.api.activity.schema.MiruSchema;
import com.jivesoftware.os.miru.api.base.MiruStreamId;
import com.jivesoftware.os.miru.plugin.index.MiruActivityIndex;
import com.jivesoftware.os.miru.plugin.index.MiruAuthzIndex;
import com.jivesoftware.os.miru.plugin.index.MiruFieldIndexProvider;
import com.jivesoftware.os.miru.plugin.index.MiruInboxIndex;
import com.jivesoftware.os.miru.plugin.index.MiruRemovalIndex;
import com.jivesoftware.os.miru.plugin.index.MiruSipIndex;
import com.jivesoftware.os.miru.plugin.index.MiruTermComposer;
import com.jivesoftware.os.miru.plugin.index.MiruTimeIndex;
import com.jivesoftware.os.miru.plugin.index.MiruUnreadTrackingIndex;

/**
 * @author jonathan
 */
public interface MiruRequestContext<BM> {

    MiruSchema getSchema();

    MiruTermComposer getTermComposer();

    MiruTimeIndex getTimeIndex();

    MiruActivityIndex getActivityIndex();

    MiruFieldIndexProvider<BM> getFieldIndexProvider();

    MiruSipIndex getSipIndex();

    MiruAuthzIndex<BM> getAuthzIndex();

    MiruRemovalIndex<BM> getRemovalIndex();

    MiruUnreadTrackingIndex<BM> getUnreadTrackingIndex();

    MiruInboxIndex<BM> getInboxIndex();

    StripingLocksProvider<MiruStreamId> getStreamLocks();
}
