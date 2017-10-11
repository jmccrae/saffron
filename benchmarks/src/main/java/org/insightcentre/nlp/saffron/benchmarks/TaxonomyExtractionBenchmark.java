package org.insightcentre.nlp.saffron.benchmarks;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.insightcentre.nlp.saffron.data.Taxonomy;

/**
 * Benchmark system for comparing two extracted taxonomies
 *
 * @author John McCrae <john@mccr.ae>
 */
public class TaxonomyExtractionBenchmark {

    private static int matches(Taxonomy taxo, Set<StringPair> gold) {
        int m = 0;
        for(Taxonomy child : taxo.children) {
            if(gold.contains(new StringPair(taxo.root.toLowerCase(), child.root.toLowerCase()))) 
                m++;
            m += matches(child, gold);
        }
        return m;
    }

    private static double size(Taxonomy taxo) {
        int m = 1;
        for(Taxonomy child : taxo.children) {
            m += size(child);
        }
        return m;
    }
    private static class Scores {
        public double precision;
        public double recall;

        public Scores(double precision, double recall) {
            this.precision = precision;
            this.recall = recall;
        }
    }
    
    private static Scores evalTaxo(Taxonomy extracted, Set<StringPair> gold) {
        final int matches = matches(extracted, gold);
        return new Scores(
                (double)matches / size(extracted),
                (double)matches / gold.size());
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
                    accepts("o", "The output taxonomy").withRequiredArg().ofType(File.class);
                    accepts("g", "The gold taxonomy").withRequiredArg().ofType(File.class);
                }
            };
            final OptionSet os;

            try {
                os = p.parse(args);
            } catch (Exception x) {
                badOptions(p, x.getMessage());
                return;
            }
            
            final File taxoFile = (File)os.valueOf("o");
            if(taxoFile == null || !taxoFile.exists()) {
                badOptions(p, "Output taxonomy not specified or does not exist");
                return;
            }
            
            final File goldFile = (File)os.valueOf("g");
            if(goldFile == null || !goldFile.exists()) {
                badOptions(p, "Gold taxonomy not specified or does not exist");
                return;
            }
            
            final Taxonomy taxo = mapper.readValue(taxoFile, Taxonomy.class);
            
            final Set<StringPair> gold;
            if(goldFile.getName().endsWith(".json")) {
                gold = linksFromTaxo(mapper.readValue(taxoFile, Taxonomy.class));
            } else {
                gold = readTExEval(goldFile);
            }
            
            final Scores s = evalTaxo(taxo, gold);
            
            System.err.printf("|-----------|--------|\n");
            System.err.printf("| Precision | %.4f |\n", s.precision);
            System.err.printf("| Recall    | %.4f |\n", s.recall);
            System.err.printf("| F-Measure | %.4f |\n", 
                    s.precision == 0.0 && s.recall == 0.0 ? 0.0 :
                    2.0 * s.recall * s.precision / (s.precision + s.recall));
            System.err.printf("| F&M       | %.4f |\n", Math.sqrt(s.recall * s.precision));
        } catch (Exception x) {
            x.printStackTrace();
            System.exit(-1);
        }

    }
    
    private static Set<StringPair> linksFromTaxo(Taxonomy taxo) {
        HashSet<StringPair> links = new HashSet<>();
        _linksFromTaxo(taxo, links);
        return links;        
    }

    private static Set<StringPair> readTExEval(File goldFile) throws IOException {
        HashSet<StringPair> links = new HashSet<>();
        String line;
        try(BufferedReader reader = new BufferedReader(new FileReader(goldFile))) {
            while((line = reader.readLine()) != null) {
                if(!line.equals("")) {
                    String[] elems = line.split("\t");
                    if(elems.length != 2) {
                        throw new IOException("Bad Line: " + line);
                    }
                    links.add(new StringPair(elems[0].toLowerCase(), elems[1].toLowerCase()));
                }
            }
        }
        return links;
    }

    private static void _linksFromTaxo(Taxonomy taxo, HashSet<StringPair> links) {
        for(Taxonomy child : taxo.children) {
            links.add(new StringPair(taxo.root.toLowerCase(), child.root.toLowerCase()));
            _linksFromTaxo(child, links);
        }
    }
    
    private static class StringPair {
        public final String _1, _2;

        public StringPair(String _1, String _2) {
            this._1 = _1;
            this._2 = _2;
        }

        @Override
        public int hashCode() {
            int hash = 5;
            hash = 53 * hash + Objects.hashCode(this._1);
            hash = 53 * hash + Objects.hashCode(this._2);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final StringPair other = (StringPair) obj;
            if (!Objects.equals(this._1, other._1)) {
                return false;
            }
            if (!Objects.equals(this._2, other._2)) {
                return false;
            }
            return true;
        }
        
        
    }
}