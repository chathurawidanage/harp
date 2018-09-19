#!/bin/bash

#
# test script for KMeans, using random generated dataset
#

startdir=$(dirname $0)
harproot=$(readlink -m $startdir/../../)
bin=$harproot/contrib/target/contrib-0.1.0.jar
hdfsroot=/harp-test
hdfsoutput=$hdfsroot/km/

if [ ! -f $bin ] ; then
    echo "harp contrib app not found at "$bin
    exit -1
fi
if [ -z ${HADOOP_HOME+x} ];then
    echo "HADOOP not setup"
    exit
fi

# check whether safemode is open
hdfs dfsadmin -safemode get | grep -q "ON"
if [[ "$?" = "0" ]]; then
    hdfs dfsadmin -safemode leave
fi

#workdir
workdir=test_km
mkdir -p $workdir
cd $workdir

#
# runtest 
#
runtest()
{
       # <numOfDataPoints>: the number of data points you want to generate randomly
       # <num of centriods>: the number of centroids you want to clustering the data to
       # <size of vector>: the number of dimension of the data
       # <number of map tasks>: number of map tasks
       # <number of threads>: number of threads per task 
       # <number of iteration>: the number of iterations to run
       # <work dir>: the root directory for this running in HDFS
       # <local dir>: the harp kmeans will firstly generate files which contain data points to local directory. Set this argument to determine the local directory.
       # <communication operation> includes:
       # 	[allreduce]: use allreduce operation to synchronize centroids
       # 	[regroup-allgather]: use regroup and allgather operation to synchronize centroids 
       # 	[broadcast-reduce]: use broadcast and reduce operation to synchronize centroids
       # 	[push-pull]: use push and pull operation to synchronize centroids
       # <mem per mapper>
    hadoop jar $bin edu.iu.kmeans.common.KmeansMapCollective $1 $2 $3 $4 $5 $6 $hdfsoutput/$7 /tmp/kmeans $7 $8
    
    if [ $? -ne 0 ]; then
        echo "run km failure"
        exit -1
    fi
    
    ## retrieve the results from HDFS
    if [ -d ./$7 ];then
	 rm -rf ./$7/*
    else
	 mkdir -p ./$7
    fi

    hdfs dfs -get $hdfsoutput/$7/evaluation ./$7
    hdfs dfs -get $hdfsoutput/$7/centroids ./$7
}

#run test
runtest 100000 10 100 1 4 100 allreduce 110000