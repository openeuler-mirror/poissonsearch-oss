CLASSPATH=$CLASSPATH:$ES_HOME/lib/*:$ES_HOME/lib/sigar/*

if [ "x$ES_MIN_MEM" = "x" ]; then
    ES_MIN_MEM=256
fi
if [ "x$ES_MAX_MEM" = "x" ]; then
    ES_MAX_MEM=1024
fi

# Arguments to pass to the JVM
JAVA_OPTS=" \
        -Xms${ES_MIN_MEM}m \
        -Xmx${ES_MAX_MEM}m \
        -Djline.enabled=true \
        -XX:+AggressiveOpts \
        -XX:+UseParNewGC \
        -XX:+UseConcMarkSweepGC \
        -XX:+CMSParallelRemarkEnabled \
        -XX:+HeapDumpOnOutOfMemoryError"
