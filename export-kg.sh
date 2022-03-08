#!/bin/bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

mvn -q exec:java -f $DIR/taxonomy/pom.xml -Dexec.mainClass="org.insightcentre.nlp.saffron.taxonomy.extract.ConvertKGToRDF" -Dexec.args="$*"
