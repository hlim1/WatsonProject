package edu.arizona.cs;

// Java classes
import java.io.*;
import java.util.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
// Lucene classes
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.search.similarities.BooleanSimilarity;
import org.apache.lucene.search.similarities.TFIDFSimilarity;
import org.apache.lucene.search.similarities.BM25Similarity;
// Standford NLP classes
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.coref.CorefCoreAnnotations.CorefChainAnnotation;
import edu.stanford.nlp.coref.data.CorefChain;
import edu.stanford.nlp.ling.CoreAnnotations.NamedEntityTagAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.PartOfSpeechAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.LemmaAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TextAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokensAnnotation;
import edu.stanford.nlp.ling.CoreLabel;

class Wiki_Page
{
    String Title;
    String Category;
    String Content;
}

class Questions
{
    String Category;
    String Clue;
    String Answer;
}

class RunningTime
{
    long lemmatizing;
    long retrieving;
    long indexing;
    long searching;
}

public class Watson
{
    // A regular expression pattern to find the page title
    public static final String TITLE_REGEX = "^\\[\\[(.+)\\]\\]";
    public static final String INDEX_DIR = "./wiki-index-dir"; 

    public static final Pattern pattern = Pattern.compile(TITLE_REGEX);

    public static final int CATEGORY    = 0;
    public static final int CLUE        = 1;
    public static final int ANSWER      = 2;
    public static final int WHITESPACE  = 3;
    public static final double NUMBER_OF_Q = 100.0;

    private Directory indexDirectory;
    private StandardAnalyzer analyzer;
    private Wiki_Page wikiPage = new Wiki_Page();
    private static RunningTime runningTime = new RunningTime();

    // An array list of Part-of-Speech tags, which I will only considers important and extract from the contents
    private static ArrayList<String> posTags = new ArrayList<>(Arrays.asList("NN", "NNS", "NNP", "NNPS", "VB", "VBN", "VBP", "VBD", "VBZ", "JJ"));

    public static void main( String[] args ) throws java.io.FileNotFoundException,java.io.IOException, ParseException, java.net.URISyntaxException
    {
        // Initializing the running times of each operation to 0 at the beginning of the program.
        runningTime.lemmatizing = 0;
        runningTime.retrieving = 0;
        runningTime.indexing = 0;
        runningTime.searching = 0;

        File   questionsFile;
        ArrayList<Questions> questions = new ArrayList<>();

        // Declaring the main Watson object.
        Watson watson = new Watson();

        // Retrieve questions file
        questionsFile = watson.getFile("Questions.txt");
        System.out.println("A questions file: " + questionsFile.toString() + " retrieved.");

        // Tokenize and lemmatize each search query
        questions = watson.LemmatizeQuestions(questionsFile); 
      
        File[] wikiFiles;
        ArrayList<Wiki_Page> wikiPages = new ArrayList<>();

        // Get wikipedia files
        wikiFiles = watson.getWikiFiles();
        System.out.printf("%d wiki files retrieved.\n", wikiFiles.length);

        // The commented out code stub is for lemmatizing and indexing. Since both operations
        // are already completed and stored on disk, they are not necessary to re-run. Though,
        // it's needed for any future work and record, I'm leaving it in here rather than removing it
        /* ******************************************************************************************
        **********************************************************************************************/

        // Tokenize and lemmatize each wiki page's content and store it into wiki page list
        wikiPages = watson.lemmatizeWikiPages(wikiFiles);

        watson.addLemmatizedDocsToFile(wikiPages);

        // Tokenize and lemmatize each search query
        questions = watson.LemmatizeQuestions(questionsFile); 
     
        // File lemmatizedFile = watson.getFile("LemmatizedWikiPages.txt"); 
        wikiPages = watson.retrieveLammatizedWiki(lemmatizedFile);

        // Index each lemmatized wiki page
        watson.IndexDocuments(wikiPages);
        //============================================================================================
        HashMap<String, Double> positions = new HashMap<>();
        IndexSearcher searcher = createSearcher();

        int numberCorrectlyFound = watson.query(searcher, questions, positions);
        double accuracy = ((double)numberCorrectlyFound / NUMBER_OF_Q) * 100;
        double mrr = watson.MRR(positions);

        // Prints MRR and accuracy of querying
        System.out.println("=======================================================");
        System.out.println("Measurements:");
        System.out.printf("MRR: %.2f.\n", mrr);
        System.out.printf("The accuracy: %.2f%%\n", accuracy);
        
        // Prints processing time for each main functionalities of the program 
        System.out.println("=======================================================");
        System.out.println("Processing Time:");
        System.out.printf("Lemmatizing took %d Secs.\n", runningTime.lemmatizing);
        System.out.printf("Retrieving lemmatized pages took %d Secs.\n", runningTime.retrieving);
        System.out.printf("Indexing took %d Secs.\n", runningTime.indexing);
        System.out.printf("Searching took %d mSecs.\n", runningTime.searching);
        System.out.println("=======================================================");
    }

    /*
     *  All wiki files are stored in the "/taret/classes/wiki-pages" directory, so retrieve all the files from the directory
     *  and store them into the files array object. Then, return the array of files.
     */
    public File[] getWikiFiles() throws java.io.FileNotFoundException, java.io.IOException, ParseException, java.net.URISyntaxException
    {
        String corpusDir = "wiki-pages";
        ClassLoader classLoader = getClass().getClassLoader();
        File wikiPageDir = new File(classLoader.getResource(corpusDir).getFile());
        File[] wikiFiles = wikiPageDir.listFiles();

        return wikiFiles;
    }

    /*
     *  Retrieve the questions file that lives under "/target/classes" directory and return to the caller.
     */
    public File getFile(String filename) throws java.io.FileNotFoundException, java.io.IOException, ParseException, java.net.URISyntaxException
    {
        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource(filename).getFile());

        return file;
    }

    /*
     *  For each wiki page stored in the wikiFiles, lemmatize and tokenize the content,
     *  and store it into the wikiPages list. Then, return the list back to the caller.
     *  Tokenize and lemmatize the wiki pages with StanfordNLP core.
     */
    public static ArrayList<Wiki_Page> lemmatizeWikiPages (File[] wikiFiles)
    {
        long startTime = System.nanoTime();
        ArrayList<Wiki_Page> wikiPages = new ArrayList<>();

        String content = "";

        Properties props = new Properties();
        props.setProperty("annotators", "tokenize, ssplit, pos, lemma");
        StanfordCoreNLP pipeline = new StanfordCoreNLP(props);

        System.out.println("Begin tokenizing and lemmatizing wiki pages...");

        // This is simply to track how many files have been lemmatized during the process
        int fileNum = 1;

        // Traverse each wiki page file
        for (File file : wikiFiles)
        {
            System.out.println("Tokenizing File: " + (file.getName()).toString() + " ...");
            boolean newPage = true;
            try (Scanner scanner = new Scanner(file))
            {
                Wiki_Page wikiPage = null;
                while (scanner.hasNextLine())
                {
                    String line = scanner.nextLine();
                    Matcher m = pattern.matcher(line);
                    if (m.find())
                    {
                        if (!newPage)
                        {
                            Annotation cont = new Annotation(content);
                            pipeline.annotate(cont);
                            List<CoreMap> sentences = cont.get(SentencesAnnotation.class);
                            String lemmas = "";
                            for (CoreMap sentence : sentences)
                            {
                                for (CoreLabel token : sentence.get(TokensAnnotation.class))
                                {
                                    // Construct a new string of lemmas only based on the POS tags that I considers imortant
                                    String pos = token.get(PartOfSpeechAnnotation.class);
                                    if (posTags.contains(pos))
                                    {
                                        lemmas += (token.get(LemmaAnnotation.class) + " ");
                                    }
                                }
                            }
                            String cleanedLemma = lemmas.replaceAll("[^\\p{IsAlphabetic}^\\p{IsDigit}]", " ");
                            // Add the lemmatized content to the wikiPage.Content
                            wikiPage.Content = cleanedLemma;
                            // Add to the list of wiki pages
                            wikiPages.add(wikiPage);
                            content = "";
                            newPage = true;
                        }
                        wikiPage = new Wiki_Page();
                        wikiPage.Title = line;
                        newPage = false;
                        System.out.printf("Currently lemmatizing Page: %s from file: %s. File number: %d\n", line, (file.getName()).toString(), fileNum);
                    }
                    else if (line.contains("CATEGORIES"))
                    {
                        wikiPage.Category = line;
                    }
                    else
                    {
                        if (!line.isEmpty() && line != null)
                            content += line;
                    }
                }
                // REMOVE
                break;
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
            fileNum++;
        }
        long endTime = System.nanoTime();
        runningTime.lemmatizing = ((endTime - startTime)/1000000000);

        return wikiPages;
    }

    /*
     *  In order to avoid the long process of lemmatizing entire wiki pages again,
     *  this function will store all lemmatized pages into the file specified by the path.
     *  This file will be called and repopulated on memory for indexing.
     */
    private void addLemmatizedDocsToFile (ArrayList<Wiki_Page> wikiPages) throws java.io.IOException
    {
        String path = "/Users/hlim1/CourseWorks/583/CSC583/Project/watson/target/classes/LemmatizedWikiPages.txt";
        FileWriter write = new FileWriter(path, false);
        PrintWriter printWriter = new PrintWriter(write);

        for (int i = 0; i < wikiPages.size(); i++)
        {
            if ((wikiPages.get(i)).Category == null)
                (wikiPages.get(i)).Category = "CATEGORIES: NULL";
            printWriter.printf("__NEWPAGE__\n");
            printWriter.printf("TITLE: %s\n", (wikiPages.get(i)).Title);
            printWriter.printf("%s\n", (wikiPages.get(i)).Category);
            printWriter.printf("CONTENT: %s\n", (wikiPages.get(i)).Content);
        }
    }

    private static ArrayList<Wiki_Page> retrieveLammatizedWiki (File lemmatizedFile) throws java.io.IOException
    {
        long startTime = System.nanoTime();
        System.out.println ("Begin retreiving lemmatized pages from file ...");

        ArrayList<Wiki_Page> wikiPages = new ArrayList<>();
        Wiki_Page wikiPage = null;

        try (Scanner scanner = new Scanner(lemmatizedFile))
        {
            while (scanner.hasNextLine())
            {
                String line = scanner.nextLine();
                if (line.contains("__NEWPAGE__"))
                {
                    wikiPage = new Wiki_Page();
                }
                else if (line.contains("TITLE"))
                {
                    String removeTITLE = line.replace("TITLE:", "");
                    String onlyTitle = removeTITLE.replaceAll("\\[|\\]", "");
                    wikiPage.Title = onlyTitle.trim();
                }
                else if (line.contains("CATEGORIES"))
                {
                    wikiPage.Category = line;
                }
                else if (line.contains("CONTENT"))
                {
                    wikiPage.Content = line;
                    wikiPages.add(wikiPage);
                }
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        long endTime = System.nanoTime();
        runningTime.retrieving = ((endTime - startTime)/1000000000);

        return wikiPages;
    }

    /*
     *  By using the lemmatized wiki pages, index each page into individual documents.
     */
    private void IndexDocuments (ArrayList<Wiki_Page> wikiPages) throws java.io.IOException
    {
        long startTime = System.nanoTime();
        System.out.println("Begin indexing...");

        IndexWriter writer = createWriter();
        List<Document> documents = new ArrayList<>();

        int numPageRemain = wikiPages.size();
        for (int i = 0; i < wikiPages.size(); i++)
        {
            numPageRemain--;
            System.out.printf("Indexing document: %s. %d / %d remaining\n", (wikiPages.get(i)).Title, numPageRemain, wikiPages.size());

            // If either category or content field is null (or empty), set it to "NULL" string as adding null to document is an invalid operation.
            if ((wikiPages.get(i)).Category == null)
                (wikiPages.get(i)).Category = "NULL";
            if ((wikiPages.get(i)).Content == null)
                (wikiPages.get(i)).Content = "NULL";

            Document document = createDocument ((wikiPages.get(i)).Title, (wikiPages.get(i)).Category, (wikiPages.get(i)).Content);
            documents.add(document);
        }

        System.out.println("Indexing done. Adding documents. It may take a few minutes ...");

        writer.deleteAll();

        writer.addDocuments(documents);
        writer.commit();
        writer.close();

        long endTime = System.nanoTime();
        runningTime.indexing = ((endTime - startTime)/1000000000);
    }

    /*
     *  With passed parameters of title, categories, and content, create a new document
     *  for each wiki page.
     */
    private static Document createDocument (String title, String categories, String content)
    {
         Document document = new Document();

         document.add(new TextField("title", title, Field.Store.YES));
         document.add(new TextField("categories", categories, Field.Store.YES));
         document.add(new TextField("content", content, Field.Store.YES));

         return document;
    }

    /*
     *  Create a writer that will store indexed document to the disk under INDEX_DIR
     */
    private static IndexWriter createWriter() throws IOException
    {
        FSDirectory dir = FSDirectory.open(Paths.get(INDEX_DIR));
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        IndexWriter writer = new IndexWriter(dir, config);

        return writer;
    }

    /*
     *  Open and read in the indexed documents and return the searcher object back to the caller
     */
    private static IndexSearcher createSearcher() throws IOException
    {
        Directory indexDir = FSDirectory.open(Paths.get(INDEX_DIR));
        IndexReader reader = DirectoryReader.open(indexDir);
        IndexSearcher searcher = new IndexSearcher(reader);

        return searcher;
    }

    /*
     *  With 100 questions from the lemmatized quesions ArrayList, search the indexed Wiki. pages to find the correct document based on the top scoring hits.
     *  Default: BM25
     *  Modified: TFIDFSimilarity
     */
    private static int query (IndexSearcher searcher, ArrayList<Questions> questions, HashMap<String, Double> positions) throws java.io.IOException, ParseException
    {
        long startTime = System.nanoTime();
        System.out.println("Begin searching ...");
        int hitsPerPage = 10;
        int numberCorrectlyFound = 0;

        Query query = null;
        TopDocs docs = null;

        for (int i = 0; i < questions.size(); i++)
        {
            String category = (questions.get(i)).Category;
            String clue     = (questions.get(i)).Clue;
            String answer   = (questions.get(i)).Answer;

            // Search through the indexed wiki page with the provided clue to find the correct answer
            query = new QueryParser("content", new StandardAnalyzer()).parse(QueryParser.escape(clue));
            docs = searcher.search(query, hitsPerPage);
            double position = 0;
            for (ScoreDoc sd : docs.scoreDocs)
            {
                position += 1.0;
                Document document = searcher.doc(sd.doc);
                // If the found document's title is equal to the current question's answer, it is a correct match.
                if ((((document.get("title")).toString()).trim()).equals(answer.trim()))
                {
                    // Thus, increment the found number and print the document's title.
                    numberCorrectlyFound++;
                    System.out.println("Question: " + clue);
                    System.out.println("Expected Answer: " + answer);
                    System.out.println("Found Answer: " + document.get("title"));
                    System.out.printf("\n");
                    positions.put(answer.trim(), position);
                }
            }
        }

        System.out.println("Searching ended ...");

        long endTime = System.nanoTime();
        runningTime.searching = (endTime - startTime);

        return numberCorrectlyFound;
    }

    /*
     *  Tokenize and Lemmatize search queries
     */
    private static ArrayList<Questions> LemmatizeQuestions(File questionsFile)
    {
        Questions question = null;
        ArrayList<Questions> questions = new ArrayList<>();

        Properties props = new Properties();
        props.setProperty("annotators", "tokenize, ssplit, pos, lemma");
        StanfordCoreNLP pipeline = new StanfordCoreNLP(props);

        try (Scanner scanner = new Scanner(questionsFile))
        {
            int currentLine = 0;
            while (scanner.hasNextLine())
            {
                String line = scanner.nextLine();
                switch (currentLine)
                {
                    case CATEGORY:
                        question = new Questions();
                        question.Category = line;
                        currentLine++;
                        break;
                    case CLUE:
                        Annotation clue = new Annotation(line);
                        pipeline.annotate(clue);
                        List<CoreMap> sentences = clue.get(SentencesAnnotation.class);
                        String lemmas = "";
                        for (CoreMap sentence : sentences)
                        {
                            for (CoreLabel token : sentence.get(TokensAnnotation.class))
                            {
                                lemmas += (token.get(LemmaAnnotation.class) + " ");
                            }
                        }
                        question.Clue = lemmas;
                        currentLine++;
                        break;
                    case ANSWER:
                        question.Answer = line;
                        currentLine++;
                        break;
                    case WHITESPACE:
                        questions.add(question);
                        currentLine = 0;
                        break;
                }
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        return questions;
    }

    /*
     *  Calculate the MRR based on the rankings and positions of each answer within the relevant documents
     */
    private static double MRR (HashMap<String, Double> positions)
    {
        double mrr = 0.0;
        double sumOfReciprocalRank = 0.0;
        Iterator it = positions.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry pair = (Map.Entry)it.next();
            sumOfReciprocalRank += (1.0/(double)pair.getValue());
        }
        mrr = sumOfReciprocalRank/positions.size();
        return mrr;
    }
}
