#!/bin/bash

CONF_DIR=../test/squall/confs/create_confs/generated/
RESULT_DIR=../bin/extract_results/
STORM_OUTPUT_PATH=$RESULT_DIR/data/
SNAPSHOT_DIR=$RESULT_DIR/snapshots

# 1. deleting old data
rm -rf ${STORM_OUTPUT_PATH}*

# 2. Uploading storm.yaml for profiling
./profiling.sh START

# 3. for each generated file, run it and wait until it is terminated
for config in ${CONF_DIR}* ; do
        #wait until resources are freed from previous execution, the same as TOPOLOGY_MESSAGE_TIMEOUT_SECS
        #explained in Killing topology section in https://github.com/nathanmarz/storm/wiki/Running-topologies-on-a-production-cluster

	confname=${config##*/}
	./squall_cluster.sh $CONF_DIR/$confname

	#waiting for topology to finish is now in squall_cluster.sh

        #getting snapshots
	snapshot_path=$SNAPSHOT_DIR/$confname
        mkdir -p $snapshot_path
	rm -rf $snapshot_path/*
	./grasp_snapshots.sh $snapshot_path

        #deleting snapshots from cluster, so that the following snapshots are not spoiled
        ./delete_snapshots.sh
done

# 4. grasp output
./grasp_output.sh $STORM_OUTPUT_PATH

# 5. Uploading storm.yaml for profiling
./profiling.sh END

# 6. Extracting timing information, we might want to check whether execution time prolonged too much, so that profiling results does not make sense anymore
CURR_DIR=`pwd`
cd $RESULT_DIR
rake -f extract_time.rb
cd $CURR_DIR