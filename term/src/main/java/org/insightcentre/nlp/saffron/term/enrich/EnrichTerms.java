package org.insightcentre.nlp.saffron.term.enrich;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import opennlp.tools.lemmatizer.DictionaryLemmatizer;
import opennlp.tools.lemmatizer.Lemmatizer;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTagger;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.tokenize.SimpleTokenizer;
import opennlp.tools.tokenize.Tokenizer;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.Span;
import org.insightcentre.nlp.saffron.config.Configuration;
import org.insightcentre.nlp.saffron.config.TermExtractionConfiguration;
import org.insightcentre.nlp.saffron.data.Corpus;
import org.insightcentre.nlp.saffron.data.Document;
import org.insightcentre.nlp.saffron.data.Status;
import org.insightcentre.nlp.saffron.data.Term;
import org.insightcentre.nlp.saffron.data.connections.DocumentTerm;
import org.insightcentre.nlp.saffron.documentindex.CorpusTools;
import org.insightcentre.nlp.saffron.term.FrequencyStats;

/**
 * This is used to create a Doc-Terms file from a taxonomy, such as those used
 * in TExEval
 *
 * @author John McCrae &lt;john@mccr.ae&gt;
 */
public class EnrichTerms {

    /**
     * Count how many times t occurs in s
     *
     * @param s
     * @param t
     * @return
     */
    private static int count(String[] s, String[] t) {
        int n = 0;
        OUTER:
        for (int i = 0; i < s.length; i++) {
            if (s[i].equals(t[0])) {
                for (int j = 1; j < t.length && i + j < s.length; j++) {
                    if (!s[i + j].equals(t[j])) {
                        continue OUTER;
                    }
                }
                n++;
            }
        }
        return n;
    }

    public static Result enrich(Set<String> termStrings, Corpus corpus, TermExtractionConfiguration config) {
        final ThreadLocal<Tokenizer> tokenizer;
        final ThreadLocal<POSTagger> tagger;
        final ThreadLocal<Lemmatizer> lemmatizer;
        final int nThreads;
        if (config == null) {
            tokenizer = new ThreadLocal<Tokenizer>() {
                @Override
                protected Tokenizer initialValue() {
                    return new Tokenizer() {
                        @Override
                        public String[] tokenize(String s) {
                            return s.split("\\s+");
                        }

                        @Override
                        public Span[] tokenizePos(String s) {
                            throw new UnsupportedOperationException("Not supported");
                        }
                    };
                }

            };
            tagger = null;
            lemmatizer = null;
            nThreads = 10;
        } else {
            final POSModel posModel;
            try {
                posModel = new POSModel(config.posModel.toFile());
            } catch (IOException x) {
                throw new RuntimeException(x);
            }

            tagger = new ThreadLocal<POSTagger>() {
                @Override
                protected POSTagger initialValue() {
                    return new POSTaggerME(posModel);
                }
            };
            final TokenizerModel tokenizerModel;
            try {
                tokenizerModel = new TokenizerModel(config.tokenizerModel.toFile());
            } catch (IOException x) {
                throw new RuntimeException(x);
            }
            tokenizer = new ThreadLocal<Tokenizer>() {
                @Override
                protected Tokenizer initialValue() {

                    if (config.tokenizerModel == null) {
                        return SimpleTokenizer.INSTANCE;
                    } else {
                        return new TokenizerME(tokenizerModel);
                    }
                }
            };
            if (config.lemmatizerModel == null) {
                lemmatizer = null;
            } else {
                final Lemmatizer dictLemmatizer;
                try {
                    dictLemmatizer = new DictionaryLemmatizer(config.lemmatizerModel.toFile());
                } catch (IOException x) {
                    throw new RuntimeException(x);
                }
                lemmatizer = new ThreadLocal<Lemmatizer>() {
                    @Override
                    protected Lemmatizer initialValue() {
                        return dictLemmatizer;
                    }
                };
            }
            nThreads = config.numThreads <= 0 ? 10 : config.numThreads;
        }
        return enrich(termStrings, corpus, nThreads, tagger, lemmatizer, tokenizer);
    }

    public static Result enrich(Set<String> termStrings, Corpus corpus, int nThreads,
            ThreadLocal<POSTagger> tagger, ThreadLocal<Lemmatizer> lemmatizer, ThreadLocal<Tokenizer> tokenizer) {
        try {
            ExecutorService service = new ThreadPoolExecutor(nThreads, nThreads, 0,
                    TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(1000),
                    new ThreadPoolExecutor.CallerRunsPolicy());

            final FrequencyStats summary = new FrequencyStats();
            final ConcurrentLinkedQueue<DocumentTerm> dts = new ConcurrentLinkedQueue<>();

            for (Document d : corpus.getDocuments()) {
                service.submit(new EnrichTermTask(d, tagger, lemmatizer, tokenizer, summary, makeTrie(termStrings, tokenizer), dts));
            }

            service.shutdown();
            service.awaitTermination(2, TimeUnit.DAYS);
            List<DocumentTerm> docTerms = new ArrayList<>(dts);
            List<Term> terms = new ArrayList<>();
            for (String term : termStrings) {
                terms.add(new Term(term, summary.termFrequency.getInt(term), summary.docFrequency.getInt(term),
                        (double) summary.docFrequency.getInt(term) / corpus.size(),
                        Collections.EMPTY_LIST, Status.none.toString()));
            }

            TFIDF.addTfidf(docTerms);

            return new Result(docTerms, terms);
        } catch (InterruptedException x) {
            throw new RuntimeException(x);
        }
    }

    public static class Result {

        public final List<DocumentTerm> docTerms;
        public final List<Term> terms;

        public Result(List<DocumentTerm> docTerms, List<Term> terms) {
            this.docTerms = docTerms;
            this.terms = terms;
        }

    }

    private static void badOptions(OptionParser p, String message) throws IOException {
        System.err.println("Error: " + message);
        p.printHelpOn(System.err);
        System.exit(-1);
    }

    public static void main(String[] args) {
        try {
            final ObjectMapper mapper = new ObjectMapper();
            // Parse command line arguments
            final OptionParser p = new OptionParser() {
                {
                    accepts("t", "The taxonomy to enrich (in TExEval format)").withRequiredArg().ofType(File.class);
                    accepts("c", "The corpus to load").withRequiredArg().ofType(File.class);
                    accepts("o", "The output term list").withRequiredArg().ofType(File.class);
                    accepts("d", "The output doc-term list").withRequiredArg().ofType(File.class);
                    accepts("cfg", "The configuration (Used of tokenization, tagging and lemmatization of the corpus)").withRequiredArg().ofType(File.class);
                }
            };
            final OptionSet os;

            try {
                os = p.parse(args);
            } catch (Exception x) {
                badOptions(p, x.getMessage());
                return;
            }

            File termOutFile = (File) os.valueOf("o");
            if (termOutFile == null) {
                badOptions(p, "Output file not given");
            }

            File docTermOutFile = (File) os.valueOf("d");
            if (docTermOutFile == null) {
                badOptions(p, "Output file not given");
            }

            File taxoFile = (File) os.valueOf("t");
            if (taxoFile == null || !taxoFile.exists()) {
                badOptions(p, "The taxonomy does not exist");
            }

            File corpusFile = (File) os.valueOf("c");
            if (corpusFile == null || !corpusFile.exists()) {
                badOptions(p, "The corpus file does not exist");
            }
            File cfgFile = (File) os.valueOf("cfg");

            final TermExtractionConfiguration config;

            if (cfgFile == null) {
                config = null;
            } else if (!cfgFile.exists()) {
                badOptions(p, "The config file does not exist");
                return;
            } else {
                config = mapper.readValue(cfgFile, Configuration.class).termExtraction;
            }

            final Corpus corpus = CorpusTools.readFile(corpusFile);

            final Set<String> terms = readTExEval(taxoFile);

            Result res = enrich(terms, corpus, config);

            mapper.writerWithDefaultPrettyPrinter().writeValue(termOutFile, res.terms);
            mapper.writerWithDefaultPrettyPrinter().writeValue(docTermOutFile, res.docTerms);
        } catch (Exception x) {
            x.printStackTrace();
            return;
        }
    }

    private static Set<String> readTExEval(File goldFile) throws IOException {
        HashSet<String> links = new HashSet<>();
        String line;
        try (BufferedReader reader = new BufferedReader(new FileReader(goldFile))) {
            while ((line = reader.readLine()) != null) {
                if (!line.equals("")) {
                    String[] elems = line.split("\t");
                    if (elems.length != 2) {
                        throw new IOException("Bad Line: " + line);
                    }
                    links.add(elems[0].toLowerCase());
                    links.add(elems[1].toLowerCase());
                }
            }
        }
        return links;
    }

    private static class TFIDF {

        /**
         * Add the TF-IDF value to an existing set of unique document term
         * connections
         *
         * @param docTerms The list of values to add TF-IDF scores to
         */
        public static void addTfidf(List<DocumentTerm> docTerms) {
            Object2DoubleMap<String> termDf = new Object2DoubleOpenHashMap<>();
            HashSet<String> docNames = new HashSet<>();
            for (DocumentTerm dt : docTerms) {
                // We assume there are no duplicates in the DT list
                termDf.put(dt.getTermString(), termDf.getDouble(dt.getTermString()) + 1.0);
                docNames.add(dt.getDocumentId());
            }
            double n = docNames.size();
            for (DocumentTerm dt : docTerms) {
                dt.setTfIdf((double) dt.getOccurrences() * Math.log(n / termDf.getDouble(dt.getTermString())));
            }
        }
    }

    private static WordTrie makeTrie(Set<String> termStrings, ThreadLocal<Tokenizer> _tokenizer) {
        Tokenizer tokenizer = _tokenizer.get();
        WordTrie trie = new WordTrie("");
        for (String termString : termStrings) {
            trie.addTokenized(tokenizer.tokenize(termString.toLowerCase()));
        }
        return trie;
    }

    public static class WordTrie extends AbstractMap<String, WordTrie> {

        final Map<String, WordTrie> trie;
        final String word;
        boolean present = false;

        public WordTrie(String word) {
            trie = new HashMap<>();
            this.word = word;
        }

        public String getWord() {
			return word;
		}

		public boolean isPresent() {
        	return this.present;
        }

        public boolean isPrefix() {
        	return trie != null && trie.size() > 0;
        }

        @Override
        public boolean containsKey(Object key) {
            return trie.containsKey(key);
        }

        @Override
        public WordTrie get(Object key) {
            return trie.get(key); //To change body of generated methods, choose Tools | Templates.
        }

        public void addTokenized(String[] words) {
            _add(words, 0);
        }

        private void _add(String[] words, int i) {
            if (i >= words.length) {
                present = true;
                return;
            }

            if (!trie.containsKey(words[i])) {
                trie.put(words[i], new WordTrie(join(words, i)));
            }
            trie.get(words[i])._add(words, i + 1);
        }

        private String join(String[] words, int i) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j <= i; j++) {
                if (j != 0) {
                    sb.append(" ");
                }
                sb.append(words[j]);
            }
            return sb.toString();
        }

        @Override
        public Set<Entry<String, WordTrie>> entrySet() {
            return trie.entrySet();
        }
    }
}
