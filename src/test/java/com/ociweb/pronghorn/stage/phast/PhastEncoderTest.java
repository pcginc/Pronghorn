package com.ociweb.pronghorn.stage.phast;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import org.junit.Test;

import com.ociweb.pronghorn.pipe.DataInputBlobReader;
import com.ociweb.pronghorn.pipe.DataOutputBlobWriter;
import com.ociweb.pronghorn.pipe.Pipe;
import com.ociweb.pronghorn.pipe.PipeConfig;
import com.ociweb.pronghorn.pipe.RawDataSchema;
import com.ociweb.pronghorn.stage.phast.*;

import static org.junit.Assert.*;


public class PhastEncoderTest {
	
	@Test
	public void testEncodeString() throws IOException{
		//create a new blob pipe to put a string on 
		Pipe<RawDataSchema> encodedValuesToValidate = new Pipe<RawDataSchema>(new PipeConfig<RawDataSchema>(RawDataSchema.instance, 100, 4000));
		encodedValuesToValidate.initBuffers();
		DataOutputBlobWriter<RawDataSchema> writer = new DataOutputBlobWriter<RawDataSchema>(encodedValuesToValidate);
		
		//encode a string on there using the static method
		PhastEncoder.encodeString(writer, 0, 0, "This is a test");
		Pipe.publishAllBatchedWrites(encodedValuesToValidate);
		
		//check what is on the pipe
		DataInputBlobReader<RawDataSchema> reader = new DataInputBlobReader<RawDataSchema>(encodedValuesToValidate);
		//should be -63
		int test = reader.readPackedInt();
		//char length is 14 so this should be 28
		int lengthOfString = reader.readPackedInt();
		//the string
		StringBuilder value = new StringBuilder();
		reader.readPackedChars(value);
		
		String s = value.toString();
		assertTrue((test==-63) && (lengthOfString==28) && (s.compareTo("This is a test")==0));
		
	}
}
