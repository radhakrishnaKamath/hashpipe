import java.util.*;
import java.io.File;
import java.io.FileNotFoundException;

/* high level procedure that uses packet info from a csv file, parses it,
   induces loss on it, and produces a sketch for the lost packet on which
   the big loser identification process is performed 

   initially written for the sketches to all be reversible so that the 
   reversibility procedure would identify the lossy buckets - unused 
   code in the context of the hash table approach*/
public class TopKIdentifierFlowIdD4{
	private static HashSet<String> expectedHH;
	private static HashMap<String, Integer> flowSizes;
	private static ArrayList<FlowIdWithCount> flowAggWithSizes;
	//private static double[] thetaValues;

	private static double accuracy = 0.99;

	public static void runTopKIdentificationTrials(SummaryStructureType type, ArrayList<Packet> inputStream, int[] k, int tableSize, int D, double[] thetaValues){
		int numberOfTrials = 1;
		int cumDroppedPacketInfoCount = 0;
		double cumProblematicEvictionFraction = 0;
		int observedSize[] = new int[k.length];
		int expectedSize[] = new int[k.length];
		int numWithin1Dev[] = new int[k.length];
		int numWithin2Dev[] = new int[k.length];
		double theoreticalProb[] = new double[k.length];
		int numberOfFalsePositives[] = new int[k.length];
		int numberOfFalseNegatives[] = new int[k.length];
		int underEstimatedFlows[] = new int[k.length];
		double underEstimateAmount[] = new double[k.length];
		int missingFromTable[] = new int[k.length];
		long bigLoserPacketReported[] = new long[k.length];
		long bigLoserPacketCount[] = new long[k.length];
		float occupiedSlots[] = new float[k.length];
		float duplicates[] = new float[k.length];
		double keysSeen[] = new double[k.length];
		double frequencyByTable[][] = new double[k.length][D];

		double varianceFP[] = new double[k.length];
		double varianceFN[] = new double[k.length];
		double varianceRep[] = new double[k.length];
		// track the unique lost flows
		DLeftHashTableFlowId lostFlowHashTable = null;
		GroupCounters gcHashTable = null;

		double cumDeviation[] = new double[k.length];
		HashMap<String, Long> observedHH = new HashMap<String, Long>();
		ArrayList<FlowIdWithCount> outputFlowsList = new ArrayList<FlowIdWithCount>();

		//hashmap to compute which ranked flows get evcited more
		HashMap<Integer, Integer> rankToFrequency = new HashMap<Integer, Integer>();

		for (int t = 0; t < numberOfTrials; t++){

			// given input, so ideal order of heavy hitters
			FlowIdWithCount[] inputFlowArray = new FlowIdWithCount[flowAggWithSizes.size()];
			inputFlowArray = flowAggWithSizes.toArray(inputFlowArray);
			Arrays.sort(inputFlowArray);

			if (type == SummaryStructureType.GroupCounters)
				gcHashTable = new GroupCounters(tableSize, type,  inputStream.size(), D);
			else
				lostFlowHashTable = new DLeftHashTableFlowId(tableSize, type, inputStream.size(), D, flowSizes);

			//Collections.shuffle(inputStream); // randomizing the order

			int count = 0;
			for (Packet p : inputStream){
				//lostPacketSketch.updateCountInSketch(p);
				//System.out.println(p.getFlowId());
				if (type == SummaryStructureType.GroupCounters)
					gcHashTable.processData(p.getSrcIp());
				else
					lostFlowHashTable.processData(p.getFlowId(), count++, rankToFrequency);
				
			}

			if (type == SummaryStructureType.GroupCounters)
				cumDroppedPacketInfoCount += gcHashTable.getDroppedPacketInfoCount();
			else {
				cumDroppedPacketInfoCount += lostFlowHashTable.getDroppedPacketInfoCount();
				cumProblematicEvictionFraction += lostFlowHashTable.getProblematicEvictionFraction();
			}
			

			for (int k_index = 0; k_index < k.length; k_index++){
				outputFlowsList = new ArrayList<FlowIdWithCount>();
				//theoreticalProb[k_index] = 1 - Math.pow((thetaValues[k_index]/tableSize), D);

				if (type == SummaryStructureType.EvictionWithoutCount){
					Sketch lossEstimateSketch = lostFlowHashTable.getSketch();
					for (String f : lostFlowHashTable.getFlowIdBuckets()){
						if (!f.equals(""))
							occupiedSlots[k_index]++;

						outputFlowsList.add(new FlowIdWithCount(f, lossEstimateSketch.estimateCountBigHash(f)));
						//System.out.println(f.flowid);
					}
				}
				else if (type == SummaryStructureType.RollingMinSingleLookup){
					HashMap<String, Long> currentFlows = new HashMap<String, Long>();
					for (FlowIdWithCount f : lostFlowHashTable.getBuckets()){
						if (!f.flowid.equals(""))
							occupiedSlots[k_index]++;

						//System.out.println(f.flowid + " " + f.count); 
						//if (/*f.flowid != 0 && */f.count > k*lostPacketStream.size()){
						if (currentFlows.containsKey(f.flowid)){
							currentFlows.put(f.flowid, currentFlows.get(f.flowid) + f.count);
							duplicates[k_index]++;
						}
						else
							currentFlows.put(f.flowid, f.count);
						//System.out.println(f.flowid);
					}

					for (String f : currentFlows.keySet()){
						outputFlowsList.add(new FlowIdWithCount(f, currentFlows.get(f)));
					}
				}
				else {
					HashMap<String, Long> currentFlows = new HashMap<String, Long>();
					for (FlowIdWithCount f : lostFlowHashTable.getBuckets()){
						if (!f.flowid.equals(""))
							occupiedSlots[k_index]++;

						//System.out.println(f.flowid + " " + f.count); 
						//if (/*f.flowid != 0 && */f.count > threshold*lostPacketStream.size()){
						if (currentFlows.containsKey(f.flowid)){
							currentFlows.put(f.flowid, currentFlows.get(f.flowid));
							duplicates[k_index]++;
						}
						else
							currentFlows.put(f.flowid, f.count);
						//System.out.println(f.flowid);
					}

					for (FlowIdWithCount f : lostFlowHashTable.getBuckets()){
						outputFlowsList.add(new FlowIdWithCount(f.flowid, f.count));
					}
				}

				// observed flows in sorted order so that we can pick the hh as the top k
				FlowIdWithCount[] outputFlowBuckets = new FlowIdWithCount[outputFlowsList.size()];
				outputFlowBuckets = outputFlowsList.toArray(outputFlowBuckets);
				Arrays.sort(outputFlowBuckets);

				/* print counter values to stdout
				for (int i = 0; i < outputFlowBuckets.length; i++){
					System.out.println(outputFlowBuckets[i].count);
				}*/

				observedHH = new HashMap<String, Long>();
				for (int i = 0; i < k[k_index] && i < outputFlowBuckets.length; i++){
					observedHH.put(outputFlowBuckets[i].flowid, outputFlowBuckets[i].count);
				}

				int bigLoserPacketsLost = 0;
				int flag = 0;
				double deviation = 0;
				double denominator = 0;
				double curUnderEstimation = 0;


				// first k in inputStream are expected hh - fix which heavy hitters you look at for cdf of competitors
				expectedHH = new HashSet<String>();
				for (int i = 0; i < k[k_index]; i++){
					expectedHH.add(inputFlowArray[i].flowid);
				}

				for (int i = 0; i < outputFlowBuckets.length; i++){
					if (expectedHH.contains(outputFlowBuckets[i].flowid) == false){
						for (int j = 0; j < D; j++){
							if (i >= j*tableSize/D && i < (j+1)*tableSize/D)
								frequencyByTable[k_index][j]++;
						}
					}
				}

				double presentInTable = 0;
				int curFalseNegs = 0;
				long curBLCount = 0;
				long curBLTotalCount = 0;
				for (String flowid : expectedHH){
					long repSize = 0;
					if (!observedHH.containsKey(flowid)){
						numberOfFalseNegatives[k_index]++;
						curFalseNegs++;
					}
					else {
						bigLoserPacketReported[k_index] += flowSizes.get(flowid);
						curBLCount += flowSizes.get(flowid);
						repSize = observedHH.get(flowid);
					}

					int tempFlag = 0;
					for (FlowIdWithCount f : outputFlowsList){
						if (f.flowid == flowid){
							tempFlag = 1;
							break;
						}
					}
					if (tempFlag == 0)
						missingFromTable[k_index]++;
					else
						presentInTable++;
					bigLoserPacketCount[k_index] += flowSizes.get(flowid);
					curBLTotalCount += flowSizes.get(flowid);
					//if (type != SummaryStructureType.RollingMinSingleLookup)
						System.err.println(flowSizes.get(flowid) + "," + repSize);
				}

				expectedSize[k_index] = expectedHH.size();
				observedSize[k_index] = observedHH.size();
				varianceFN[k_index] += (curFalseNegs/(double) expectedSize[k_index]) *(curFalseNegs/(double) expectedSize[k_index]);
				varianceRep[k_index] += (curBLCount/(double) curBLTotalCount) *(curBLCount/(double) curBLTotalCount);

				/* check where within u + sigma this lies*/
				double theoreticalMean = k[k_index]*theoreticalProb[k_index];
				double theoreticalVar = Math.sqrt(k[k_index]*theoreticalProb[k_index]*(1-theoreticalProb[k_index]));
				if (presentInTable < (theoreticalMean + theoreticalVar) && presentInTable > (theoreticalMean - theoreticalVar)){
					//System.out.println("case1");
					numWithin1Dev[k_index]++;
					numWithin2Dev[k_index]++;
				}
				else if (presentInTable < (theoreticalMean + 2*theoreticalVar) && presentInTable > (theoreticalMean - 2*theoreticalVar)) {
					//System.out.println("case2");
					numWithin2Dev[k_index]++;
				}

				int curFalsePos = 0;
				for (String flowid : observedHH.keySet()){
					//System.out.println("hello");
					//System.out.println(FlowDataParser.convertLongToAddress(flowid));
					if (!expectedHH.contains(flowid)){
						//System.out.println(FlowDataParser.convertLongToAddress(flowid));
						numberOfFalsePositives[k_index]++;
						curFalsePos++;
					}
					/*if (flowSizes.get(flowid) == null)
						System.out.println(flowid);*/
					//System.out.print(observedHH.get(flowid));
					//System.out.print(" flowid=" + flowid + " " + flowSizes.get(flowid));
					/*if (!expectedHH.contains(flowid) && observedHH.get(flowid) > flowSizes.get(flowid)){
						System.out.println(flowid + " " + observedHH.get(flowid) + " " + flowSizes.get(flowid));
					}*/

					/*deviation += Math.abs(observedHH.get(flowid) - flowSizes.get(flowid));
					if (observedHH.get(flowid) - flowSizes.get(flowid) < 0){
						underEstimatedFlows[k_index]++;
						curUnderEstimation += Math.abs(observedHH.get(flowid) - flowSizes.get(flowid));
					}
					denominator += flowSizes.get(flowid);*/
				}
				varianceFP[k_index] += (curFalsePos/(double) observedSize[k_index]) *(curFalsePos/(double) observedSize[k_index]);

				cumDeviation[k_index] += deviation/denominator;
				underEstimateAmount[k_index] += curUnderEstimation/denominator;
				//System.out.println(numWithin1Dev[k_index] + " " + numWithin2Dev[k_index]);

				/*if ((type == SummaryStructureType.OverallMinReplacement || type == SummaryStructureType.BasicHeuristic) && k_index == 0){
					int[] keysPerBucket = lostFlowHashTable.getKeysPerBucket();
					FlowIdWithCount[] flows = lostFlowHashTable.getBuckets();
					for (int i = 0; i < keysPerBucket.length; i++){
						boolean isFP = false;
						boolean isTP = false;
						if (observedHH.containsKey(flows[i].flowid)){
							if (expectedHH.contains(flows[i].flowid)) isTP = true;
							else isFP = true;
						}
						double thisdeviation = Math.abs((flows[i].count - flowSizes.get(flows[i].flowid))/(double) flowSizes.get(flows[i].flowid));
						System.err.println(keysPerBucket[i]+ "," + isFP + "," + isTP + "," + thisdeviation);
						keysSeen[k_index] += keysPerBucket[i];
					}
				}*/
			}
		}

		/*for (int k_index = 0; k_index < k.length; k_index++){
			varianceFP[k_index] = (varianceFP[k_index] - ((numberOfFalsePositives[k_index]*numberOfFalsePositives[k_index])/(double) numberOfTrials/observedSize[k_index]/observedSize[k_index]))/(numberOfTrials - 1);
			varianceFN[k_index] = (varianceFN[k_index] - ((numberOfFalseNegatives[k_index]*numberOfFalsePositives[k_index])/(double) numberOfTrials/expectedSize[k_index]/expectedSize[k_index]))/(numberOfTrials - 1);
			varianceRep[k_index] = (varianceRep[k_index] - ((bigLoserPacketReported[k_index]*bigLoserPacketReported[k_index])/(double) numberOfTrials/bigLoserPacketCount[k_index]/bigLoserPacketCount[k_index]))/(numberOfTrials - 1);
		}*/

		for (int k_index = 0; k_index < k.length; k_index++){
			System.out.print(tableSize + "," + k[k_index] + "," + D + ",");
			System.out.print((double) numberOfFalsePositives[k_index]/numberOfTrials/(flowSizes.size() - k[k_index]) + ",");
			System.out.print((double) numberOfFalseNegatives[k_index]/numberOfTrials/expectedSize[k_index] + ",");
			System.out.print(expectedSize[k_index] + "," + observedSize[k_index] + "," + (double) bigLoserPacketReported[k_index]/bigLoserPacketCount[k_index]);
			System.out.print("," + cumDeviation[k_index]/numberOfTrials + "," + occupiedSlots[k_index]/tableSize/numberOfTrials + "," + duplicates[k_index]/tableSize/numberOfTrials);
			//System.out.print("," + (double) missingFromTable[k_index]/numberOfTrials/expectedSize[k_index] + "," + cumProblematicEvictionFraction/numberOfTrials);
			//System.out.print("," + theoreticalProb[k_index] + "," + (double) numWithin1Dev[k_index] + "," + (double) numWithin2Dev[k_index]/numberOfTrials + ",");
			//System.out.print((double) underEstimatedFlows[k_index]/numberOfTrials/observedSize[k_index] + "," + (double) underEstimateAmount[k_index]/numberOfTrials+ ",");
			//System.out.print((double) Math.sqrt(varianceFP[k_index]) + "," + Math.sqrt(varianceFN[k_index]) + "," + Math.sqrt(varianceRep[k_index]) + ",");
			/*for (int i = 0; i < D; i++){
				System.out.print("," + frequencyByTable[k_index][i]);
			}
			if (type == SummaryStructureType.OverallMinReplacement){
				System.out.print("," + keysSeen[k_index]/numberOfTrials/tableSize);
			}*/
			System.out.println();
		}

		/*for (int r : rankToFrequency.keySet())
			System.err.println(r + "," + rankToFrequency.get(r));*/

		//lostFlowHashTable.printBuckets();
	}

	public static void runTrialsPerK(SummaryStructureType type, ArrayList<Packet> inputPacketStream, int[] k, int totalMemory, int D, long thr_totalPackets)
	{
		//double thres[] = {0.002186, 0.001238, 0.000821, 0.000708, 0.000615, 0.000459};
		
		// dstip on 10M packets
		/*double thres[] = {2090, 1077, 546, 261, 109};
		for (int i = 0; i < thres.length; i++)
			thres[i] /= 303314;*/

		/*double thres[] = {24860, 21102, 14953, 11230, 8812, 7392, 4873};
		for (int i = 0; i < thres.length; i++)
			thres[i] /= 11849078;*/

		double thresh = 3800.0/10000000;
		int numberOfTrials = 1;
		int observedSize[] = new int[k.length];
		int expectedSize[] = new int[k.length];
		int numberOfFalsePositives[] = new int[k.length];
		int numberOfFalseNegatives[] = new int[k.length];
		long hhPacketReported[] = new long[k.length];
		long hhPacketCount[] = new long[k.length];
		float occupancy[] = new float[k.length];
		double cumDeviation[] = new double[k.length];
		float controllerReportCount[] = new float[k.length];

		int observedSizeFromDump[] = new int[k.length];
		int numberOfFalsePositivesinDump[] = new int[k.length];
		int numberOfFalseNegativesinDump[] = new int[k.length];
		long hhPacketReportedinDump[] = new long[k.length];
		double cumDeviationinDump[] = new double[k.length];
		int cacheSize = 0;

		ArrayList<FlowIdWithCount> outputFlowsList = new ArrayList<FlowIdWithCount>();
		ArrayList<FlowIdWithCount> dumpOutputFlowsList = new ArrayList<FlowIdWithCount>();

		double varianceFP[] = new double[k.length];
		double varianceFN[] = new double[k.length];
		double varianceRep[] = new double[k.length];

		// given input, so ideal order of heavy hitters
		FlowIdWithCount[] inputFlowArray = new FlowIdWithCount[flowAggWithSizes.size()];
		inputFlowArray = flowAggWithSizes.toArray(inputFlowArray);
		Arrays.sort(inputFlowArray);

		for (int k_index = 0; k_index < k.length; k_index++){

			// first k in inputStream are expected hh - fix which heavy hitters you look at for cdf of competitors
			expectedHH = new HashSet<String>();
			for (int i = 0; i < k[k_index]; i++){
				expectedHH.add(inputFlowArray[i].flowid);
			}
			expectedSize[k_index] = expectedHH.size();
		}


		HashMap<String, Long> observedHH;
		HashMap<String, Long> observedHHfromDump;
		for (int t = 0; t < numberOfTrials; t++){
			outputFlowsList = new ArrayList<FlowIdWithCount>();
				//Collections.shuffle(inputPacketStream);
						//cacheSize[k_index] = (int) (1.0/threshold) + 20;/* (1.25*expectedSize[k_index]);*/
			cacheSize = totalMemory/2;
			//System.out.println("cacheSize" + cacheSize);
			// track the unique lost flows
			CountMinFlowIdWithCache cmsketch = null;
			SampleAndHoldFlowId flowMemoryFromSampling = null;
			UnivMon univmon = null;
			//double kCount = lostPacketStream.size() * k[k_index];
			double samplingProb = totalMemory/(double) inputPacketStream.size(); /*(1 - Math.pow(1 - accuracy, 1/kCount))*/

			if (type == SummaryStructureType.SampleAndHold)
				flowMemoryFromSampling = new SampleAndHoldFlowId(totalMemory, type, inputPacketStream.size(), samplingProb);
			/*else if (type == SummaryStructureType.UnivMon)
				univmon = new UnivMon(totalMemory, type, inputPacketStream.size(), k[k_index], thres[k_index]);*/
			else
				cmsketch = new CountMinFlowIdWithCache((totalMemory - cacheSize)*15/2, type, inputPacketStream.size(), D, cacheSize, thresh, k[k.length - 1]);

			for (Packet p : inputPacketStream){
				if (type == SummaryStructureType.SampleAndHold)
					flowMemoryFromSampling.processData(p.getFlowId());
				/*else if (type == SummaryStructureType.UnivMon)
					univmon.processData(p.getFlowId());*/
				else
					cmsketch.processData(p.getFlowId(), thr_totalPackets);
			}
			
			// get the heavy hitters from the sample and hold flow memory
			if (type == SummaryStructureType.SampleAndHold){
				cacheSize = flowMemoryFromSampling.getBuckets().size();
				for (String f : flowMemoryFromSampling.getBuckets().keySet()){
					outputFlowsList.add(new FlowIdWithCount(f, flowMemoryFromSampling.getBuckets().get(f)));
				}
			}
			/*else if (type == SummaryStructureType.UnivMon){
				for (long f : univmon.getHeavyHitters().keySet())
					outputFlowsList.add(new FlowIdWithCount(f, univmon.getHeavyHitters().get(f)));
			}*/
			else if (type == SummaryStructureType.CountMinWithHeap){
				for (String f : cmsketch.getHeavyHitters().keySet())
					outputFlowsList.add(new FlowIdWithCount(f, cmsketch.getHeavyHitters().get(f)));
			}
			else { 
				//get the heavy hitters and clean them up
				//observedHH = cmsketch.getHeavyHitters();
				//ArrayList<String> flowsToRemove = new ArrayList<String>();
				for (String flowid : cmsketch.getHeavyHitters().keySet()) {
					//if (type == SummaryStructureType.CountMinCacheNoKeys && cmsketch.getHeavyHitters().get(flowid) > thres[k_index]*inputPacketStream.size())
						outputFlowsList.add(new FlowIdWithCount(flowid, cmsketch.getHeavyHitters().get(flowid)));
						//observedHH.put(flowid, cmsketch.getHeavyHitters().get(flowid));
					/*if (type == SummaryStructureType.CountMinCacheWithKeys && observedHH.get(flowid) <= thres[k_index]*inputPacketStream.size()){
						// check if the cache has a mre updated value that would account for this particular flowid being a hh
						// you would technically hash on this flowid and look up that index -- eliminated that part
						if (!observedHHfromDump.containsKey(flowid))
							flowsToRemove.add(flowid);
						else if (observedHHfromDump.get(flowid) <= thres[k_index]*inputPacketStream.size())
							flowsToRemove.add(flowid);
					}*/
				}
				//for (String flowid : flowsToRemove)
				//	observedHH.remove(flowid);
				//System.out.println("after cleaning: " + observedHH.size());
			}
			FlowIdWithCount[] outputFlowBuckets;
			// observed flows in sorted order so that we can pick the hh as the top k
			if (type == SummaryStructureType.CountMinCacheWithKeys)
				outputFlowBuckets = cmsketch.getCache();
			else {
				outputFlowBuckets = new FlowIdWithCount[outputFlowsList.size()];
				outputFlowBuckets = outputFlowsList.toArray(outputFlowBuckets);
			}
			Arrays.sort(outputFlowBuckets);
		

			for (int k_index = 0; k_index < k.length; k_index++){
				// find the expected HH in the idealistic 100% accuracy case
				expectedHH = new HashSet<String>();
				observedHHfromDump = new HashMap<String, Long>();
				observedHH = new HashMap<String, Long>();
				

				// first k in inputStream are expected hh - fix which heavy hitters you look at for cdf of competitors
				expectedHH = new HashSet<String>();
				for (int i = 0; i < k[k_index]; i++){
					expectedHH.add(inputFlowArray[i].flowid);
				}
				expectedSize[k_index] = expectedHH.size();

				// FIX THIS
				double threshold = 0.000;
				// FIX

				observedHH = new HashMap<String, Long>();
				for (int i = 0; i < outputFlowBuckets.length && i < k[k_index]; i++){
					observedHH.put(outputFlowBuckets[i].flowid, outputFlowBuckets[i].count);
				}
				observedSize[k_index] = observedHH.size();

				// get the heavy hitters from a dump of the cache and track them separately
				// observed flows in sorted order so that we can pick the hh as the top k
				FlowIdWithCount[] dumpOutputFlowBuckets = new FlowIdWithCount[dumpOutputFlowsList.size()];
				dumpOutputFlowBuckets = dumpOutputFlowsList.toArray(dumpOutputFlowBuckets);
				Arrays.sort(dumpOutputFlowBuckets);

				/*if (type == SummaryStructureType.CountMinCacheWithKeys){
					for (int i = 0; i < k[k_index]; i++){
					observedHH.put(dumpOutputFlowBuckets[i].flowid, dumpOutputFlowBuckets[i].count);
					}
				}
				observedSizeFromDump[k_index] = observedHHfromDump.size();*/


				// get occupancy and number of notifications to the controller
				if (type != SummaryStructureType.SampleAndHold && type != SummaryStructureType.UnivMon) {
					occupancy[k_index] += (float) cmsketch.getSketch().getOccupancy();
					controllerReportCount[k_index] += (float) cmsketch.getControllerReports();
				}
				else if (type == SummaryStructureType.UnivMon){
					float curOccupancy = 0;
					for (Sketch s : univmon.getSketches())
						curOccupancy += (float) s.getOccupancy();
					curOccupancy /= univmon.getSketches().length;
					occupancy[k_index] += curOccupancy;
				}
			
				// compare against expected hh
				int bigLoserPacketsLost = 0;
				int flag = 0;
				double deviation = 0;
				double denominator = 0;

				int curFalseNegs = 0;
				long curBLCount = 0;
				for (String flowid : expectedHH){
					long repSize = 0;
					if (!observedHH.containsKey(flowid)){
						numberOfFalseNegatives[k_index]++;
						curFalseNegs++;
					}
					else{
						hhPacketReported[k_index] += flowSizes.get(flowid);
						curBLCount += flowSizes.get(flowid);
						repSize = observedHH.get(flowid);
					}

					if (!observedHHfromDump.containsKey(flowid)){
						numberOfFalseNegativesinDump[k_index]++;
					}
					else
						hhPacketReportedinDump[k_index] += flowSizes.get(flowid);
					
					hhPacketCount[k_index] += flowSizes.get(flowid);
					System.err.println(flowSizes.get(flowid) + "," + repSize);
				}
				varianceFN[k_index] += curFalseNegs*curFalseNegs;
				varianceRep[k_index] += curBLCount*curBLCount;

				int curFalsePos = 0;
				for (String flowid : observedHH.keySet()){
					if (!expectedHH.contains(flowid)){
						numberOfFalsePositives[k_index]++;
						curFalsePos++;
					}
					/*if (flowSizes.get(flowid) == null)
						System.out.println(flowid);*/

					//deviation += Math.abs(observedHH.get(flowid) - flowSizes.get(flowid));
					//denominator += flowSizes.get(flowid);
				}
				varianceFP[k_index] += curFalsePos*curFalsePos;

				cumDeviation[k_index] += deviation/denominator;

				deviation = 0;
				denominator = 0;
				for (String flowid : observedHHfromDump.keySet()){
					if (!expectedHH.contains(flowid)){
						numberOfFalsePositivesinDump[k_index]++;
					}
					/*if (flowSizes.get(flowid) == null)
						System.out.println(flowid);

					deviation += Math.abs(observedHHfromDump.get(flowid) - flowSizes.get(flowid));*/
					//denominator += flowSizes.get(flowid);
				}

				if (denominator != 0) 
					cumDeviationinDump[k_index] += deviation/denominator;				
			}
			//System.out.println("D =" + D);
		}

		/*for (int k_index = 0; k_index < k.length; k_index++){
			varianceFP[k_index] = (varianceFP[k_index] - ((numberOfFalsePositives[k_index]*numberOfFalsePositives[k_index])/(double) numberOfTrials/observedSize[k_index]/observedSize[k_index]))/(numberOfTrials - 1);
			varianceFN[k_index] = (varianceFN[k_index] - ((numberOfFalseNegatives[k_index]*numberOfFalsePositives[k_index])/(double) numberOfTrials/expectedSize[k_index]/expectedSize[k_index]))/(numberOfTrials - 1);
			varianceRep[k_index] = (varianceRep[k_index] - ((hhPacketReported[k_index]*hhPacketReported[k_index])/(double) numberOfTrials/hhPacketCount[k_index]/hhPacketCount[k_index]))/(numberOfTrials - 1);
		}*/
		
		for (int k_index = 0; k_index < k.length; k_index++){
			System.out.print(totalMemory + "," + cacheSize + "," + k[k_index] + "," + D + ",");
			System.out.print((double) numberOfFalsePositives[k_index]/numberOfTrials/(flowSizes.size() - k[k_index]) + ",");
			System.out.print((double) numberOfFalseNegatives[k_index]/numberOfTrials/expectedSize[k_index] + ",");
			System.out.print(expectedSize[k_index] + "," + observedSize[k_index] + "," + (double) hhPacketReported[k_index]/hhPacketCount[k_index]);
			System.out.print("," + cumDeviation[k_index]/numberOfTrials + "," + occupancy[k_index]/numberOfTrials + "," + thr_totalPackets + "," + controllerReportCount[k_index]/numberOfTrials + ",");
			//System.out.print((double) Math.sqrt(varianceFP[k_index]) + "," + Math.sqrt(varianceFN[k_index]) + "," + Math.sqrt(varianceRep[k_index]) + ",");

			/*if (type == SummaryStructureType.CountMinCacheWithKeys){
				System.out.print((double) numberOfFalsePositivesinDump[k_index]/numberOfTrials/observedSizeFromDump[k_index] + ",");
				System.out.print((double) numberOfFalseNegativesinDump[k_index]/numberOfTrials/expectedSize[k_index] + ",");
				System.out.print(expectedSize[k_index] + "," + observedSizeFromDump[k_index] + "," + (double) hhPacketReportedinDump[k_index]/hhPacketCount[k_index]);
				System.out.print("," + cumDeviationinDump[k_index]/numberOfTrials + ",");
			}*/
			
			System.out.println();
		}
	}

	public static void main(String[] args){
		long start = System.currentTimeMillis();
		ArrayList<Packet> inputPacketStream;
		flowAggWithSizes = new ArrayList<FlowIdWithCount>(); // input stream in a convenient format
		flowSizes = new HashMap<String, Integer>();
		if(args[0].contains("caida") || args[0].contains("Caida"))
			inputPacketStream = FlowDataParser.parseCAIDAPacketData(args[0]);
		else
			inputPacketStream = FlowDataParser.parsePacketData(args[0]);

		double[] thetaValues = {807.7544426, 1218.026797, 1412.429379, 1626.01626, 1904.761905, 2178.649237, 2427.184466, 2702.702703, 2898.550725, 3174.603175, 3472.222222, 3846.153846, 4201.680672, 4484.304933, 4901.960784};		

		// read the flows to be lost from a file mentioned in the command line and create a new stream with that flow lost
		int totalPacketsLost = 0;
		File file = new File(args[1]);
		try
		{
			Scanner scanner = new Scanner(file);
			String line;
			//int linenumber = 0;
			String[] fields = new String[24];
			while (scanner.hasNextLine())
			{
				line = scanner.nextLine();
				fields = line.split(",");
				flowSizes.put(fields[0], Integer.parseInt(fields[1]));
				totalPacketsLost+= Integer.parseInt(fields[1]);
				FlowIdWithCount f = new FlowIdWithCount(fields[0], Integer.parseInt(fields[1]));
				flowAggWithSizes.add(f);
			}
			scanner.close();
		}
		catch (FileNotFoundException e)
		{
			System.err.format("Exception occurred trying to read '%s'.", args[1]);
			e.printStackTrace();
			return;
		}

		//final int tableSize[] = {30, 75, 150, 300, 500, 900, 1200, 1500, 2000};
		//final int tableSize[] = {/*100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1200, 1400, 1600, 1800, 1024, 2048/*, 4096, 8192*/};
		//final int k[] = {50, 100, 150, 200, 250, 300, 350, 400, 450, 500, 550, 600, 650, 700, 750};
		//final int k[] = {/*, 50, 100, 150, 200, 300/*150, 300, 450, 600*/};
		//final int k[] = {100, 200, 400, 800, 1600};
		
		//final int k[] = {5040, 2520, 1260, 630, 315, 155};
		//final int tableSize[] = {2520/*, 5040, 7560/*, 10080*/}; // LCM of the first 12 integers
		//final int tableSize[] = {1200/*, 600, 900, 1200, 1500, 1800, 2100, 2400, 2700, 3000, 3300, 3600, 3900, 4200, 4500*/};
		//final int tableSize[] = {12500, 25000, 50000, 100000, 200000, 400000, 800000};
		//final int tableSize[] = {200, 400, 600, 800, 1000, 1200, 1400, 1600, 1800, 2000};
		//final int tableSize[] = {64};
		
		/* d measurements 
		final int k[] = {210, 420, 630, 168, 336, 504};
		final int tableSize[] = {840, 1680, 2520, 4200};*/

		/* m measurements */
		final int k[] = {160 /*30, 60, 120, 150, 240, 300*/};

		/*int[] k = new int[150];
		for (int i = 0; i < 150; i++)
			k[i] = i + 1;*/

		//final int tableSize[] = {100/*, 150, 200, 250, 300, 350, 400, 500, 600, 700, 800, 900, 1000, 1100, 1200};
		final int tableSize[] = {/*300, 600, 900, 1200,*/ 1600/*, 3000, 4500/*, 1800, 2100, 2400, 3000, 3600, 4200, 4500, 4800*/};
		//final int tableSize[] = {4800, 5100, 5400, 5700, 6000, 6300};
		//final int tableSize[] = {1200};
		//final int tableSize[] = {300, 900, 1500, 2100, 3000, 6000, 9000, 12000};

		if (args[2].equals("runTrial"))	{
			System.out.print("tableSize" + "," + "k" + "," + "D," + "FalsePositive %" + "," + "False Negative %" + "," + "expected number,");
			System.out.print("reported number, hhReportedFraction, deviation, table occupancy, duplicates, fraction missing in table, cumProblematicEvictionFraction");
			System.out.println(" theoretical Prob, P(within 1 stddev), P(within 2 stddev), numUnderEstimated, underEstimateAmount, StddevFP, StddevFN, StddevRep");
			for (int tableSize_index = 0; tableSize_index < tableSize.length; tableSize_index++) { 
				for (int D = 4; D <= 4; D++){
				//for (int D = 2; D <= 12; D++){
					if (D == 11 || D == 13)
						continue;

		 			// run the loss identification trials for the appropriate heuristic
					if (args[3].contains("Basic"))
						runTopKIdentificationTrials(SummaryStructureType.BasicHeuristic, inputPacketStream, k, tableSize[tableSize_index], D, thetaValues);
					else if (args[3].contains("Multi"))
						runTopKIdentificationTrials(SummaryStructureType.RollingMinWihoutCoalescense, inputPacketStream, k, tableSize[tableSize_index], D, thetaValues);
					else if (args[3].contains("Single"))
						runTopKIdentificationTrials(SummaryStructureType.RollingMinSingleLookup , inputPacketStream, k, tableSize[tableSize_index], D, thetaValues);
					else if (args[3].contains("coalesce"))
						runTopKIdentificationTrials(SummaryStructureType.RollingMinWithBloomFilter , inputPacketStream, k, tableSize[tableSize_index], D, thetaValues);
					else if (args[3].contains("SpaceSaving"))
						runTopKIdentificationTrials(SummaryStructureType.OverallMinReplacement, inputPacketStream, k, tableSize[tableSize_index], D, thetaValues);
				}
			}
		}
		else if (args[2].equals("PerThreshold")){
			System.out.print("totalMemory," + "cacheSize," + "k," + "D," + "FalsePositive %," + "False Negative %," + "expected number, reported number, hhReportedFraction, deviation, table occupancy, thr_totalPackets, Controlleer Report Count");
			System.out.print("StddevFP, StddevFN, StddevRep");
			if (args[3].contains("Keys"))
				System.out.print("FalsePositiveinDump %," + "False Negativ in Dump %," + "expected number, reported number in dump, hhReportedFraction in dump, deviation in dump,");
			System.out.println();

			for (int tableSize_index = 0; tableSize_index < tableSize.length; tableSize_index++) {
				if (args[3].contains("SampleAndHold")) {
					runTrialsPerK(SummaryStructureType.SampleAndHold, inputPacketStream, k, tableSize[tableSize_index], 0, 0);
					continue;
				}
				else if (args[3].contains("UnivMon")){
					runTrialsPerK(SummaryStructureType.UnivMon, inputPacketStream, k, tableSize[tableSize_index], 0, 0);
				}
				else if (args[3].contains("CM")){
					runTrialsPerK(SummaryStructureType.CountMinCacheWithKeys, inputPacketStream, k, tableSize[tableSize_index], 5, 0);
				}
			}
		}
		System.out.println((System.currentTimeMillis() - start)/1000);
	}
}