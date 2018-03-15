#!/bin/bash

## root path of harp  
cd ../../../
export HARP_ROOT=$(pwd)
cd ${HARP_ROOT}

if [ -z ${HADOOP_HOME+x} ];then
    echo "HADOOP not setup"
    exit
fi

cp ${HARP_ROOT}/ml/daal/target/harp-daal-0.1.0.jar ${HADOOP_HOME}
cd ${HADOOP_HOME}

hdfs dfsadmin -safemode get | grep -q "ON"
if [[ "$?" = "0" ]]; then
    hdfs dfsadmin -safemode leave
fi

## copy required third_party native libs to HDFS
hdfs dfs -mkdir -p /Hadoop
hdfs dfs -mkdir -p /Hadoop/Libraries
hdfs dfs -rm /Hadoop/Libraries/*
hdfs dfs -put ${HARP_ROOT}/third_party/daal-2018/lib/intel64_lin/libJavaAPI.so /Hadoop/Libraries/
hdfs dfs -put ${HARP_ROOT}/third_party/tbb/lib/intel64_lin/gcc4.4/libtbb* /Hadoop/Libraries/

export LIBJARS=${HARP_ROOT}/third_party/daal-2018/lib/daal.jar

## load training and test data
datadir=${HARP_ROOT}/datasets/daal_nn

hdfs dfs -mkdir -p /Hadoop/nn-input
hdfs dfs -rm -r /Hadoop/nn-input/*
hdfs dfs -put ${datadir}/* /Hadoop/nn-input/ 

## log directory
mkdir -p ${HADOOP_HOME}/Harp-DAAL-NN
logDir=${HADOOP_HOME}/Harp-DAAL-NN

Mem=110000
Batch=50
# num of mappers (nodes)
Node=2
# num of threads on each mapper(node)
Thd=16

echo "Test-daal-nn-$Dataset-N$Node-T$Thd-B$Batch Start" 
hadoop jar harp-daal-0.1.0.jar edu.iu.daal_nn.NNDaalLauncher -libjars ${LIBJARS}  /Hadoop/nn-input/train /Hadoop/nn-input/test /Hadoop/nn-input/groundTruth /nn/work $Mem $Batch $Node $Thd 2>$logDir/Test-daal-nn-$Dataset-N$Node-T$Thd-B$Batch.log
echo "Test-daal-nn-$Dataset-N$Node-T$Thd-B$Batch End" 