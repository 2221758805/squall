package plan_runner.utilities;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;

import backtype.storm.Config;
import backtype.storm.utils.Utils;

public class SystemParameters {
	private static Logger LOG = Logger.getLogger(SystemParameters.class);

	// the content of ~/.storm/storm.yaml
	public static final int DEFAULT_NUM_ACKERS = 0;
	public static final int CLUSTER_SIZE = 220;

	// used in StormDataSource, for both local and clustered mode
	public static final long EOF_TIMEOUT_MILLIS = 1000;

	// Period between figuring out code is finished and
	// killing the execution
	// In Local Mode needed more time because we also need to compare results
	// (LocalMergeResults)
	public static final long LOCAL_SLEEP_BEFORE_KILL_MILLIS = 8000;
	public static final long CLUSTER_SLEEP_BEFORE_KILL_MILLIS = 2000;

	// default port, should not be changed unless some other application took
	// this port
	public static final int NIMBUS_THRIFT_PORT = 6627;

	// ***************************SendAndWait
	// parameters********************************

	// how much space average tuple takes to be stored
	public static final int TUPLE_SIZE_BYTES = 50;
	// the factor we multiply predicted storage
	public static final int JAVA_OVERHEAD = 7;
	// ***************************SendAndWait
	// parameters********************************

	// DO NOT MODIFY OR MOVE ANYWHERE ELSE. THESE ARE NOT CONFIGURATION
	// VARIABLES
	public static final String DATA_STREAM = Utils.DEFAULT_STREAM_ID; /* "default" */
	public static final String EOF_STREAM = "2";
	public static final String DUMP_RESULTS_STREAM = "3";

	public static final String LAST_ACK = "LAST_ACK";
	public static final String EOF = "EOF";
	public static final String DUMP_RESULTS = "DumpResults";

	public static final long BYTES_IN_MB = 1024 * 1024;

	public static final String MANUAL_BATCH_HASH_DELIMITER = "~";
	public static final String MANUAL_BATCH_TUPLE_DELIMITER = "`";
	public static final String STORE_TIMESTAMP_DELIMITER = "@";

	// DYNAMIC THETA JOIN STREAM IDS..
	public static final String ThetaClockStream = "7";

	public static final String ThetaAggregatedCounts = "8";
	public static final String ThetaSynchronizerSignal = "9"; // Signal and
	// might contain
	// new mapping
	// along side.
	// (from the
	// Synchronizer
	// to the
	// reshuffler)
	public static final String ThetaReshufflerSignal = "10"; // Signal and might
	// contain new
	// mapping along
	// side. (from
	// the
	// Reshuffler to
	// the joiner)
	public static final String ThetaJoinerAcks = "11"; // Acks (from the joiner
	// to the mapping
	// assigner) //Acks can
	// be for 1)Change map
	// or 2)Data migration
	// ended.
	public static final String ThetaDataMigrationJoinerToReshuffler = "12"; // from
	// joiner
	// -->
	// reshuffler
	public static final String ThetaDataMigrationReshufflerToJoiner = "13"; // from
	// reshuffler
	// -->
	// joiner
	public static final String ThetaDataReshufflerToJoiner = "14"; // from
	// reshuffler
	// -->
	// joiner
	// (ordinary
	// tuples)
	public static final String ThetaReshufflerStatus = "15"; // Status Signal.
	// (from the
	// reshuffler to
	// the
	// synchronizer)

	// DYNAMIC THETA JOIN STREAM SIGNALS..
	public static final String ThetaReshufflerSatusOK = "OK"; // Status ok

	public static final String ThetaReshufflerSatusNOTOK = "NOTOK"; // Status
	// not ok
	public static final String ThetaSignalCheck = "Check"; // Check --> Check if

	// it is possible to
	// change Mapping
	public static final String ThetaSignalRefrain = "Refrain"; // Refrain -->
	// Refrain from
	// changing
	// Mapping
	public static final String ThetaSignalStop = "Stop"; // Stop --> Stop and
	// Send new Mapping
	public static final String ThetaSignalProceed = "Proceed"; // Proceed -->
	// Data
	// migration
	// phase entered
	// public static final String ThetaSignalDataMigrationEndedInitiated =
	// "D-M-E-I"; //Initiate Data migration-End
	public static final String ThetaSignalDataMigrationEnded = "D-M-E"; // End --> Data migration
																		// ended
	public static final String ThetaJoinerDataMigrationEOF = "D-M-E-E-O-F"; // End

	// -->
	// Data
	// migration
	// ended
	public static final String ThetaJoinerMigrationSignal = "T-J-M-S!"; // End
	// -->
	// Data
	// migration
	// ended
	// for acking from the Dynamic JOINER
	// public static final String ThetaAckDataMigrationEndedInititated =
	// "ACK-0"; //
	public static final String ThetaAckDataMigrationEnded = "ACK-1"; //

	public static final String ThetaAckNewMappingReceived = "ACK-2"; //
	// printing statistics for creating graphs
	public static final int INITIAL_PRINT = 0;

	public static final int INPUT_PRINT = 1;
	public static final int OUTPUT_PRINT = 2;
	public static final int FINAL_PRINT = 3;

	public static Map<String, String> fileToMap(String propertiesFile) {
		final Map map = new HashMap<String, String>();

		try {
			String line;
			final BufferedReader reader = new BufferedReader(new FileReader(
					new File(propertiesFile)));
			while ((line = reader.readLine()) != null) {

				// remove leading and trailing whitespaces
				line = line.trim();
				if (line.length() != 0 && line.charAt(0) != '\n' // empty line
						&& line.charAt(0) != '\r' // empty line
						&& line.charAt(0) != '#') { // commented line

					// get key and value as first and second parameter
					// delimiter is one or more whitespace characters (space,
					// tabs, ...)
					final String args[] = line.split("\\s+");
					if (args.length == 2) {
						// only in that case you put something in map
						final String key = args[0];
						final String value = args[1];
						map.put(key, value);
					}
				}
			}
			reader.close();
		} catch (final Exception e) {
			final String error = MyUtilities.getStackTrace(e);
			LOG.info(error);
			throw new RuntimeException(error);
		}
		return map;
	}

	public static Config fileToStormConfig(String propertiesFile) {
		final Map map = fileToMap(propertiesFile);
		return mapToStormConfig(map);
	}

	public static boolean getBoolean(Map conf, String key) {
		final String result = getString(conf, key);
		if (result.equalsIgnoreCase("TRUE"))
			return true;
		else if (result.equalsIgnoreCase("FALSE"))
			return false;
		else
			throw new RuntimeException("Invalid Boolean value");
	}

	public static double getDouble(Map conf, String key) {
		final String result = getString(conf, key);
		return Double.parseDouble(result);
	}

	public static int getInt(Map conf, String key) {
		final String result = getString(conf, key);
		return Integer.parseInt(result);
	}

	public static String getString(Map conf, String key) {
		final String result = (String) conf.get(key);
		if (result == null || result.equals(""))
			LOG.info("null in getString method for key " + key);
		return result;
	}

	public static boolean isExisting(Map conf, String key) {
		final String result = (String) conf.get(key);
		return result != null;
	}

	public static Config mapToStormConfig(Map map) {
		final Config conf = new Config();
		conf.putAll(map);
		setStormVariables(conf);
		return conf;
	}

	public static void putInMap(Map conf, String key, Object value) {
		putInMap(conf, key, String.valueOf(value));
	}

	public static void putInMap(Map conf, String key, String value) {
		conf.put(key, value);
	}

	/*
	 * Decided not to set it here, because we have to change many Squall config
	 * files this way the only change is in storm.yaml.
	 * Set variables for which there is no set method in Config This overrides
	 * ~/.storm/storm.yaml Thus, we don't need to change storm.yaml when
	 * submission to other master is required. Still, these properties stay in
	 * storm.yaml, so when storm.yaml is uploaded, all the worker nodes are
	 * aware of the appropriate master node. This is important for
	 * multiple-instance Storm on cluster.
	 */
	private static void setStormVariables(Config conf) {

	}

}
