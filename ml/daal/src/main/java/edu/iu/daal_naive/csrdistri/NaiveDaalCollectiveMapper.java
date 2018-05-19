/*
 * Copyright 2013-2016 Indiana University
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.iu.daal_naive.csrdistri;

import org.apache.commons.io.IOUtils;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;
import java.util.Arrays;
import java.util.ListIterator;
import java.nio.DoubleBuffer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.mapred.CollectiveMapper;

import edu.iu.harp.example.DoubleArrPlus;
import edu.iu.harp.partition.Partition;
import edu.iu.harp.partition.Partitioner;
import edu.iu.harp.partition.Table;
import edu.iu.harp.resource.DoubleArray;
import edu.iu.harp.resource.ByteArray;
import edu.iu.harp.schdynamic.DynamicScheduler;
import edu.iu.datasource.*;
import edu.iu.data_aux.*;

import java.nio.DoubleBuffer;

//import daal.jar API
import com.intel.daal.algorithms.classifier.prediction.ModelInputId;
import com.intel.daal.algorithms.classifier.prediction.NumericTableInputId;
import com.intel.daal.algorithms.classifier.prediction.PredictionResult;
import com.intel.daal.algorithms.classifier.prediction.PredictionResultId;
import com.intel.daal.algorithms.classifier.training.InputId;
import com.intel.daal.algorithms.classifier.training.TrainingResultId;
import com.intel.daal.algorithms.multinomial_naive_bayes.Model;
import com.intel.daal.algorithms.multinomial_naive_bayes.prediction.*;
import com.intel.daal.algorithms.multinomial_naive_bayes.training.*;
import com.intel.daal.data_management.data.*;
import com.intel.daal.services.DaalContext;
import com.intel.daal.data_management.data_source.*;

import com.intel.daal.services.Environment;


/**
 * @brief the Harp mapper for running Naive Bayes
 */


public class NaiveDaalCollectiveMapper
extends
CollectiveMapper<String, String, Object, Object>{

  private long nClasses = 20;
  private int numMappers;
  private int numThreads; //used in computation
  private int harpThreads; //used in data conversion
  private TrainingResult trainingResult;
  private PredictionResult predictionResult;
  private String testFilePath;
  private String testGroundTruth;

  //to measure the time
  private long load_time = 0;
  private long convert_time = 0;
  private long total_time = 0;
  private long compute_time = 0;
  private long comm_time = 0;
  private long ts_start = 0;
  private long ts_end = 0;
  private long ts1 = 0;
  private long ts2 = 0;

  private static HarpDAALDataSource datasource;
  private static DaalContext daal_Context = new DaalContext();
    /**
   * Mapper configuration.
   */
    @Override
    protected void setup(Context context)
    throws IOException, InterruptedException {
      long startTime = System.currentTimeMillis();
      Configuration configuration =
      context.getConfiguration();

      numMappers = configuration
      .getInt(HarpDAALConstants.NUM_MAPPERS, 10);
      numThreads = configuration
      .getInt(HarpDAALConstants.NUM_THREADS, 10);
      nClasses = configuration
      .getInt(HarpDAALConstants.NUM_CLASS, 20);

      testFilePath = configuration.get(HarpDAALConstants.TEST_FILE_PATH,"");
      testGroundTruth =configuration.get(HarpDAALConstants.TEST_TRUTH_PATH,"");

      //always use the maximum hardware threads to load in data and convert data 
      harpThreads = Runtime.getRuntime().availableProcessors();

      LOG.info("Num Mappers " + numMappers);
      LOG.info("Num Threads " + numThreads);
      LOG.info("Num classes " + nClasses);
      LOG.info("Num harp load data threads " + harpThreads);

      long endTime = System.currentTimeMillis();
      LOG.info(
        "config (ms) :" + (endTime - startTime));
      System.out.println("Collective Mapper launched");

    }

    protected void mapCollective(
      KeyValReader reader, Context context)
    throws IOException, InterruptedException {

      // long startTime = System.currentTimeMillis();
      List<String> trainingDataFiles =
      new LinkedList<String>();

	  //splitting files between mapper
      while (reader.nextKeyValue()) {
        String key = reader.getCurrentKey();
        String value = reader.getCurrentValue();
        LOG.info("Key: " + key + ", Value: "
          + value);
        System.out.println("file name : " + value);
        trainingDataFiles.add(value);
      }

      Configuration conf = context.getConfiguration();

      Path pointFilePath = new Path(trainingDataFiles.get(0));
      System.out.println("path = "+ pointFilePath.getName());
      FileSystem fs = pointFilePath.getFileSystem(conf);
      FSDataInputStream in = fs.open(pointFilePath);

      //init data source
      this.datasource = new HarpDAALDataSource(trainingDataFiles, harpThreads, conf);

      runNaive(conf, context);
      // LOG.info("Total time of iterations in master view: "
      //   + (System.currentTimeMillis() - startTime));
      this.freeMemory();
      this.freeConn();
      System.gc();
    }

  private void runNaive(Configuration conf, Context context) throws IOException {
         
    // load training data and training labels 
    //read in csr files with filenames in trainingDataFiles
    NumericTable featureArray_daal = this.datasource.loadCSRNumericTableAndLabel(daal_Context);
    NumericTable labelArray_daal = this.datasource.loadCSRLabel(daal_Context);  

    Table<ByteArray> partialResultTable = new Table<>(0, new ByteArrPlus());
    trainModel(featureArray_daal, labelArray_daal, partialResultTable);

    //load test set and labels
    NumericTable testData = this.datasource.loadExternalCSRNumericTable(testFilePath, daal_Context);

    if(this.isMaster()){
      testModel(testData, conf);
      printResults(testGroundTruth, predictionResult, conf);
    }
    
    this.barrier("naive", "testmodel-sync");

    daal_Context.dispose();

    ts_end = System.currentTimeMillis();
    total_time = (ts_end - ts_start);
    
    LOG.info("Loading Data Time of Naive: "+ load_time);
    LOG.info("Total Execution Time of Naive: "+ total_time);
    LOG.info("Computation Time of Naive: "+ compute_time);
    LOG.info("Comm Time of Naive: "+ comm_time);
    LOG.info("DataType Convert Time of Naive: "+ convert_time);
    LOG.info("Misc Time of Naive: "+ (total_time - compute_time - comm_time - convert_time));
}
  
  private void trainModel(NumericTable featureArray_daal, NumericTable labelArray_daal, Table<ByteArray> partialResultTable) throws java.io.IOException 
  {

    DaalContext localContext = new DaalContext();

    LOG.info("The default value of thread numbers in DAAL: " + Environment.getNumberOfThreads());
    Environment.setNumberOfThreads(numThreads);
    LOG.info("The current value of thread numbers in DAAL: " + Environment.getNumberOfThreads());

    ts1 = System.currentTimeMillis();
    TrainingDistributedStep1Local algorithm = new TrainingDistributedStep1Local(localContext, Double.class,
                    TrainingMethod.fastCSR, nClasses);

    algorithm.input.set(InputId.data, featureArray_daal);
    algorithm.input.set(InputId.labels, labelArray_daal);

    TrainingPartialResult pres = algorithm.compute();
    pres.changeContext(daal_Context);
    localContext.dispose();
    ts2 = System.currentTimeMillis();
    compute_time += (ts2 - ts1);

    ts1 = System.currentTimeMillis();

    partialResultTable.addPartition(new Partition<>(this.getSelfID(), serializePartialResult(pres)));
    boolean reduceStatus = false;
    // reduceStatus = this.reduce("naive", "sync-partialresult", partialResultTable, this.getMasterID());
    reduceStatus = this.reduce("naive", "sync-partialresult", partialResultTable, this.getMasterID());
	this.barrier("naive", "reduce-sync");
    ts2 = System.currentTimeMillis();
    comm_time += (ts2 - ts1); 

    if(!reduceStatus){
      System.out.println("reduce not successful");
    }
    else{
      System.out.println("reduce successful");
    }
      
    if(this.isMaster()){
	  ts1 = System.currentTimeMillis();
      TrainingDistributedStep2Master masterAlgorithm = new TrainingDistributedStep2Master(daal_Context, Double.class,
                TrainingMethod.fastCSR, nClasses);
	  ts2 = System.currentTimeMillis();
	  compute_time += (ts2 - ts1);

      ts1 = System.currentTimeMillis();
      int[] pid = partialResultTable.getPartitionIDs().toIntArray();
      for(int j = 0; j< pid.length; j++){
		if (pid[j] < 0)
			continue;

        try {
          System.out.println("pid : "+pid[j]);
          masterAlgorithm.input.add(TrainingDistributedInputId.partialModels,
          deserializePartialResult(partialResultTable.getPartition(pid[j]).get())); 
        } catch (Exception e) 
          {  
            System.out.println("Fail to deserilize partialResultTable" + e.toString());
            e.printStackTrace();
          }

      }

      ts2 = System.currentTimeMillis();
      comm_time += (ts2 - ts1);

      ts1 = System.currentTimeMillis();
      masterAlgorithm.compute();
      trainingResult = masterAlgorithm.finalizeCompute();
      ts2 = System.currentTimeMillis();
      compute_time += (ts2 - ts1);
    }

	this.barrier("naive", "master-compute-sync");

  }

  private void testModel(NumericTable testData, Configuration conf) throws java.io.FileNotFoundException, java.io.IOException {

    PredictionBatch algorithm = new PredictionBatch(daal_Context, Double.class, PredictionMethod.fastCSR, nClasses);

    algorithm.input.set(NumericTableInputId.data, testData);
    Model model = trainingResult.get(TrainingResultId.model);
    algorithm.input.set(ModelInputId.model, model);

    /* Compute the prediction results */
    ts1 = System.currentTimeMillis();
    predictionResult = algorithm.compute();
    ts2 = System.currentTimeMillis();
    compute_time += (ts2 - ts1);

  }

  private void printResults(String testGroundTruth, PredictionResult predictionResult, Configuration conf) throws java.io.FileNotFoundException, java.io.IOException {

	  this.datasource.loadTestFile(testGroundTruth, 1);
	  NumericTable expected = new HomogenNumericTable(daal_Context, Double.class, 1, this.datasource.getTestRows(), NumericTable.AllocationFlag.DoAllocate);

	  this.datasource.loadTestTable(expected);

	  NumericTable prediction = predictionResult.get(PredictionResultId.prediction);

        Service.printClassificationResult(expected, prediction, "Ground truth", "Classification results",
                "NaiveBayes classification results (first 20 observations):", 20);
    }

private NumericTable getNumericTableHDFS(DaalContext daal_Context, Configuration conf, String inputFiles, int vectorSize, int numRows) 
        throws IOException{

            Path inputFilePaths = new Path(inputFiles);
            List<String> inputFileList = new LinkedList<>();

            try {
                FileSystem fs =
                inputFilePaths.getFileSystem(conf);
                RemoteIterator<LocatedFileStatus> iterator =
                fs.listFiles(inputFilePaths, true);

                while (iterator.hasNext()) {
                    String name =
                    iterator.next().getPath().toUri()
                    .toString();
                    inputFileList.add(name);
                }

            } catch (IOException e) {
                LOG.error("Fail to get test files", e);
            }
            int dataSize = vectorSize*numRows;
            double[] data = new double[dataSize];
            long[] dims = {numRows, vectorSize};
            int index = 0;

            FSDataInputStream in = null;

            //loop over all the files in the list
            ListIterator<String> file_itr = inputFileList.listIterator();
            while (file_itr.hasNext())
            {
                String file_name = file_itr.next();
                LOG.info("read in file name: " + file_name);

                Path file_path = new Path(file_name);
                try {

                    FileSystem fs =
                    file_path.getFileSystem(conf);
                    in = fs.open(file_path);

                } catch (Exception e) {
                    LOG.error("Fail to open file "+ e.toString());
                    return null;
                }

                //read file content
                while(true)
                {
                    String line = in.readLine();
                    if (line == null) break;

                    String[] lineData = line.split(",");

                    for(int t =0 ; t< vectorSize; t++)
                    {
                        if (index < dataSize)
                        {
                            data[index] = Double.parseDouble(lineData[t]);
                            index++;                                                          
                        }
                        else
                        {
                            LOG.error("Incorrect size of file: dataSize: " + dataSize + "; index val: " + index);
                            return null;
                        }
                        
                    }
                }

                in.close();

            }

            if ( index  != dataSize )
            {
                LOG.error("Incorrect total size of file: dataSize: " + dataSize + "; index val: " + index);
                return null;
            }
            
            NumericTable predictionData = new HomogenNumericTable(daal_Context, data, vectorSize, numRows);
            return predictionData;

        }

    private static ByteArray serializePartialResult(TrainingPartialResult partialResult) throws IOException {
          /* Create an output stream to serialize the numeric table */
          ByteArrayOutputStream outputByteStream = new ByteArrayOutputStream();
          ObjectOutputStream outputStream = new ObjectOutputStream(outputByteStream);

          /* Serialize the numeric table into the output stream */
          partialResult.pack();
          outputStream.writeObject(partialResult);

          /* Store the serialized data in an array */
          byte[] serializedPartialResult = outputByteStream.toByteArray();

          ByteArray partialResultHarp = new ByteArray(serializedPartialResult, 0, serializedPartialResult.length);
          return partialResultHarp;
      }

      private static TrainingPartialResult deserializePartialResult(ByteArray byteArray) throws IOException, ClassNotFoundException {
          /* Create an input stream to deserialize the numeric table from the array */
          byte[] buffer = byteArray.get();
          ByteArrayInputStream inputByteStream = new ByteArrayInputStream(buffer);
          ObjectInputStream inputStream = new ObjectInputStream(inputByteStream);

          /* Create a numeric table object */
          TrainingPartialResult restoredDataTable = (TrainingPartialResult) inputStream.readObject();
          restoredDataTable.unpack(daal_Context);

          return restoredDataTable;
      }

}
