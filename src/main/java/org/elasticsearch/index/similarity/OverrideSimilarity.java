package org.elasticsearch.index.similarity;

import org.apache.lucene.index.FieldInvertState;
import org.apache.lucene.search.similarities.DefaultSimilarity;
import org.apache.lucene.util.BytesRef;

public class OverrideSimilarity extends DefaultSimilarity {
	private static final long serialVersionUID = 674197082435004099L;
	protected boolean discountOverlaps;

	@Override
	public float coord(int overlap, int maxOverlap) {
		// TODO Auto-generated method stub
		return super.coord(overlap, maxOverlap);
	}

	@Override
	public boolean getDiscountOverlaps() {
		// TODO Auto-generated method stub
		return super.getDiscountOverlaps();
	}

	@Override
	public float idf(long docFreq, long numDocs) {
		return 1.0f;
	}

	@Override
	public float lengthNorm(FieldInvertState state) {
		final int numTerms;
		if (discountOverlaps) {
			numTerms = state.getLength() - state.getNumOverlap();
		} else {
			numTerms = state.getLength();
		}
		if (state.getName().indexOf("auctionName") != -1) {
			return (float) (0.1 * Math.log(numTerms));
		}
		return state.getBoost();
	}

	@Override
	public float queryNorm(float sumOfSquaredWeights) {
		// TODO Auto-generated method stub
		return super.queryNorm(sumOfSquaredWeights);
	}

	@Override
	public float scorePayload(int doc, int start, int end, BytesRef payload) {
		// TODO Auto-generated method stub
		return super.scorePayload(doc, start, end, payload);
	}

	@Override
	public void setDiscountOverlaps(boolean v) {
		// TODO Auto-generated method stub
		super.setDiscountOverlaps(v);
	}

	@Override
	public float sloppyFreq(int distance) {
		// TODO Auto-generated method stub
		return super.sloppyFreq(distance);
	}

	@Override
	public float tf(float freq) {
		return (float) (1.0f / Math.sqrt(freq));
	}
}