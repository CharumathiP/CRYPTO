package tests.headless;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;

import boomerang.BackwardQuery;
import boomerang.Query;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import boomerang.results.ForwardBoomerangResults;
import crypto.HeadlessCryptoScanner;
import crypto.analysis.AnalysisSeedWithSpecification;
import crypto.analysis.CrySLAnalysisListener;
import crypto.analysis.CrySLRulesetSelector;
import crypto.analysis.CrySLRulesetSelector.Ruleset;
import crypto.analysis.CryptoScannerSettings.ReportFormat;
import crypto.analysis.EnsuredCrySLPredicate;
import crypto.analysis.IAnalysisSeed;
import crypto.analysis.errors.AbstractError;
import crypto.exceptions.CryptoAnalysisException;
import crypto.extractparameter.CallSiteWithParamIndex;
import crypto.extractparameter.ExtractedValue;
import crypto.interfaces.ISLConstraint;
import crypto.rules.CrySLPredicate;
import crypto.rules.CrySLRule;
import crypto.rules.CrySLRuleReader;
import soot.G;
import sync.pds.solver.nodes.Node;
import test.IDEALCrossingTestingFramework;
import tests.headless.FindingsType.FalseNegatives;
import tests.headless.FindingsType.FalsePositives;
import tests.headless.FindingsType.NoFalseNegatives;
import tests.headless.FindingsType.NoFalsePositives;
import tests.headless.FindingsType.TruePositives;
import typestate.TransitionFunction;

public abstract class AbstractHeadlessTest {

	/**
	 * To run these test cases in Eclipse, specify your maven home path as JVM argument: -Dmaven.home=<PATH_TO_MAVEN_BIN>
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractHeadlessTest.class);

	private static boolean VISUALIZATION = false;
	private static boolean PROVIDER_DETECTION = true;
	private CrySLAnalysisListener errorCountingAnalysisListener;
	private Table<String, Class<?>, Integer> errorMarkerCountPerErrorTypeAndMethod = HashBasedTable.create();
	
	/**
	 * List for storing the section names to be ignored
	 */
	private static List<String> ignoredSections = Collections.emptyList();
	
	/**
	 * Formats of the analysis report
	 */
	private static Set<ReportFormat> reportFormats = new HashSet<>();
	
	public static void setReportFormat(ReportFormat reportFormat) {
		// use this method to add exactly one report format
		AbstractHeadlessTest.reportFormats.clear();
		AbstractHeadlessTest.reportFormats.add(reportFormat);
	}
	
	public static void setReportFormat(ReportFormat ...formats) {
		// use this method to add multiple report formats
		AbstractHeadlessTest.reportFormats.clear();
		
		for (ReportFormat format : formats) {
			AbstractHeadlessTest.reportFormats.add(format);
		}
	}

	public static void setVISUALIZATION(boolean vISUALIZATION) {
		VISUALIZATION = vISUALIZATION;
	}
	
	public static void setProviderDetection(boolean providerDetection) {
		PROVIDER_DETECTION = providerDetection;
	}

	public static void setIgnoredSections(List<String> ignoredSectionsList) {
		ignoredSections = ignoredSectionsList;
	}
	
	protected MavenProject createAndCompile(String mavenProjectPath) {
		MavenProject mi = new MavenProject(mavenProjectPath);
		mi.compile();
		return mi;
	}

	protected HeadlessCryptoScanner createScanner(MavenProject mp) {
		return createScanner(mp, Ruleset.JavaCryptographicArchitecture);
	}

	protected HeadlessCryptoScanner createScanner(MavenProject mp, Ruleset ruleset) {
		G.reset();
		HeadlessCryptoScanner scanner = new HeadlessCryptoScanner() {
			@Override
			protected String sootClassPath() {
				return mp.getBuildDirectory() + (mp.getFullClassPath().equals("") ? "" : File.pathSeparator + mp.getFullClassPath());
			}

			@Override
			protected List<CrySLRule> getRules() {
				try {
					List<CrySLRule> rules = CrySLRulesetSelector.makeFromRuleset(IDEALCrossingTestingFramework.RULES_BASE_DIR, ruleset);
					HeadlessCryptoScanner.setRules(rules);
					return rules;
				} catch (CryptoAnalysisException e) {
					LOGGER.error("Error happened when getting the CrySL rules from the specified directory: " + IDEALCrossingTestingFramework.RULES_BASE_DIR, e);
				}
				return null;
			}

			@Override
			protected String applicationClassPath() {
				return mp.getBuildDirectory();
			}

			@Override
			protected CrySLAnalysisListener getAdditionalListener() {
				return errorCountingAnalysisListener;
			}

			@Override
			protected String getOutputFolder() {
				File file = new File("cognicrypt-output/");
				file.mkdirs();
				return VISUALIZATION ? file.getAbsolutePath() : super.getOutputFolder();
			}

			@Override
			protected boolean enableVisualization() {
				return VISUALIZATION;
			}
			
			@Override
			protected List<String> ignoredSections(){
				return ignoredSections;
			}

			@Override
			protected boolean providerDetection() {
				return PROVIDER_DETECTION;
			}
			
			@Override
			protected Set<ReportFormat> reportFormats(){
				return VISUALIZATION ? reportFormats : new HashSet<>();
			}
		};
		return scanner;
	}

	@Before
	public void setup() {
		errorCountingAnalysisListener = new CrySLAnalysisListener() {
			@Override
			public void reportError(AbstractError error) {
				Integer currCount;
				String errorClassName = error.getErrorLocation().getMethod().getDeclaringClass().getName().toString();
				String methodContainingError = error.getErrorLocation().getMethod().toString();
				if (errorMarkerCountPerErrorTypeAndMethod.contains(methodContainingError, error.getClass())) {
					currCount = errorMarkerCountPerErrorTypeAndMethod.get(methodContainingError, error.getClass());
				} else {
					currCount = 0;
				}
				Integer newCount = --currCount;
				errorMarkerCountPerErrorTypeAndMethod.put(methodContainingError, error.getClass(), newCount);
			}

			@Override
			public void onSeedTimeout(Node<Statement, Val> seed) {}

			@Override
			public void onSeedFinished(IAnalysisSeed seed, ForwardBoomerangResults<TransitionFunction> solver) {}

			@Override
			public void ensuredPredicates(Table<Statement, Val, Set<EnsuredCrySLPredicate>> existingPredicates, Table<Statement, IAnalysisSeed, Set<CrySLPredicate>> expectedPredicates,
					Table<Statement, IAnalysisSeed, Set<CrySLPredicate>> missingPredicates) {}

			@Override
			public void discoveredSeed(IAnalysisSeed curr) {}

			@Override
			public void collectedValues(AnalysisSeedWithSpecification seed, Multimap<CallSiteWithParamIndex, ExtractedValue> collectedValues) {}

			@Override
			public void checkedConstraints(AnalysisSeedWithSpecification analysisSeedWithSpecification, Collection<ISLConstraint> relConstraints) {}

			@Override
			public void seedStarted(IAnalysisSeed analysisSeedWithSpecification) {}

			@Override
			public void boomerangQueryStarted(Query seed, BackwardQuery q) {}

			@Override
			public void boomerangQueryFinished(Query seed, BackwardQuery q) {}

			@Override
			public void beforePredicateCheck(AnalysisSeedWithSpecification analysisSeedWithSpecification) {}

			@Override
			public void beforeConstraintCheck(AnalysisSeedWithSpecification analysisSeedWithSpecification) {}

			@Override
			public void beforeAnalysis() {}

			@Override
			public void afterPredicateCheck(AnalysisSeedWithSpecification analysisSeedWithSpecification) {}

			@Override
			public void afterConstraintCheck(AnalysisSeedWithSpecification analysisSeedWithSpecification) {}

			@Override
			public void afterAnalysis() {}

			@Override
			public void onSecureObjectFound(IAnalysisSeed analysisObject) {}

			@Override
			public void addProgress(int processedSeeds, int workListsize) {}
		};
	}

	protected void assertErrors() {
		boolean errorFound = false;
		StringBuilder builder = new StringBuilder();
		for (Cell<String, Class<?>, Integer> c : errorMarkerCountPerErrorTypeAndMethod.cellSet()) {
			Integer value = c.getValue();
			if (value != 0) {
				builder.append("\n");
				if (value > 0) {
					builder.append("\tFound " + value + " too few errors of type " + c.getColumnKey() + " in method " + c.getRowKey());
				} else {
					builder.append("\tFound " + Math.abs(value) + " too many  errors of type " + c.getColumnKey() + " in method " + c.getRowKey());
				}
				errorFound = true;
			}
		}

		if (errorFound) {
			throw new RuntimeException("Tests not executed as planned:" + builder);
		}
	}

	protected void setErrorsCount(String methodSignature, Class<?> errorType, int errorMarkerCount) {
		if (errorMarkerCountPerErrorTypeAndMethod.contains(methodSignature, errorType)) {
			throw new RuntimeException("Error Type already specified for this method");
		}
		errorMarkerCountPerErrorTypeAndMethod.put(methodSignature, errorType, errorMarkerCount);
	}

	protected void setErrorsCount(Class<?> errorType, TruePositives tp, FalsePositives fp, FalseNegatives fn, String methodSignature) {
		if (errorMarkerCountPerErrorTypeAndMethod.contains(methodSignature, errorType)) {
			int errorCount = errorMarkerCountPerErrorTypeAndMethod.get(methodSignature, errorType);
			errorMarkerCountPerErrorTypeAndMethod.remove(methodSignature, errorType);
			errorMarkerCountPerErrorTypeAndMethod.put(methodSignature, errorType, tp.getNumberOfFindings() + fp.getNumberOfFindings() + errorCount);
		} else {
			errorMarkerCountPerErrorTypeAndMethod.put(methodSignature, errorType, tp.getNumberOfFindings() + fp.getNumberOfFindings());
		}
	}

	protected void setErrorsCount(Class<?> errorType, TruePositives tp, String methodSignature) {
		setErrorsCount(errorType, tp, new NoFalsePositives(), new NoFalseNegatives(), methodSignature);
	}

	protected void setErrorsCount(Class<?> errorType, TruePositives tp, FalseNegatives fn, String methodSignature) {
		setErrorsCount(errorType, tp, new NoFalsePositives(), fn, methodSignature);
	}

	protected void setErrorsCount(Class<?> errorType, FalsePositives fp, String methodSignature) {
		setErrorsCount(errorType, new TruePositives(0), fp, new NoFalseNegatives(), methodSignature);
	}

	protected void setErrorsCount(Class<?> errorType, FalseNegatives fn, String methodSignature) {
		setErrorsCount(errorType, new TruePositives(0), new NoFalsePositives(), fn, methodSignature);
	}

	protected void setErrorsCount(ErrorSpecification errorSpecification) {
		if (errorSpecification.getTotalNumberOfFindings() > 0) {
			for (TruePositives tp : errorSpecification.getTruePositives()) {
				setErrorsCount(tp.getErrorType(), tp, new NoFalsePositives(), new NoFalseNegatives(), errorSpecification.getMethodSignature());
			}
			for (FalsePositives fp : errorSpecification.getFalsePositives()) {
				setErrorsCount(fp.getErrorType(), new TruePositives(0), fp, new NoFalseNegatives(), errorSpecification.getMethodSignature());
			}
		}
	}
}
