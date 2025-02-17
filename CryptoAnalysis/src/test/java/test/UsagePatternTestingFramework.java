package test;

import boomerang.BackwardQuery;
import boomerang.Boomerang;
import boomerang.results.BackwardBoomerangResults;
import boomerang.scene.CallGraph;
import boomerang.scene.ControlFlowGraph;
import boomerang.scene.DataFlowScope;
import boomerang.scene.InvokeExpr;
import boomerang.scene.Method;
import boomerang.scene.SootDataFlowScope;
import boomerang.scene.Statement;
import boomerang.scene.Val;
import boomerang.scene.jimple.JimpleMethod;
import boomerang.scene.jimple.SootCallGraph;
import boomerang.util.AccessPath;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import crypto.analysis.CryptoScanner;
import crypto.cryslhandler.RulesetReader;
import crypto.listener.IErrorListener;
import crypto.listener.IResultsListener;
import crypto.preanalysis.TransformerSetup;
import crypto.rules.CrySLRule;
import soot.Scene;
import soot.SceneTransformer;
import soot.options.Options;
import test.assertions.Assertions;
import test.assertions.CallToErrorCountAssertion;
import test.assertions.CallToForbiddenMethodAssertion;
import test.assertions.ConstraintErrorCountAssertion;
import test.assertions.DependentErrorAssertion;
import test.assertions.ExtractedValueAssertion;
import test.assertions.ForbiddenMethodErrorCountAssertion;
import test.assertions.HasEnsuredPredicateAssertion;
import test.assertions.ImpreciseValueExtractionErrorCountAssertion;
import test.assertions.InAcceptingStateAssertion;
import test.assertions.IncompleteOperationErrorCountAssertion;
import test.assertions.InstanceOfErrorCountAssertion;
import test.assertions.MissingTypestateChange;
import test.assertions.NeverTypeOfErrorCountAssertion;
import test.assertions.NoCallToErrorCountAssertion;
import test.assertions.NoMissingTypestateChange;
import test.assertions.NotHardCodedErrorCountAssertion;
import test.assertions.NotHasEnsuredPredicateAssertion;
import test.assertions.NotInAcceptingStateAssertion;
import test.assertions.PredicateContradiction;
import test.assertions.PredicateErrorCountAssertion;
import test.assertions.TypestateErrorCountAssertion;
import test.core.selfrunning.AbstractTestingFramework;
import test.core.selfrunning.ImprecisionException;
import wpds.impl.Weight;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class UsagePatternTestingFramework extends AbstractTestingFramework {

	@Override
	protected SceneTransformer createAnalysisTransformer() throws ImprecisionException {

		// Required since Soot 4.3.0
		Options.v().setPhaseOption("jb.sils", "enabled:false");

		return new SceneTransformer() {

			protected void internalTransform(String phaseName, Map<String, String> options) {
				Collection<CrySLRule> ruleset;
				try {
					RulesetReader reader = new RulesetReader();
					ruleset = reader.readRulesFromPath(getRulesetPath());
				} catch (IOException e) {
					throw new RuntimeException("Could not read rules: " + e.getMessage());
				}

				TransformerSetup.v().setupPreTransformer(ruleset);
				CallGraph callGraph = new SootCallGraph();
				TestDataFlowScope dataFlowScope = new TestDataFlowScope(ruleset);

				// Setup test listener
				Collection<Assertion> assertions = extractBenchmarkMethods(JimpleMethod.of(sootTestMethod), callGraph);
				IErrorListener errorListener = new UsagePatternErrorListener(assertions);
				IResultsListener resultsListener = new UsagePatternResultsListener(assertions);

				// Setup scanner
				CryptoScanner scanner = new CryptoScanner(ruleset) {

					@Override
					public CallGraph callGraph() {
						return callGraph;
					}

					@Override
					public DataFlowScope getDataFlowScope() {
						return dataFlowScope;
					}
				};

				scanner.addErrorListener(errorListener);
				scanner.addResultsListener(resultsListener);

				scanner.scan();

				// Evaluate results
				List<Assertion> unsound = Lists.newLinkedList();
				List<Assertion> imprecise = Lists.newLinkedList();

				for (Assertion r : assertions) {
					if (!r.isSatisfied()) {
						unsound.add(r);
					}
				}

				for (Assertion r : assertions) {
					if (r.isImprecise()) {
						imprecise.add(r);
					}
				}

				StringBuilder errors = new StringBuilder();
				if (!unsound.isEmpty()) {
					errors.append("\nUnsound results: \n").append(Joiner.on("\n").join(unsound));
				}
				if (!imprecise.isEmpty()) {
					errors.append("\nImprecise results: \n").append(Joiner.on("\n").join(imprecise));
				}
				if (!errors.toString().isEmpty()) {
					throw new RuntimeException(errors.toString());
				}
			}
		};
	}

	protected abstract String getRulesetPath();

	@Override
	public List<String> getIncludeList() {
		return new ArrayList<>();
	}

	@Override
	public List<String> excludedPackages() {
		return new ArrayList<>();
	}


	private Set<Assertion> extractBenchmarkMethods(Method testMethod, CallGraph callGraph) {
		Set<Assertion> results = new HashSet<>();
		extractBenchmarkMethods(testMethod, callGraph, results, new HashSet<>());
		return results;
	}

	private void extractBenchmarkMethods(Method method, CallGraph callGraph, Set<Assertion> queries, Set<Method> visited) {
		if (visited.contains(method)) {
			return;
		}
		visited.add(method);

		for (CallGraph.Edge callSite : callGraph.edgesInto(method)) {
			Method callee = callSite.tgt();
			extractBenchmarkMethods(callee, callGraph, queries, visited);
		}

		for (Statement statement : method.getStatements()) {
			if (!statement.containsInvokeExpr()) {
				continue;
			}

			InvokeExpr invokeExpr = statement.getInvokeExpr();

			if (!invokeExpr.getMethod().getDeclaringClass().toString().equals(Assertions.class.getName())) {
				continue;
			}

			String invocationName = invokeExpr.getMethod().getName();

			if (invocationName.startsWith("extValue")) {
				Val param = invokeExpr.getArg(0);
				if (!param.isIntConstant()) {
					continue;
				}

				for (Statement pred : getPredecessorsNotBenchmark(statement)) {
					queries.add(new ExtractedValueAssertion(pred, param.getIntValue()));
				}
			}

			if (invocationName.startsWith("callToForbiddenMethod")) {
				for (Statement pred : getPredecessorsNotBenchmark(statement)) {
					queries.add(new CallToForbiddenMethodAssertion(pred));
				}
			}

			if (invocationName.startsWith("mustBeInAcceptingState")) {
				Val param = invokeExpr.getArg(0);
				if (!param.isLocal()) {
					continue;
				}

				Set<Val> aliases = getAliasesForValue(callGraph, statement, param);

				for (Statement pred : getPredecessorsNotBenchmark(statement)) {
					queries.add(new InAcceptingStateAssertion(pred, aliases));
				}
			}
			
			//if (invocationName.startsWith("violatedConstraint")) {
			//	queries.add(new ConstraintViolationAssertion(statement));
			//}

			if (invocationName.startsWith("hasEnsuredPredicate")){
				Val param = invokeExpr.getArg(0);
				if (!param.isLocal()) {
					continue;
				}

				Set<Val> aliases = getAliasesForValue(callGraph, statement, param);

				if (invokeExpr.getArgs().size() == 2) {
					// predicate name is passed as parameter
					Val predNameParam = invokeExpr.getArg(1);
					if (!predNameParam.isStringConstant()) {
						continue;
					}
					String predName = predNameParam.getStringValue();
					for (Statement pred : getPredecessorsNotBenchmark(statement)) {
						queries.add(new HasEnsuredPredicateAssertion(pred, aliases, predName));
					}
				} else {
					for (Statement pred : getPredecessorsNotBenchmark(statement)) {
						queries.add(new HasEnsuredPredicateAssertion(pred, aliases));
					}
				}
			}
			
			if (invocationName.startsWith("notHasEnsuredPredicate")) {
				Val param = invokeExpr.getArg(0);
				if (!param.isLocal()) {
					continue;
				}

				Set<Val> aliases = getAliasesForValue(callGraph, statement, param);

				if (invokeExpr.getArgs().size() == 2) {
					// predicate name is passed as parameter
					Val predNameParam = invokeExpr.getArg(1);
					if (!predNameParam.isStringConstant()) {
						continue;
					}
					String predName = predNameParam.getStringValue();
					for (Statement pred : getPredecessorsNotBenchmark(statement)) {
						queries.add(new NotHasEnsuredPredicateAssertion(pred, aliases, predName));
					}
				} else {
					for (Statement pred : getPredecessorsNotBenchmark(statement)) {
						queries.add(new NotHasEnsuredPredicateAssertion(pred, aliases));
					}
				}
			}
			
			if (invocationName.startsWith("mustNotBeInAcceptingState")) {
				Val param = invokeExpr.getArg(0);
				if (!param.isLocal()) {
					continue;
				}

				Set<Val> aliases = getAliasesForValue(callGraph, statement, param);
				for (Statement pred : getPredecessorsNotBenchmark(statement)) {
					queries.add(new NotInAcceptingStateAssertion(pred, aliases));
				}
			}

			if (invocationName.startsWith("predicateContradiction")) {
				queries.add(new PredicateContradiction());
			}

			if (invocationName.startsWith("missingTypestateChange")) {
				for (Statement pred : getPredecessorsNotBenchmark(statement)) {
					queries.add(new MissingTypestateChange(pred));
				}
			}

			if (invocationName.startsWith("noMissingTypestateChange")) {
				for (Statement pred : getPredecessorsNotBenchmark(statement)) {
					queries.add(new NoMissingTypestateChange(pred));
				}
			}
			
			if (invocationName.startsWith("predicateErrors")) {
				Val param = invokeExpr.getArg(0);
				if (!param.isIntConstant()) {
					continue;
				}
				queries.add(new PredicateErrorCountAssertion(param.getIntValue()));
			}

			if (invocationName.startsWith("constraintErrors")) {
				Val param = invokeExpr.getArg(0);
				if (!param.isIntConstant()) {
					continue;
				}
				queries.add(new ConstraintErrorCountAssertion(param.getIntValue()));
			}

			if (invocationName.startsWith("typestateErrors")) {
				Val param = invokeExpr.getArg(0);
				if (!param.isIntConstant()) {
					continue;
				}
				queries.add(new TypestateErrorCountAssertion(param.getIntValue()));
			}

			if (invocationName.startsWith("incompleteOperationErrors")) {
				Val param = invokeExpr.getArg(0);
				if (!param.isIntConstant()) {
					continue;
				}
				queries.add(new IncompleteOperationErrorCountAssertion(param.getIntValue()));
			}

			if (invocationName.startsWith("forbiddenMethodErrors")) {
				Val param = invokeExpr.getArg(0);
				if (!param.isIntConstant()) {
					continue;
				}
				queries.add(new ForbiddenMethodErrorCountAssertion(param.getIntValue()));
			}

			if (invocationName.startsWith("impreciseValueExtractionErrors")) {
				Val param = invokeExpr.getArg(0);
				if (!param.isIntConstant()) {
					continue;
				}
				queries.add(new ImpreciseValueExtractionErrorCountAssertion(param.getIntValue()));
			}

			if (invocationName.startsWith("callToErrors")) {
				Val param = invokeExpr.getArg(0);
				if (!param.isIntConstant()) {
					continue;
				}
				queries.add(new CallToErrorCountAssertion(param.getIntValue()));
			}

			if (invocationName.startsWith("noCallToErrors")) {
				Val param = invokeExpr.getArg(0);
				if (!param.isIntConstant()) {
					continue;
				}
				queries.add(new NoCallToErrorCountAssertion(param.getIntValue()));
			}

			if (invocationName.startsWith("neverTypeOfErrors")) {
				Val param = invokeExpr.getArg(0);
				if (!param.isIntConstant()) {
					continue;
				}
				queries.add(new NeverTypeOfErrorCountAssertion(param.getIntValue()));
 			}

			if (invocationName.startsWith("notHardCodedErrors")) {
				Val param = invokeExpr.getArg(0);
				if (!param.isIntConstant()) {
					continue;
				}
				queries.add(new NotHardCodedErrorCountAssertion(param.getIntValue()));
			}

			if (invocationName.startsWith("instanceOfErrors")) {
				Val param = invokeExpr.getArg(0);
				if (!param.isIntConstant()) {
					continue;
				}
				queries.add(new InstanceOfErrorCountAssertion(param.getIntValue()));
			}

			if (invocationName.startsWith("dependentError")) {
				// extract parameters
				List<Val> params = invokeExpr.getArgs();
				if (!params.stream().allMatch(Val::isIntConstant)) {
					continue;
				}
				int thisErrorID = params.remove(0).getIntValue();
				int[] precedingErrorIDs = params.stream().mapToInt(Val::getIntValue).toArray();
				for (Statement pred : getPredecessorsNotBenchmark(statement)) {
					queries.add(new DependentErrorAssertion(pred, thisErrorID, precedingErrorIDs));
				}
			}

			// connect DependentErrorAssertions
			Set<Assertion> depErrors = queries.stream().filter(q -> q instanceof DependentErrorAssertion).collect(Collectors.toSet());
			depErrors.forEach(ass -> ((DependentErrorAssertion)ass).registerListeners(depErrors));
		}
	}

	private Set<Statement> getPredecessorsNotBenchmark(Statement stmt) {
		Set<Statement> res = Sets.newHashSet();
		Set<Statement> visited = Sets.newHashSet();
		LinkedList<Statement> workList = Lists.newLinkedList();
		workList.add(stmt);

		while (!workList.isEmpty()) {
			Statement curr = workList.poll();

			if (!visited.add(curr)) {
				continue;
			}

			if (curr.containsInvokeExpr()) {
				String invokedClassName = curr.getInvokeExpr().getMethod().getDeclaringClass().getName();
				String assertionClassName = Assertions.class.getName();

				if (!invokedClassName.equals(assertionClassName)) {
					res.add(curr);
					continue;
				}
			}

			Collection<Statement> preds = stmt.getMethod().getControlFlowGraph().getPredsOf(curr);
			workList.addAll(preds);
		}
		return res;
	}

	@SuppressWarnings("deprecation")
	private Set<Val> getAliasesForValue(CallGraph callGraph, Statement stmt, Val val) {
		Set<Val> aliases = new HashSet<>();
		aliases.add(val);

		for (Statement pred : stmt.getMethod().getControlFlowGraph().getPredsOf(stmt)) {
			ControlFlowGraph.Edge edge = new ControlFlowGraph.Edge(pred, stmt);
			BackwardQuery query = BackwardQuery.make(edge, val);

			Boomerang solver = new Boomerang(callGraph, SootDataFlowScope.make(Scene.v()));
			BackwardBoomerangResults<Weight.NoWeight> results = solver.solve(query);

			for (AccessPath accessPath : results.getAllAliases()) {
				aliases.add(accessPath.getBase());
			}
		}
		return aliases;
	}
}
