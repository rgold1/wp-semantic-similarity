#!/bin/bash

if [ $# -lt 1 ]; then
    echo "usage: $0 memory_in_mb args...">&2
    exit 1
fi

mb=$1
shift
args=(${@// /\\ })

export MAVEN_OPTS="-Xmx${mb}M -ea -server"
mvn compile &&
mvn exec:java -D exec.mainClass="edu.macalester.wpsemsim.sim.utils.SimilarityAnalyzer" -D exec.classpathScope=runtime  -D exec.args="${args[*]}"
