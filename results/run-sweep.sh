#!/bin/bash
JMX=src/test/resources/jmeter/rate-limiter-bench.jmx

for threads in 10 20 50 100 200; do
  echo "=== threads=$threads ==="
  jmeter -n -t $JMX -Jthreads=$threads -l results/results-${threads}.jtl -e -o results/report-${threads} -f 2>&1 | grep "^summary ="
done
