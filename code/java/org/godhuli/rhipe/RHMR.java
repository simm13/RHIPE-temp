/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.godhuli.rhipe;

import java.io.IOException;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataOutput;
import java.io.DataInput;
import java.io.EOFException;
import java.net.URISyntaxException;
import java.util.Hashtable;
import java.util.Enumeration;
import java.util.Calendar;
import java.net.URI;

import java.text.SimpleDateFormat;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.fs.FileSystem;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.mapreduce.Job;


import org.godhuli.rhipe.REXPProtos.REXP;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;

public class RHMR  implements Tool {
    protected Environment env_;
    protected String[] argv_;
    protected Configuration config_;
    protected Hashtable<String,String> rhoptions_;
    protected Job job_;
    protected boolean debug_;
    public static final String DATE_FORMAT_NOW = "yyyy-MM-dd HH:mm:ss";
    final static Log LOG = LogFactory.getLog(RHMR.class);

    public static void main(String[] args)  {
	int res;
	try{
	    // (new RHMR()).doTest();
	    // System.exit(0);
	    res = ToolRunner.run(new Configuration(), new RHMR(), args);
	}catch(Exception ex){
	    ex.printStackTrace();
	    res=-2;
	}
	System.exit(res);
    }

    public void doTest(){
	int i;
	for(i=0;i<1;i++){System.out.println("I="+i);};
	System.out.println("Last I="+i);
    }

    public void setConf(Configuration c){}
    protected void init()  {
	try {
	    debug_=false;
	    rhoptions_ = new Hashtable<String,String>();
	    readParametersFromR(argv_[0]);
	    env_ = new Environment();
	    config_ = new Configuration();
	    setConf();
	    job_ = new Job(config_);
	    setJob();

	    
	} catch (Exception io) {
	    io.printStackTrace();
	    throw new RuntimeException(io);
	}
    }
    
    public Configuration getConf() {
	return config_;
    }
    
    public int run(String[] args) throws Exception {
	this.argv_ = args;
	init();
	submitAndMonitorJob();
	return 1;
    }

    public void setConf() throws IOException,URISyntaxException
    {
	Enumeration keys = rhoptions_.keys();
	while( keys.hasMoreElements() ) {
	    String key = (String) keys.nextElement();
	    String value = (String) rhoptions_.get(key);
	    config_.set(key,value);
	}
	String[] shared = config_.get("rhipe_shared").split(",");
	if(shared!=null){
	    for(String p : shared)
		if(p.length()>1) DistributedCache.addCacheFile(new URI(p),config_);
	}
	DistributedCache.createSymlink(config_);

    }

    public void setJob() throws ClassNotFoundException,IOException,
				    URISyntaxException {
	Calendar cal = Calendar.getInstance();
	SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT_NOW);

	job_.setJobName(sdf.format(cal.getTime()));
	job_.setJarByClass(RHMR.class);

	Class<?> clz=config_.getClassByName(rhoptions_.get("rhipe_outputformat_class"));
	Class<? extends OutputFormat> ofc = clz.asSubclass(OutputFormat.class);
	job_.setOutputFormatClass(ofc);

	Class<?> clz2=config_.getClassByName(  rhoptions_.get("rhipe_inputformat_class"));
	Class<? extends InputFormat> ifc = clz2.asSubclass(InputFormat.class);
	job_.setInputFormatClass(ifc);
	
	if(!rhoptions_.get("rhipe_input_folder").equals(""))
	    FileInputFormat.setInputPaths(job_,rhoptions_.get("rhipe_input_folder"));

	String output_folder = rhoptions_.get("rhipe_output_folder");
	if(! output_folder.equals("")){
		Path ofp = new Path(output_folder);
		FileSystem srcFs = FileSystem.get(job_.getConfiguration());
		srcFs.delete(ofp, true);
		FileOutputFormat.setOutputPath(job_,ofp);
		job_.setOutputKeyClass(Class.forName(rhoptions_
						     .get("rhipe_outputformat_keyclass")));
		job_.setOutputValueClass(Class.forName(rhoptions_
						       .get("rhipe_outputformat_valueclass")));
		job_.setMapOutputKeyClass(RHBytesWritable.class);
		job_.setMapOutputValueClass(RHBytesWritable.class);
	    } else{
		job_.setOutputFormatClass(org.apache.hadoop.mapreduce.lib.output.NullOutputFormat.class);
		job_.setOutputKeyClass(org.apache.hadoop.io.NullWritable.class);
		job_.setOutputValueClass(org.apache.hadoop.io.NullWritable.class);
		job_.setMapOutputKeyClass(org.apache.hadoop.io.NullWritable.class);
		job_.setMapOutputValueClass(org.apache.hadoop.io.NullWritable.class);
	    }

	job_.setMapperClass(RHMRMapper.class);
	job_.setReducerClass(RHMRReducer.class);
	if(rhoptions_.get("rhipe_combiner").equals("TRUE"))
	    job_.setCombinerClass(RHMRCombiner.class);


	
    }

    public int submitAndMonitorJob() throws Exception {
	int k =0;
	job_.submit();
	LOG.info("Tracking URL ----> "+ job_.getTrackingURL());
	boolean verb = rhoptions_.get("rhipe_job_verbose").equals("TRUE")
	    ? true: false;
	boolean result = job_.waitForCompletion( verb );
	if(!result)
	    k=-1;
	return k;
    }

    public void readParametersFromR(String configfile) throws
	IOException
    {
	FileInputStream in = new FileInputStream (configfile);
	DataInputStream fin = new DataInputStream(in);
	byte[] d;
	String key,value;
	int n0 = fin.readInt(),n;
	for(int i=0;i<n0;i++){
	    // R Writes Null Terminated Strings(when I dont use char2Raw)
	    try{
		n = fin.readInt();
		d  = new byte[n];
		fin.readFully(d,0,d.length);
		key = new String(d);
		
		n = fin.readInt();
		d = new byte[n];
		fin.readFully(d,0,d.length);
		value = new String(d);
		rhoptions_.put(key,value);
	    }catch(EOFException e){
		throw new IOException(e);
	    }
	}
	fin.close();	
	if(debug_){
	    Enumeration keys = rhoptions_.keys();
	    while( keys.hasMoreElements() ) {
		String key0 = (String) keys.nextElement();
		String value0 = (String) rhoptions_.get(key0);
		System.out.println(key0+"="+value0);
	    }
	}
    }
}