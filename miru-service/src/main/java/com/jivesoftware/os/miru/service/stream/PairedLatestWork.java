package com.jivesoftware.os.miru.service.stream;

import com.jivesoftware.os.miru.api.base.MiruTermId;
import java.util.List;

/**
 *
 */
class PairedLatestWork implements Comparable<PairedLatestWork> {
    final int fieldId;
    final int aggregateFieldId;
    final MiruTermId fieldValue;
    final List<IdAndTerm> work;

    PairedLatestWork(int fieldId, int aggregateFieldId, MiruTermId fieldValue, List<IdAndTerm> work) {
        this.fieldId = fieldId;
        this.aggregateFieldId = aggregateFieldId;
        this.fieldValue = fieldValue;
        this.work = work;
    }

    @Override
    public int compareTo(PairedLatestWork o) {
        // flipped so that natural ordering is "largest first"
        return Integer.compare(o.work.size(), work.size());
    }
}
