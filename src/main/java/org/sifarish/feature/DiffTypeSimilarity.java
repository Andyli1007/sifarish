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

package org.sifarish.feature;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
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
import org.codehaus.jackson.map.ObjectMapper;
import org.sifarish.util.Entity;
import org.sifarish.util.Field;
import org.sifarish.util.FieldMapping;
import org.sifarish.util.Utility;

/**
 * Similarity between two different entity types based distance measure of attributes
 * @author pranab
 */
public class DiffTypeSimilarity  extends Configured implements Tool {
    @Override
    public int run(String[] args) throws Exception {
        Job job = new Job(getConf());
        String jobName = "Dirfferent type entity similarity MR";
        job.setJobName(jobName);
        
        job.setJarByClass(DiffTypeSimilarity.class);
        
        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        job.setMapperClass(DiffTypeSimilarity.SimilarityMapper.class);
        job.setReducerClass(DiffTypeSimilarity.SimilarityReducer.class);
        
        job.setMapOutputKeyClass(LongWritable.class);
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
    
    
    
    public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new DiffTypeSimilarity(), args);
        System.exit(exitCode);
    }
    
    public static class SimilarityMapper extends Mapper<LongWritable, Text, LongWritable, Text> {
        private LongWritable keyHolder = new LongWritable();
        private MixedTypeSchema schema;
        private int bucketCount;
        private long hash;
        private int idOrdinal;
        
        protected void setup(Context context) throws IOException, InterruptedException {
        	bucketCount = context.getConfiguration().getInt("bucket.count", 1000);
            
			Configuration conf = context.getConfiguration();
            String filePath = conf.get("schema.file.path");
            FileSystem dfs = FileSystem.get(conf);
            Path src = new Path(filePath);
            FSDataInputStream fs = dfs.open(src);
            ObjectMapper mapper = new ObjectMapper();
            schema = mapper.readValue(fs, MixedTypeSchema.class);
       }

        protected void cleanup(Context context) throws IOException, InterruptedException {
        }
        
        @Override
        protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
            String[] items  =  value.toString().split(",");
            Entity entity = schema.getEntityBySize(items.length);
            if (null != entity){
        		idOrdinal = entity.getIdField().getOrdinal();
        		hash = items[idOrdinal].hashCode() %  bucketCount;
            	if (entity.getType() == 0){
            		for (int i = 0; i < bucketCount; ++i) {
            			keyHolder.set((hash * bucketCount + i) * 10);
            			context.write(keyHolder, value);
            		}
            	} else {
            		for (int i = 0; i < bucketCount; ++i) {
            			keyHolder.set(((i * bucketCount + hash ) * 10) + 1);
            			context.write(keyHolder, value);
            		}
            	}
            } else {
            	
            }
        }        
    }   
    
    public static class SimilarityReducer extends Reducer<LongWritable, Text, NullWritable, Text> {
        private Text valueHolder = new Text();
        private MixedTypeSchema schema;
        private int firstTypeSize;
        private List<String> firstTypeValues = new ArrayList<String>();
        private int firstIdOrdinal;
        private int secondIdOrdinal;
        private String firstId;
        private String  secondId;
        private int sim;
        private List<Field> fields;
        private List<Field> targetFields;
        private int scale;
        private Map<Integer, MappedValue> mappedFields = new HashMap<Integer, MappedValue>();
        private static final int INVALID_ORDINAL = -1;
        private int srcCount;
        private int targetCount;
        private int simCount;
        private int simResultCnt;
        private boolean prntDetail;
        private DistanceStrategy distStrategy;
 
        protected void setup(Context context) throws IOException, InterruptedException {
        	//load schema
            Configuration conf = context.getConfiguration();
            String filePath = conf.get("schema.file.path");
            FileSystem dfs = FileSystem.get(conf);
            Path src = new Path(filePath);
            FSDataInputStream fs = dfs.open(src);
            ObjectMapper mapper = new ObjectMapper();
            schema = mapper.readValue(fs, MixedTypeSchema.class);
        	
        	firstTypeSize = schema.getEntityByType(0).getFieldCount();
        	firstIdOrdinal = schema.getEntityByType(0).getIdField().getOrdinal();
        	secondIdOrdinal = schema.getEntityByType(1).getIdField().getOrdinal();
        	fields = schema.getEntityByType(0).getFields();
        	targetFields = schema.getEntityByType(1).getFields();
        	scale = context.getConfiguration().getInt("distance.scale", 1000);
        	distStrategy = schema.createDistanceStrategy(scale);
        	
        	System.out.println("firstTypeSize: " + firstTypeSize + " firstIdOrdinal:" +firstIdOrdinal + 
        			" secondIdOrdinal:" + secondIdOrdinal + " Source field count:" + fields.size() + 
        			" Target field count:" + targetFields.size());
        }
        
        protected void reduce(LongWritable key, Iterable<Text> values, Context context)
        throws IOException, InterruptedException {
        	firstTypeValues.clear();
        	srcCount = 0;
        	targetCount = 0;
        	simCount = 0;
        	for (Text value : values){
        		String[] items = value.toString().split(",");
        		if (items.length == firstTypeSize){
        			firstTypeValues.add(value.toString());
        			++srcCount;
        		} else {
        			for (String first : firstTypeValues){
        				String second = value.toString();
        				//prntDetail =  ++simResultCnt % 10000 == 0;
        				sim = findSimilarity(first, second, context);
        				firstId = first.split(",")[firstIdOrdinal];
        				secondId = second.split(",")[secondIdOrdinal];
        				valueHolder.set(firstId + "," + second + "," + sim);
        				context.write(NullWritable.get(), valueHolder);
        				++simCount;
        			}
        			++targetCount;
        		}
        	}
			context.getCounter("Data", "Source Count").increment(srcCount);
			context.getCounter("Data", "Target Count").increment(targetCount);
			context.getCounter("Data", "Similarity Count").increment(simCount);
        	
        }
        
    	private int findSimilarity(String source, String target, Context context) {
    		int sim = 0;
    		mapFields(source, context);
    		String[] trgItems = target.split(",");
    		
    		double dist = 0;
			context.getCounter("Data", "Target Field Count").increment(targetFields.size());
			if (prntDetail){
				System.out.println("target record: " + trgItems[0]);
			}
			
			distStrategy.initialize();
    		for (Field field : targetFields) {
    			dist = 0;
    			Integer ordinal = field.getOrdinal();
				String trgItem = trgItems[ordinal];
				boolean skipAttr = false;
				if (prntDetail){
					System.out.println("ordinal: " + ordinal +  " target:" + trgItem);
				}
				
				MappedValue mappedValueObj = mappedFields.get(ordinal);
				if (null == mappedValueObj){
    				//non mapped passive attributes
					continue;
				}
				
    			List<String> mappedValues = mappedValueObj.getValues();
    			Field srcField = mappedValueObj.getField();
				if (!trgItem.isEmpty()) {
    				if (field.getDataType().equals("categorical")) {
    					if (!mappedValues.isEmpty()) {
	    					double thisDist;
	    					dist = 1.0;
	    					for (String mappedValue : mappedValues) {
	    						thisDist = schema.findCattegoricalDistance(mappedValue, trgItem, ordinal);
	    						if (thisDist < dist) {
	    							dist = thisDist;
	    						}
		    					if (prntDetail){
		    						System.out.println("dist calculation: ordinal: " + ordinal + " src:" + mappedValue + " target:" + trgItem + 
		    								" dist:" + dist);
		    					}
	    					}
	    					
    						context.getCounter("Data", "Dist Calculated").increment(1);
    					} else {
    						//missing source
    						if (schema.getMissingValueHandler().equals("default")){
    							dist = getDistForMissingSrc(field, trgItem);
    						} else {
    							skipAttr = true;
    						}
    						context.getCounter("Data", "Missing Source").increment(1);
    					}
    				} else if (field.getDataType().equals("int")) {
    					if (!mappedValues.isEmpty()) {
	    					int trgItemInt = Integer.parseInt(trgItem);
    						int srcItemInt = getAverageMappedValue(mappedValues);
    						dist = getDistForNumeric(srcField, srcItemInt, field, trgItemInt);
    					} else {
    						//missing source
    						if (schema.getMissingValueHandler().equals("default")){
    							dist = getDistForMissingSrc(field, trgItem);
    						} else {
    							skipAttr = true;
    						}
    					}
    				}
				} else {
					//missing target value
					if (schema.getMissingValueHandler().equals("default")){
						context.getCounter("Data", "Missing Target").increment(1);
						dist = getDistForMissingTrg(field, mappedValues);
					} else {
						skipAttr = true;
					}
				}
    			
				if (!skipAttr) {
					distStrategy.accumulate(dist, field.getWeight());
				}
    		}
    		sim = distStrategy.getSimilarity();
    		return sim;
    	}
    	
    	private double getDistForNumeric(Field srcField, int srcVal, Field trgField, int trgVal){
    		double dist = 0;
    		boolean linear = false;
    		String distFun = srcField.getNumDistFunction();
    		
    		if (distFun.equals("equalSoft")) {
    			linear = true;
    		} else if (distFun.equals("equalHard")) { 
    			dist = srcVal == trgVal ? 0 : 1;
    		} else if (distFun.equals("minSoft")) {
    			if (trgVal >= srcVal) {
    				dist = 0;
    			} else {
    				linear = true;
    			}
    		} else if (distFun.equals("minHard")) {
    			dist = trgVal >= srcVal ? 0 : 1;
    		} else if (distFun.equals("maxSoft")) {
    			if (trgVal <= srcVal) {
    				dist = 0;
    			} else {
    				linear = true;
    			}
    		} else if (distFun.equals("maxHard")) {
    			dist = trgVal <= srcVal ? 0 : 1;
    		}
    		
    		if (linear) {
    			if (trgField.getMax() > trgField.getMin()) {
    				dist = ((double)(srcVal - trgVal)) / (trgField.getMax() - trgField.getMin());
       			} else {
					int max = srcVal > trgVal ? srcVal : trgVal;
					double diff = ((double)(srcVal - trgVal)) / max;
					if (diff < 0) {
						diff = - diff;
					}
					dist = diff > schema.getNumericDiffThreshold() ? 1.0 : 0.0;
       				
       			}
				if (dist < 0) {
					dist = -dist;
				}
   			}
    		
    		return dist;
    	}
    	
    	private double getDistForMissingSrc(Field trgField, String trgVal){
    		double dist = 0;
			if (trgField.getDataType().equals("categorical")) {
				dist = 0;
			} else if (trgField.getDataType().equals("int")) {
				int trgValInt = Integer.parseInt(trgVal);
				int max = trgField.getMax();
				int min = trgField.getMin();
				if (max > trgField.getMin()) {
					double upper = ((double)(max - trgValInt)) / (max - min);
					double lower = ((double)(trgValInt - min)) / (max - min);
					dist = upper > lower ? upper : lower;
				} else {
					dist = 1;
				}
			}
    		return dist;
    	}
    	
    	private double getDistForMissingTrg(Field trgField, List<String> mappedValues){
    		double dist = 0;
			if (trgField.getDataType().equals("categorical")) {
				dist = 1;
			}  else if (trgField.getDataType().equals("int")) {
				int srcValInt = getAverageMappedValue(mappedValues);
				int max = trgField.getMax();
				int min = trgField.getMin();
				if (max > min) {
					double upper = ((double)(max - srcValInt)) / (max - min);
					double lower = ((double)(srcValInt - min)) / (max - min);
					dist = upper > lower ? upper : lower;
				} else {
					dist = 1;
				}
			}

    		return dist;
    	}
    	
    	private void mapFields(String source, Context context){
	    	mappedFields.clear();
			String[] srcItems = source.split(",");
			
			if (prntDetail){
				System.out.println("src record: " + srcItems[0]);
			}
			for (Field field : fields) {
				List<FieldMapping> mappings = field.getMappings();
				if (null != mappings){
					for (FieldMapping fldMapping : mappings) {
						int matchingOrdinal = fldMapping.getMatchingOrdinal();
						if (-1 == matchingOrdinal) {
							continue;
						}
						
						MappedValue mappedValue = mappedFields.get(matchingOrdinal);
						if (null == mappedValue){
							mappedValue = new MappedValue();
							mappedValue.setField(field);
							mappedFields.put(matchingOrdinal, mappedValue);
						}
						List<String> mappedValues = mappedValue.getValues();
						
						String value = srcItems[field.getOrdinal()];
						if (prntDetail){
							System.out.println("src value: " + value);
						}
						
						List<FieldMapping.ValueMapping> valueMappings = fldMapping.getValueMappings();
						if (null != valueMappings) {
							for (FieldMapping.ValueMapping valMapping : valueMappings) {
								if (field.getDataType().equals("categorical")) {
									if (valMapping.getThisValue().equals(value)) {
										mappedValues.add(valMapping.getThatValue());
			    						context.getCounter("Data", "Mapped Value").increment(1);
			    						if (prntDetail){
			    							System.out.println("mapped: " + value + "  " + valMapping.getThatValue() + 
			    									" matching ordinal:" + matchingOrdinal);
			    						}
										break;
									}
								} else if (field.getDataType().equals("int")) {
									int valueInt = Integer.parseInt(value);
									int[] range = valMapping.getThisValueRange();
									if (null != range) {
										if (valueInt >= range[0] && valueInt <= range[1]) {
											mappedValues.add(valMapping.getThatValue());
											break;
										}
									}
								}
							}
						} else {
    						if (prntDetail){
    							System.out.println("non mapped: " + value + " matching ordinal:" + matchingOrdinal);
    						}
							if (!value.isEmpty()) {
								mappedValues.add(value);
							}
						}
					}
				} 
			}
	    }
    	
        private int getAverageMappedValue(List<String> mappedValues){
    		int sum = 0;
    		int count = 0;
    		for (String mappedValue : mappedValues) {
    			sum += Integer.parseInt(mappedValue);
    			++count;
    		}    	
    		int fItemInt = sum / count;
    		return fItemInt;
        }
        
    	
    }  
    
     

    public static class IdPairPartitioner extends Partitioner<LongWritable, Text> {
	     @Override
	     public int getPartition(LongWritable key, Text value, int numPartitions) {
	    	 //consider only base part of  key
		     int keyVal = (int)(key.get() / 10);
		     return keyVal % numPartitions;
	     }
    
    }

    public static class IdPairGroupComprator extends WritableComparator {
    	private static final int KEY_EXTENSION_SCALE = 10;
    	protected IdPairGroupComprator() {
    		super(LongWritable.class, true);
    	}

    	@Override
    	public int compare(WritableComparable w1, WritableComparable w2) {
    		//consider only the base part of the key
    		Long t1 = ((LongWritable)w1).get() / KEY_EXTENSION_SCALE;
    		Long t2 = ((LongWritable)w2).get() / KEY_EXTENSION_SCALE;
    		
    		int comp = t1.compareTo(t2);
    		return comp;
    	}
     }
    
    public static class MappedValue {
    	private List<String> values = new ArrayList<String>();
    	private Field field;
    	
		public List<String> getValues() {
			return values;
		}
		public void setValues(List<String> values) {
			this.values = values;
		}
		public Field getField() {
			return field;
		}
		public void setField(Field field) {
			this.field = field;
		}
    	
    }
    
}
