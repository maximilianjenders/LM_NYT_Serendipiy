package CoreCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import util.DBConnection;
import util.Helper;

import dataclass.Article;
import dataclass.ArticleFeature;
import dataclass.ArticleFeatureMaintainer;
import dataclass.ArticleProbability;

/***
 * Implementation of CoreCalculator, straightforward with one LM
 * @author Max
 *
 */
public class SimpleCoreCalculator extends CoreCalculator {

	private HashSet<Article> coreArticles;
	//Probabilities only used for entropy calculation, since theta has to be smooothed for prob calculation
	private HashMap<Integer, Integer> theta; //map: FeatureID -> count
	
	private static final double ENTROPYTHRESHOLD = 0.01; 
	boolean advancedAlgorithm = false;
	
	/***
	 * The CoreCalculator calculates the core documents
	 * @param initialDocumentID the ID of the start document
	 */
	
	public SimpleCoreCalculator(ArticleFeatureMaintainer afm) {
		fm = afm;
		coreArticles = new HashSet<Article>();
		executorPool = Executors.newFixedThreadPool(NUMTHREADS);
	}

	/***
	 * Iterates over all candidate articles to determine the core articles
	 */
	public void extractCoreArticles() {
//		calculateSmoothedLogProbability(findArticle(0, candidateArticles), theta);
		//The core has to consist of a few documents, and the delta entropy changes largely for the first few additions
		for (int i = 0; i <= 10; i++) addMostProbableDocument();
		//Iterate and find argmax_d P(d | theta), then add to core list and recalculate theta
		while (getAvgEntropyDelta() > ENTROPYTHRESHOLD) {
			addMostProbableDocument();
		}
	}
	
	/***
	 * Finds the k most serendipitous Documents
	 * @param numRecommendations determines the k
	 */
	public void findSerendipitousDocs(int numRecomendations) {
		HashMap<Integer, Integer> thetaRed = calculateThetaRed(THETARED_K, theta);
		
		findSerendipitousDocs(numRecomendations, candidateArticles, thetaRed, theta);
	}
	
	
	
	/***
	 * Calculates the document that has the highes probability of being generated by theta, and then adds
	 * the article to the list of core documents. Theta will be updated and the newly added article removed 
	 * from the list of candidate articles.
	 * 
	 * Most probable: argmax_d P(d | theta) or argmin_d [- log P(d | theta)]
	 */
	public void addMostProbableDocument() {
		Article a = findMostProbableDocument(theta);
		printArticleData(a);
		addCoreArticle(a);
	}
	
	/***
	 * Finds the most probable article for the current configuration of core articles
	 * @return the most probable Article
	 */
	public Article getMostProbableDocument() {
		return findMostProbableDocument(theta);
	}
	
	/***
	 * Finds the least probable article for the current configuration of core articles
	 * @return the least probable Article
	 */
	public Article getLeastProbableDocument() {
		return findLeastProbableDocument(theta);
	}
	
	/***
	 * Prints data abouit article to the console
	 * @param articleToBeAdded
	 */
	private void printArticleData(Article articleToBeAdded) {
		if (printArticleComparisonData) {
			//Print out
			CoreSupporter supporter = new CoreSupporter();
			ArrayList<Article> core = new ArrayList<Article>();
			for (Article a : coreArticles) {
				core.add(a);
			}
			supporter.setArticleFeatures(core, articleToBeAdded);
			supporter.printArticle1Data();
			supporter.printArticle2Data();
			supporter.printIntersectionData();
		}
		if (printCoreArticleTitles) {
			Helper.print("Adding core document " + articleToBeAdded.getID() + "(" + 
					articleToBeAdded.getTopic() + "): " + articleToBeAdded.getTitle() + 
					" with an entropy delta of " + getAvgEntropyDelta() + "." );
		}
	}
	
	/***
	 * Adds an article to the set of core articles, then removes the article from the article list and recalculates theta
	 * @param article the Article to add
	 */
	public void addCoreArticle(Article article) {
		coreArticles.add(article);
		candidateArticles.remove(article);
		theta = calculateTheta(coreArticles);
		calculateThetaEntropy(theta);
		
	}
	
	/***
	 * Specifies the initial article
	 */
	public void addInitialDocument(int initialDocumentID) {
		if (fm.getArticle(initialDocumentID) == null) {
			Helper.printErr("ARTICLE NOT FOUND");
			System.exit(-1);
		}
		
		//Initialize the core article set with the inital document
		extractCandidateArticles();
		initialArticle = findArticle(initialDocumentID, candidateArticles);
		coreArticles.add(initialArticle);
		removeArticle(initialDocumentID, candidateArticles);
		theta = calculateTheta(coreArticles);
		
		if (printInitialDocumentData) Helper.print("Initial Document is " + initialDocumentID + "(" + initialArticle.getTopic() + "): " + initialArticle.getTitle());
	}

	/***
	 * Find the ranked position the initial document itself would be in
	 */
	public void findInitialDocRecommendationPosition() {
		candidateArticles.add(initialArticle);
		
		if (candidateArticles.size() == 0) {
			System.err.println("No more candidate articles");
			System.exit(-1);
		}

		Set<Future<ArticleProbability>> probabilities = startParallelProbabilityCalculations(candidateArticles, theta);
				
		double initialProb = ProbabiliyCalculator.calculateSmoothedLogProability(initialArticle, theta);
		//the articleID of the most probable article
		int count = 0;
				
		for (Future<ArticleProbability> future : probabilities) {
			try {
				ArticleProbability prob = future.get();
				if (prob.getLogProbability() > initialProb ) {
					count++;
				}
			} catch (InterruptedException e) {
				Helper.printErr("ERROR WHEN CALCULATING PROBABLITIY:");
				e.printStackTrace();
			} catch (ExecutionException e) {
				Helper.printErr("ERROR WHEN CALCULATING PROBABLITIY:");
				e.printStackTrace();
			}
		}
		
		Helper.print("There are " + count + " articles who have a higher prob than " + initialArticle.getID());
	}
	public void run(int initialDocumentID, int numRecomendations) {
		Helper.print("Setting initial document");
		addInitialDocument(initialDocumentID);
		Helper.print("Extracting core articles");
		extractCoreArticles();
		Helper.print("Storing data");
		storeData();
		Helper.print("Requesting serendipitious documents");
		findSerendipitousDocs(numRecomendations);
		
	}

	public void storeData() {
		if (storeData) DBConnection.getInstance().updateAssignments(initialArticle.getID(), coreArticles, advancedAlgorithm, dataSlice);
	}
	
	@Override
	public void assignToMainCore(ArrayList<Integer> mainArticleIDs) {
		for (int articleID : mainArticleIDs) {
			Article article = findCandidateArticle(articleID);
			coreArticles.add(article);
			addToTheta(article, theta);
			candidateArticles.remove(article);
		}
	}

	@Override
	//Already in candidate list
	public void setRemainingArticlesNonCore() {
	}
}