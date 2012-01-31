/*
 * Sifarish: Recommendation Engine
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.sifarish.common;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.chombo.util.IntPair;
import org.chombo.util.Utility;
import org.sifarish.feature.DynamicAttrSimilarityStrategy;

/**
 * Mapreduce for finding similarities between items with dynamic set of attributes. For example,  products 
 * where the atrributes   are users who have purchased it or documents where the attributes are terms in
 * the documents.
 * 
 * @author pranab
 *
 */
public class ItemDynamicAttributeSimilarity  extends Configured implements Tool{
    @Override
    public int run(String[] args) throws Exception   {
        Job job = new Job(getConf());
        String jobName = "Item with dynamic attribute  similarity MR";
        job.setJobName(jobName);
        
        job.setJarByClass(ItemDynamicAttributeSimilarity.class);
        
        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        job.setMapperClass(ItemDynamicAttributeSimilarity.SimilarityMapper.class);
        job.setReducerClass(ItemDynamicAttributeSimilarity.SimilarityReducer.class);
        
        job.setMapOutputKeyClass(IntPair.class);
        job.setMapOutputValueClass(Text.class);

        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);
 
        job.setGroupingComparatorClass(IdPairGroupComprator.class);
        job.setPartitionerClass(IdPairPartitioner.class);

        Utility.setConfiguration(job.getConfiguration());

        job.setNumReduceTasks(job.getConfiguration().getInt("num.reducer", 1));
        
        int status =  job.waitForCompletion(true) ? 0 : 1;
        return status;
    }

    public static class SimilarityMapper extends Mapper<LongWritable, Text, IntPair, Text> {
        private int bucketCount;
        private int hash;
        private String fieldDelimRegex;
        private Integer hashPair;
        private Integer one = 1;
        private Integer zero = 0;
        private String itemID;
        private IntPair keyHolder = new IntPair();
        private Text valueHolder = new Text();
        private int hashPairMult;
    	
        protected void setup(Context context) throws IOException, InterruptedException {
        	bucketCount = context.getConfiguration().getInt("bucket.count", 10);
        	fieldDelimRegex = context.getConfiguration().get("field.delim.regex", "\\[\\]");
        	hashPairMult = context.getConfiguration().getInt("hash.pair.multiplier", 1000);
        }    
        
        @Override
        protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
        	itemID  =  value.toString().split(fieldDelimRegex)[0];
    		hash = (itemID.hashCode() %  bucketCount  + bucketCount) / 2 ;

    		for (int i = 0; i < bucketCount;  ++i) {
    			if (i < hash){
       				hashPair = hash * hashPairMult +  i;
       				keyHolder.set(hashPair, zero);
       				valueHolder.set("0" + value.toString());
       	   		 } else {
    				hashPair =  i * hashPairMult  +  hash;
       				keyHolder.set(hashPair, one);
       				valueHolder.set("1" + value.toString());
    			} 
    			//System.out.println("mapper hashPair: " + hashPair);
   	   			context.write(keyHolder, valueHolder);
    		}
        }
             
    }
    
    public static class SimilarityReducer extends Reducer<IntPair, Text, NullWritable, Text> {
        private Text valueHolder = new Text();
        private String fieldDelim;
    	private String fieldDelimRegex;
        private int delimLength;
        private int hashPairMult;
        private List<String[]> valueList = new ArrayList<String[]>();
        private DynamicAttrSimilarityStrategy simStrategy;
        private int scale;
        
        protected void setup(Context context) throws IOException, InterruptedException {
        	fieldDelim = context.getConfiguration().get("field.delim", "[]");
        	fieldDelimRegex = context.getConfiguration().get("field.delim.regex", "\\[\\]");
        	delimLength =  fieldDelim.length();
        	hashPairMult = context.getConfiguration().getInt("hash.pair.multiplier", 1000);
        	String simAlgorithm = context.getConfiguration().get("similarity.algorithm", "cosine");
        	
        	Map<String, Object> params = new HashMap<String, Object>();
        	simStrategy = DynamicAttrSimilarityStrategy.createSimilarityStrategy(simAlgorithm, params);
        	simStrategy.setFieldDelimRegex(fieldDelimRegex);
        	simStrategy.setBooleanVec(true);
           	scale = context.getConfiguration().getInt("distance.scale", 1000);
          }    

        protected void reduce(IntPair  key, Iterable<Text> values, Context context)
        throws IOException, InterruptedException {
        	double dist = 0;
        	valueList.clear();
        	int firstPart = key.getFirst().get();
        	//System.out.println("hashPair: " + firstPart);
        	if (firstPart / hashPairMult == firstPart % hashPairMult){
        		//same hash bucket
    			context.getCounter("Reducer", "Same  Bucket Count").increment(1);
    			//System.out.println("**same bucket");
    			
	        	for (Text value : values){
	        		String valSt = value.toString();
        			String[] parts = splitKey(valSt.substring(1));
        			valueList.add(parts);
	        	}   
	        	
	        	for (int i = 0;  i < valueList.size();  ++i){
	        		String[] firstParts = valueList.get(i);
	        		for (int j = i+1;  j < valueList.size();  ++j) {
		        		String[] secondParts = valueList.get(j);
        				dist = ( 1.0 - simStrategy.findDistance(firstParts[1], secondParts[1]))  * scale;
        				dist = dist < 0.0 ? 0.0 : dist;
	   					valueHolder.set(firstParts[0] + fieldDelim + secondParts[0] + fieldDelim + (int)dist);
	   	    			context.getCounter("Reducer", "Emit").increment(1);
	   					context.write(NullWritable.get(), valueHolder);
	        		}
	        	}
        	} else {
        		//different hash bucket
    			context.getCounter("Reducer", "Diff Bucket Count").increment(1);
    			//System.out.println("**diff  bucket");
	        	for (Text value : values){
	        		String valSt = value.toString();
	        		if (valSt.startsWith("0")) {
	        			String[] parts = splitKey(valSt.substring(1));
	        			valueList.add(parts);
	        		} else {
	        			String[] parts = splitKey(valSt.substring(1));
	        			
	        			//match with all items of first set
	        			for (String[] firstParts : valueList) {
	        				dist = (1.0 - simStrategy.findDistance(firstParts[1], parts[1])) * scale;
	        				dist = dist < 0.0 ? 0.0 : dist;
		   					valueHolder.set(firstParts[0] + fieldDelim + parts[0] + fieldDelim + (int)dist);
		   	    			context.getCounter("Reducer", "Emit").increment(1);
		   					context.write(NullWritable.get(), valueHolder);
	        			}
	        		}
	        	}
        	}
       	
        }
        
        private String[]   splitKey(String val) {
        	String[] parts = new String[2];
        	int pos = val.indexOf(fieldDelim);
        	parts[0] = val.substring(0, pos);
        	parts[1] = val.substring( pos +delimLength);
        	return parts;
        }
        
    }
    
    public static class IdPairPartitioner extends Partitioner<IntPair, Text> {
	     @Override
	     public int getPartition(IntPair key, Text value, int numPartitions) {
	    	 //consider only base part of  key
		     return key.baseHashCode() % numPartitions;
	     }
  
  }

    
    public static class IdPairGroupComprator extends WritableComparator {
    	protected IdPairGroupComprator() {
    		super(IntPair.class, true);
    	}

    	@Override
    	public int compare(WritableComparable w1, WritableComparable w2) {
    		//consider only the base part of the key
    		IntPair t1 = (IntPair)w1;
    		IntPair t2 = (IntPair)w2;
    		
    		int comp =t1.getFirst().compareTo(t2.getFirst());
    		return comp;
    	}
     }
  
    public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new ItemDynamicAttributeSimilarity(), args);
        System.exit(exitCode);
    }

}
