package crypto.listener;

import boomerang.results.ForwardBoomerangResults;
import boomerang.scene.Statement;
import boomerang.scene.Val;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;
import crypto.analysis.EnsuredCrySLPredicate;
import crypto.analysis.IAnalysisSeed;
import crypto.extractparameter.CallSiteWithParamIndex;
import crypto.extractparameter.ExtractedValue;
import crypto.rules.ISLConstraint;
import typestate.TransitionFunction;

import java.util.Collection;
import java.util.Set;

public interface IResultsListener {

    void typestateAnalysisResults(IAnalysisSeed seed, ForwardBoomerangResults<TransitionFunction> results);

    void collectedValues(IAnalysisSeed seed, Multimap<CallSiteWithParamIndex, ExtractedValue> collectedValues);

    void checkedConstraints(IAnalysisSeed seed, Collection<ISLConstraint> constraints);

    void ensuredPredicates(Table<Statement, Val, Set<EnsuredCrySLPredicate>> existingPredicates);
}
