# tutorials
P4 language tutorials

# iw15

Independent research project directed at identifying the flows that contribute to majority of packets between two points on a given network

All the evaluations used data from the CAIDA Traces from  https://data.caida.org/datasets/passive-2016/equinix-chicago/20160218-130000.UTC/ and datacenter traces from http://pages.cs.wisc.edu/~tbenson/IMC10_Data.html. These were converted to .csv files with the srcip, dstip, protocol, srcport and dstport using tshark. They were then split them into chunks of 10M packets each after which `TopKIdentifierFlowID` was run on them. `TopKIdentifierFlowID` also needs a file with the actual flow size, which can be obtained by running FindFlowSize. This groups the packets from the csv by 5-tuple giving you a 5-tuple flowid and the associated count for a flow.

The output from `TopKIdentifierFlowID` itself involves rows of data one corresponding to each experiment (a run of HashPipe on that particular trace with those settings) and prints out the tablesize (number of flowid, counter pairs), K value, D (number of stages), false negative rate, false positive rate, weighted versions of the same (by size fo the flows), number of duplicates in the table, reported fraction of heavy hitters, number of reported heavy hitters, average deviation from the actual size across flows and so on. The header identifies the precise metrics reported. The variable "FlowSizes" has the map between flowid and their actual sizes.

`runTopKIdentificationTrials` function runs the experiments for you and runs only once across all the K values we want (for HashPipe and all of the incremental steps before that). To run this, you want to use the SummaryStructureType.RollingMinSingleLookup. So, you would want to run `TopKIdentifierFlowID` with the arguments flowSize file (actual flowsize per flow), the tracefile, "runTrial" and "Single" as its arguments to run HashPipe as per the algorithm in the paper. 

`runTrialsPerK` on the other hand, runs it once for every K and this is relevant for Sample and Hold and the Count-Min Sketch. So, you would want to run `TopKIdentifierFlowID` with the arguments flowSize file (actual flowsize per flow), the tracefile, "PerThreshold" and "CM" or "SampleAndHold" as its arguments to run CM sketch or Sample And Hold respectively.
