package dpf.sp.gpinf.indexer.desktop;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;

import org.apache.commons.lang.ArrayUtils;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.SortedDocValues;

import dpf.sp.gpinf.indexer.process.IndexItem;
import dpf.sp.gpinf.indexer.search.IPEDMultiSource;
import dpf.sp.gpinf.indexer.search.MultiSearchResult;
import dpf.sp.gpinf.indexer.util.DocValuesUtil;
import iped3.IItemId;
import iped3.search.IMultiSearchResult;

public class DynamicDuplicateFilter {

    private static IPEDMultiSource ipedCase;

    private BitSet ordSet = new BitSet(1 << 23);

    public DynamicDuplicateFilter(IPEDMultiSource ipedSource) {
        if (ipedCase != ipedSource) {
            ipedCase = ipedSource;
        }
    }

    public MultiSearchResult filter(IMultiSearchResult result) throws IOException {

        LeafReader reader = ipedCase.getLeafReader();
        SortedDocValues docValues = reader.getSortedDocValues(IndexItem.HASH);

        ArrayList<IItemId> filteredItems = new ArrayList<IItemId>();
        ArrayList<Float> scores = new ArrayList<Float>();
        int i = 0;
        boolean filterOrdZero = false;
        try {
            if (!docValues.lookupOrd(0).utf8ToString().isEmpty()) {
                filterOrdZero = true;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        for (IItemId item : result.getIterator()) {
            int docId = ipedCase.getLuceneId(item);
            int ord = DocValuesUtil.getOrd(docValues, docId);
            if (ord < 0 || !ordSet.get(ord)) {
                filteredItems.add(item);
                scores.add(result.getScore(i));
                if (ord > 0 || (ord == 0 && filterOrdZero))
                    ordSet.set(ord);
            }
            i++;
        }
        return new MultiSearchResult(filteredItems.toArray(new IItemId[0]),
                ArrayUtils.toPrimitive(scores.toArray(new Float[0])));
    }

}
