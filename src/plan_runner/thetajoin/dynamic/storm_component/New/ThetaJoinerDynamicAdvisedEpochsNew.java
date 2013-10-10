/**
 *
 * @author El Seidy
 * This Class is responsible for doing the actual Theta-Join.
 */
package plan_runner.thetajoin.dynamic.storm_component.New;

import gnu.trove.list.array.TIntArrayList;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.apache.log4j.Logger;

import plan_runner.components.ComponentProperties;
import plan_runner.operators.AggregateOperator;
import plan_runner.operators.ChainOperator;
import plan_runner.operators.Operator;
import plan_runner.predicates.ComparisonPredicate;
import plan_runner.predicates.Predicate;
import plan_runner.storage.BitVector;
import plan_runner.storage.TupleStorage;
import plan_runner.storm_components.InterchangingComponent;
import plan_runner.storm_components.StormBoltComponent;
import plan_runner.storm_components.StormComponent;
import plan_runner.storm_components.StormEmitter;
import plan_runner.storm_components.synchronization.TopologyKiller;
import plan_runner.thetajoin.dynamic.advisor.Action;
import plan_runner.thetajoin.dynamic.advisor.Discard;
import plan_runner.thetajoin.indexes.Index;
import plan_runner.utilities.MyUtilities;
import plan_runner.utilities.PeriodicAggBatchSend;
import plan_runner.utilities.SystemParameters;
import plan_runner.utilities.statistics.StatisticsUtilities;
import plan_runner.visitors.PredicateCreateIndexesVisitor;
import plan_runner.visitors.PredicateUpdateIndexesVisitor;
import backtype.storm.Config;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.InputDeclarer;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;

public class ThetaJoinerDynamicAdvisedEpochsNew extends StormBoltComponent {

	// firstRelation=1 secondRelation=2
	public static int identifyDim(int prevRow, int prevCol, int currRow, int currCol,
			boolean isDiscarding) {
		final int[] preDim = new int[] { prevRow, prevCol };
		final int[] currDim = new int[] { currRow, currCol };

		if (isDiscarding) { // smaller --> bigger
			if (preDim[0] < currDim[0])
				return 1;
			else if (preDim[1] < currDim[1])
				return 2;
		} else if (preDim[0] > currDim[0])
			return 1;
		else if (preDim[1] > currDim[1])
			return 2;

		return -1;
	}

	private long numberOfTuplesMemory = 0;
	private static final long serialVersionUID = 1L;

	private static Logger LOG = Logger.getLogger(ThetaJoinerDynamicAdvisedEpochsNew.class);
	private TupleStorage _firstRelationStorage, _secondRelationStorage,
			_firstTaggedRelationStorage, _secondTaggedRelationStorage;
	private List<Index> _firstRelationIndexes, _secondRelationIndexes, _firstTaggedRelationIndexes,
			_secondTaggedRelationIndexes;
	private TupleStorage _firstRelationStorageNewEpoch, _secondRelationStorageNewEpoch,
			_firstTaggedRelationStorageNewEpoch, _secondTaggedRelationStorageNewEpoch;

	private List<Index> _firstRelationIndexesNewEpoch, _secondRelationIndexesNewEpoch,
			_firstTaggedRelationIndexesNewEpoch, _secondTaggedRelationIndexesNewEpoch;
	private String _firstEmitterIndex, _secondEmitterIndex;
	private long _numSentTuples = 0;

	private final ChainOperator _operatorChain;
	private final Predicate _joinPredicate;
	private List<Integer> _operatorForIndexes;
	private List<Object> _typeOfValueIndexed;

	private boolean _existIndexes = false;
	private int _currentNumberOfAckedReshufflerWorkersTasks;
	private int _currentNumberOfFinalAckedParents;

	private int _thisTaskIDindex;
	private final int _parallelism;
	private transient InputDeclarer _currentBolt;
	private Action _currentAction = null;
	private int _renamedIDindex;
	private long _receivedTuplesR = 0, _receivedTuplesS = 0;

	private long _receivedTaggedTuplesR = 0, _receivedTaggedTuplesS = 0;
	private TupleStorage _migratingRelationStorage, _migratingTaggedRelationStorage;
	private int _migratingRelationCursor, _migratingTaggedRelationCursor,
			_migratingTaggedRelationSize;
	private String _migratingRelationIndex;
	private boolean _isMigratingRelationStorage, _isMigratingTaggedRelationStorage;
	private int migrationBufferSize = 100;
	private int _currentEpochNumber = 0;
	private int indexOfMigrating = -1;
	private final ThetaReshufflerAdvisedEpochsNew _reshuffler;
	private int _AdvisorIndex;

	private final int _numParentTasks;
	// for statistics
	SimpleDateFormat _format = new SimpleDateFormat("EEE MMM d HH:mm:ss zzz yyyy");

	private final StatisticsUtilities _statsUtils;
	// for batch sending
	private final Semaphore _semAgg = new Semaphore(1, true);
	private boolean _firstTime = true;
	private PeriodicAggBatchSend _periodicAggBatch;

	private final long _batchOutputMillis;

	public ThetaJoinerDynamicAdvisedEpochsNew(StormEmitter firstEmitter,
			StormEmitter secondEmitter, ComponentProperties cp, List<String> allCompNames,
			Predicate joinPredicate, int hierarchyPosition, TopologyBuilder builder,
			TopologyKiller killer, Config conf, ThetaReshufflerAdvisedEpochsNew reshuffler,
			String initialDim) {

		super(cp, allCompNames, hierarchyPosition, conf);
		_reshuffler = reshuffler;
		_numParentTasks = reshuffler.getReshufflerParallelism();
		_currentNumberOfFinalAckedParents = reshuffler.getReshufflerParallelism();

		if (SystemParameters.isExisting(conf, "DIP_MIGRATION_WAVE"))
			migrationBufferSize = SystemParameters.getInt(conf, "DIP_MIGRATION_WAVE");

		_statsUtils = new StatisticsUtilities(conf, LOG);
		_batchOutputMillis = cp.getBatchOutputMillis();

		if (secondEmitter == null) { // then first has to be of type
			// Interchanging Emitter
			_firstEmitterIndex = String.valueOf(allCompNames.indexOf(firstEmitter.getName().split(
					"-")[0]));
			_secondEmitterIndex = String.valueOf(allCompNames.indexOf(firstEmitter.getName().split(
					"-")[1]));
		} else {
			_firstEmitterIndex = String.valueOf(allCompNames.indexOf(firstEmitter.getName()));
			_secondEmitterIndex = String.valueOf(allCompNames.indexOf(secondEmitter.getName()));
		}

		_parallelism = SystemParameters.getInt(conf, cp.getName() + "_PAR");
		_operatorChain = cp.getChainOperator();

		_joinPredicate = joinPredicate;
		_currentBolt = builder.setBolt(cp.getName(), this, _parallelism);

		if (hierarchyPosition == FINAL_COMPONENT && (!MyUtilities.isAckEveryTuple(conf)))
			killer.registerComponent(this, _parallelism);
		if (cp.getPrintOut() && _operatorChain.isBlocking())
			_currentBolt.allGrouping(killer.getID(), SystemParameters.DUMP_RESULTS_STREAM);
		_firstRelationStorage = new TupleStorage();
		_firstRelationStorageNewEpoch = new TupleStorage();
		_secondRelationStorage = new TupleStorage();
		_secondRelationStorageNewEpoch = new TupleStorage();
		_firstTaggedRelationStorage = new TupleStorage();
		_firstTaggedRelationStorageNewEpoch = new TupleStorage();
		_secondTaggedRelationStorage = new TupleStorage();
		_secondTaggedRelationStorageNewEpoch = new TupleStorage();
		if (_joinPredicate != null) {
			createIndexes(1, true, false);
			createIndexes(1, false, false);
			createIndexes(2, true, false);
			createIndexes(2, false, false);
			createIndexes(1, true, true);
			createIndexes(1, false, true);
			createIndexes(2, true, true);
			createIndexes(2, false, true);
			_existIndexes = true;
		} else
			_existIndexes = false;
	}

	private void addTaggedTuples(TupleStorage fromRelation, TupleStorage toRelation,
			List<Index> toRelationindexes, String emitterIndex) {
		for (int i = 0; i < fromRelation.size(); i++) {
			String tupleString = fromRelation.get(i);
			final int row_id = toRelation.insert(tupleString);
			if (_existIndexes) {
				if (MyUtilities.isStoreTimestamp(getConf(), getHierarchyPosition())) {
					// timestamp has to be removed
					final String parts[] = tupleString.split("\\@");
					tupleString = parts[1];
				}
				updateIndexes(emitterIndex, MyUtilities.stringToTuple(tupleString, getConf()),
						toRelationindexes, row_id);
			}
		}
	}

	@Override
	public void aggBatchSend() {
		if (MyUtilities.isAggBatchOutputMode(_batchOutputMillis))
			if (_operatorChain != null) {
				final Operator lastOperator = _operatorChain.getLastOperator();
				if (lastOperator instanceof AggregateOperator) {
					try {
						_semAgg.acquire();
					} catch (final InterruptedException ex) {
					}
					// sending
					final AggregateOperator agg = (AggregateOperator) lastOperator;
					final List<String> tuples = agg.getContent();
					for (final String tuple : tuples)
						tupleSend(MyUtilities.stringToTuple(tuple, getConf()), null, 0);
					// clearing
					agg.clearStorage();
					_semAgg.release();
				}
			}
	}

	private void appendTimestampExisting(Values tplSend, long lineageTimestamp) {
		if (MyUtilities.isCustomTimestampMode(getConf()))
			tplSend.add(lineageTimestamp);
	}

	private void appendTimestampZero(Values tplSend) {
		if (MyUtilities.isCustomTimestampMode(getConf())) {
			final long timestamp = 0;
			tplSend.add(timestamp);
		}
	}

	protected void applyOperatorsAndSend(Tuple stormTupleRcv, List<String> tuple,
			long lineageTimestamp) {
		if (MyUtilities.isAggBatchOutputMode(_batchOutputMillis))
			try {
				_semAgg.acquire();
			} catch (final InterruptedException ex) {
			}
		tuple = _operatorChain.process(tuple);
		if (MyUtilities.isAggBatchOutputMode(_batchOutputMillis))
			_semAgg.release();
		if (tuple == null)
			return;
		_numSentTuples++;
		printStatistics(SystemParameters.OUTPUT_PRINT);
		// printTuple(tuple);
		if (MyUtilities.isSending(getHierarchyPosition(), _batchOutputMillis))
			tupleSend(tuple, stormTupleRcv, 0);
		if (MyUtilities.isPrintLatency(getHierarchyPosition(), getConf()))
			printTupleLatency(_numSentTuples - 1, lineageTimestamp);
	}

	private void clean(TupleStorage emittingStorage, int beginIndex, int endIndex) {
		emittingStorage.remove(beginIndex, endIndex);
	}

	// from IRichBolt
	@Override
	public void cleanup() {
	}

	// RelationsNumber 1 for first and 2 for second.
	private void createIndexes(int relationNumber, boolean isTagged, boolean isNewEpoch) {
		final PredicateCreateIndexesVisitor visitor = new PredicateCreateIndexesVisitor();
		_joinPredicate.accept(visitor);
		if (relationNumber == 1) {
			if (!isNewEpoch) {
				if (!isTagged)
					_firstRelationIndexes = new ArrayList<Index>(visitor._firstRelationIndexes);
				else
					_firstTaggedRelationIndexes = new ArrayList<Index>(
							visitor._firstRelationIndexes);
			} else if (!isTagged)
				_firstRelationIndexesNewEpoch = new ArrayList<Index>(visitor._firstRelationIndexes);
			else
				_firstTaggedRelationIndexesNewEpoch = new ArrayList<Index>(
						visitor._firstRelationIndexes);
		} else if (!isNewEpoch) {
			if (!isTagged)
				_secondRelationIndexes = new ArrayList<Index>(visitor._secondRelationIndexes);
			else
				_secondTaggedRelationIndexes = new ArrayList<Index>(visitor._secondRelationIndexes);
		} else if (!isTagged)
			_secondRelationIndexesNewEpoch = new ArrayList<Index>(visitor._secondRelationIndexes);
		else
			_secondTaggedRelationIndexesNewEpoch = new ArrayList<Index>(
					visitor._secondRelationIndexes);
		if (_operatorForIndexes == null)
			_operatorForIndexes = new ArrayList<Integer>(visitor._operatorForIndexes);
		if (_typeOfValueIndexed == null)
			_typeOfValueIndexed = new ArrayList<Object>(visitor._typeOfValueIndexed);
	}

	/**
	 * The ThetaJoiner can send: 1) Join Result back to the reshuffler. 2) Final
	 * Join Result out to the next stage. 3) Acks back to the
	 * ThetaMappingAssigner
	 */
	@Override
	public void declareOutputFields(OutputFieldsDeclarer declarer) {
		// 1) Final Join Result out to the next stage. (OR if its last stage
		// "FINAL_COMPONENT" send EOF to the topologykiller)
		super.declareOutputFields(declarer);

		// 2) Join Result back to the reshuffler (ThetaDatastream tuples)
		final ArrayList<String> dataStreamFields = new ArrayList<String>();
		dataStreamFields.add(StormComponent.COMP_INDEX);
		dataStreamFields.add(StormComponent.TUPLE);
		dataStreamFields.add(StormComponent.HASH);
		dataStreamFields.add(StormComponent.EPOCH);
		if (MyUtilities.isCustomTimestampMode(getConf()))
			dataStreamFields.add(StormComponent.TIMESTAMP);
		declarer.declareStream(SystemParameters.ThetaDataMigrationJoinerToReshuffler, new Fields(
				dataStreamFields));

		// 3) Acks back to the ThetaMappingAssigner
		declarer.declareStream(SystemParameters.ThetaJoinerAcks, new Fields(StormComponent.MESSAGE));
	}

	private void emitBulk(TupleStorage emittingStorage, String emitterIndex, int beginIndex,
			int endIndex) {
		for (int i = beginIndex; i <= endIndex; i++) {
			String tupleString = emittingStorage.get(i);

			long lineageTimestamp = 0;
			if (MyUtilities.isStoreTimestamp(getConf(), getHierarchyPosition())) {
				// timestamp has to be removed
				final String parts[] = tupleString.split("\\@");
				lineageTimestamp = Long.valueOf(parts[0]);
				tupleString = parts[1];
			}
			final Values tplSend = new Values(emitterIndex, MyUtilities.stringToTuple(tupleString,
					getConf()), "N/A", _currentEpochNumber);
			appendTimestampExisting(tplSend, lineageTimestamp);

			getCollector().emit(SystemParameters.ThetaDataMigrationJoinerToReshuffler, tplSend);
		}
	}

	/**
	 * The ThetaJoinerDynamic can receive: 1) Signal/Mapping (Stop,
	 * Proceed(DataMigration), DataMigrationEnded) from the reshuffler 2)
	 * ThetaDataMigrationReshufflerToJoiner (Migrated) tuples only from the
	 * reshuffler. 3) DatamessageStream (Normal) tuples only from the
	 * reshuffler.
	 */
	@Override
	public void execute(Tuple stormTupleRcv) {

		if (_firstTime && MyUtilities.isAggBatchOutputMode(_batchOutputMillis)) {
			_periodicAggBatch = new PeriodicAggBatchSend(_batchOutputMillis, this);
			_firstTime = false;
		}
		if (receivedDumpSignal(stormTupleRcv)) {
			MyUtilities.dumpSignal(this, stormTupleRcv, getCollector());
			return;
		}

		final String inputStream = stormTupleRcv.getSourceStreamId();
		/**
		 * Case 1) Signal/Mapping from the ThetaReshuffler
		 */
		if (inputStream.equals(SystemParameters.ThetaReshufflerSignal)) {
			final String signal = stormTupleRcv.getStringByField(StormComponent.RESH_SIGNAL);
			final String mapping = (String) stormTupleRcv.getValueByField(StormComponent.MAPPING);
			/*
			 * - If stop 1) Send ack to the mapper when the number (# of
			 * reshuffler processing tasks) of stop signals has been received.
			 * i.e. is flushed! 2) change to the new mapping
			 */
			if (signal.equals(SystemParameters.ThetaSignalStop)) {
				// 1
				_currentNumberOfAckedReshufflerWorkersTasks++;
				if (_numParentTasks == _currentNumberOfAckedReshufflerWorkersTasks) {
					// increment epoch number .. now all tuples of the previous
					// epoch has been received!
					_currentEpochNumber++;
					indexOfMigrating = -1;

					LOG.info(getID() + "AT (STOP): Joiner: " + _renamedIDindex
							+ " has received receivedTuplesR:" + _receivedTuplesR + " TaggedR:"
							+ _receivedTaggedTuplesR + " receivedTuplesS:" + _receivedTuplesS
							+ " TaggedS:" + _receivedTaggedTuplesS);
					_receivedTuplesR = 0;
					_receivedTuplesS = 0;
					_receivedTaggedTuplesR = 0;
					_receivedTaggedTuplesS = 0;
					long taggedSizeR = _firstRelationStorage.size()
							+ _firstTaggedRelationStorage.size();
					long taggedSizeS = _secondRelationStorage.size()
							+ _secondTaggedRelationStorage.size();
					_currentNumberOfAckedReshufflerWorkersTasks = 0;
					final String actionString = mapping;
					// Now change to the new mapping
					_currentAction = Action.fromString(actionString);
					_renamedIDindex = _currentAction.getNewReducerName(_renamedIDindex);

					// ****************
					// HANDLE STOP CODE
					// ****************
					// Now apply the discards
					final int discardingDim = identifyDim(_currentAction.getPreviousRows(),
							_currentAction.getPreviousColumns(), _currentAction.getNewRows(),
							_currentAction.getNewColumns(), true);
					LOG.info(getID() + " Joiner AT (STOP) before discarding: " + _renamedIDindex
							+ " FirstTagged:" + taggedSizeR + " SecondTagged:" + taggedSizeS
							+ " chose for discarding rel " + discardingDim);
					if (discardingDim != 1 && discardingDim != 2) // ASSERTION
						LOG.error("Wrong discardingDim ERROR");
					/* Perform the discards */
					performDiscards(discardingDim);
					taggedSizeR = _firstRelationStorage.size() + _firstTaggedRelationStorage.size();
					taggedSizeS = _secondRelationStorage.size()
							+ _secondTaggedRelationStorage.size();
					LOG.info(getID() + " Joiner AT (STOP) after discarding: " + _renamedIDindex
							+ " FirstTagged:" + taggedSizeR + " SecondTagged:" + taggedSizeS);

					// ****************
					// NOW PROCEED CODE
					// ****************
					// Migrate all the data (flush everything), now everything
					// is considered as tagged
					// Send the EOF of all tuples --> which by thus will have to
					// send back the ThetaSignalDataMigrationEnded signal
					// dont send anything to the synchronizer
					_isMigratingRelationStorage = false;
					_isMigratingTaggedRelationStorage = false;
					final int exchangingDim = identifyDim(_currentAction.getPreviousRows(),
							_currentAction.getPreviousColumns(), _currentAction.getNewRows(),
							_currentAction.getNewColumns(), false);

					if (exchangingDim != 1 && exchangingDim != 2) // ASSERTION
						LOG.error("Wrong exchangingDim ERROR");
					else
						performExchanges(exchangingDim);

					// ***********************
					// Now execute the missing joins
					// ***********************

					if (exchangingDim == 1)
						// U1 JOIN T2 ... the other join only
						joinTables(_firstRelationStorage, _secondTaggedRelationStorage,
								_secondTaggedRelationIndexes, true);
					else
						// U2 JOIN T1 ... the other join only
						joinTables(_secondRelationStorage, _firstTaggedRelationStorage,
								_firstTaggedRelationIndexes, false);

					// ********************************
					// Now the add the epoch's tagged tuples
					// ********************************
					// add T1' to T1
					addTaggedTuples(_firstTaggedRelationStorageNewEpoch,
							_firstTaggedRelationStorage, _firstTaggedRelationIndexes,
							_firstEmitterIndex);
					_firstTaggedRelationStorageNewEpoch = new TupleStorage();
					createIndexes(1, true, true);
					// add T2' to T2
					addTaggedTuples(_secondTaggedRelationStorageNewEpoch,
							_secondTaggedRelationStorage, _secondTaggedRelationIndexes,
							_secondEmitterIndex);
					_secondTaggedRelationStorageNewEpoch = new TupleStorage();
					createIndexes(2, true, true);

				}
			}

			/*
			 * - If Datamigration ended. after EOF sent from all the joiners
			 * received through the reshuffler 1) send the DataMigrationEnded
			 * acks to the synchronizer
			 */
			else if (signal.equals(SystemParameters.ThetaSignalDataMigrationEnded)) {
				_currentNumberOfAckedReshufflerWorkersTasks++;
				if (_numParentTasks == _currentNumberOfAckedReshufflerWorkersTasks) {
					LOG.info(getID() + " Joiner: " + _renamedIDindex
							+ " has reached end of DataMigration" + " actualID: " + _thisTaskID
							+ " got:" + _currentNumberOfAckedReshufflerWorkersTasks + "/"
							+ _numParentTasks);
					_currentNumberOfAckedReshufflerWorkersTasks = 0;
					getCollector().emitDirect(_AdvisorIndex, SystemParameters.ThetaJoinerAcks,
							new Values(SystemParameters.ThetaAckDataMigrationEnded));
				}
			}
		}
		/**
		 * Case 2 or 3) tuples only from the reshuffler; either
		 * 2)ThetaDatastreams or 3)datastreams
		 */
		// Actual Join
		else if (inputStream.equals(SystemParameters.ThetaDataReshufflerToJoiner)
				|| inputStream.equals(SystemParameters.ThetaDataMigrationReshufflerToJoiner)
				|| inputStream.equals(SystemParameters.DATA_STREAM)) {
			final String inputComponentIndex = stormTupleRcv
					.getStringByField(StormComponent.COMP_INDEX);
			final List<String> tuple = (List<String>) stormTupleRcv
					.getValueByField(StormComponent.TUPLE);
			String inputTupleString = MyUtilities.tupleToString(tuple, getConf());
			final String inputTupleHash = stormTupleRcv.getStringByField(StormComponent.HASH);
			// N.B. if received at this level then data migration has ended.
			if (MyUtilities.isFinalAck(tuple, getConf())) {
				_currentNumberOfFinalAckedParents--;
				if (_currentNumberOfFinalAckedParents == 0) {
					LOG.info(getID() + " AT (LAST_ACK) Joiner: " + _renamedIDindex
							+ " has received receivedTuplesR:" + _receivedTuplesR + " TaggedR:"
							+ _receivedTaggedTuplesR + " receivedTuplesS:" + _receivedTuplesS
							+ " TaggedS:" + _receivedTaggedTuplesS);
					final int first = _firstRelationStorage.size()
							+ _firstTaggedRelationStorage.size();
					final int second = _secondRelationStorage.size()
							+ _secondTaggedRelationStorage.size();
					LOG.info(getID() + " AT (LAST_ACK) Joiner: " + _renamedIDindex
							+ " has FirstRelation:" + _firstRelationStorage.size()
							+ " FirstRelationTagged:" + _firstTaggedRelationStorage.size()
							+ " SecondRelation:" + _secondRelationStorage.size()
							+ " SecondRelationTagged:" + _secondTaggedRelationStorage.size()
							+ " final sizes: " + first + "," + second);

					printStatistics(SystemParameters.FINAL_PRINT);
				}
				MyUtilities.processFinalAck(_currentNumberOfFinalAckedParents,
						getHierarchyPosition(), getConf(), stormTupleRcv, getCollector(),
						_periodicAggBatch);
				return;
			}

			boolean isTagged = false;
			if (inputStream.equals(SystemParameters.ThetaDataMigrationReshufflerToJoiner))
				isTagged = true;
			else
				numberOfTuplesMemory++;

			boolean isFromFirstEmitter = false;
			TupleStorage affectedStorage = null, oppositeStorage = null;
			List<Index> affectedIndexes = null, oppositeIndexes = null;

			if (_firstEmitterIndex.equals(inputComponentIndex)) {
				if (isTagged)
					_receivedTaggedTuplesR++;
				else
					_receivedTuplesR++;
				isFromFirstEmitter = true;
			} else if (_secondEmitterIndex.equals(inputComponentIndex)) {
				if (isTagged)
					_receivedTaggedTuplesS++;
				else
					_receivedTuplesS++;
				isFromFirstEmitter = false;
			} else if (!inputComponentIndex.equals("N/A"))
				throw new RuntimeException("InputComponentName " + inputComponentIndex
						+ " doesn't match neither " + _firstEmitterIndex + " nor "
						+ _secondEmitterIndex + ".");

			if (isTagged) // datamigration input tuple--> join with untagged
			// data
			{
				// first check if it is ThetaJoinerMigrationSignal which
				// requires to flush more tuples
				if (inputTupleString.equals(SystemParameters.ThetaJoinerMigrationSignal)) {

					if (_isMigratingRelationStorage)
						if (_migratingRelationStorage.size() <= migrationBufferSize) {
							final int endIndex = _migratingRelationCursor
									+ _migratingRelationStorage.size() - 1;
							emitBulk(_migratingRelationStorage, _migratingRelationIndex,
									_migratingRelationCursor, endIndex);
							clean(_migratingRelationStorage, _migratingRelationCursor, endIndex);
							_isMigratingRelationStorage = false;
						} else {
							final int endIndex = _migratingRelationCursor + migrationBufferSize - 1;
							emitBulk(_migratingRelationStorage, _migratingRelationIndex,
									_migratingRelationCursor, endIndex);
							clean(_migratingRelationStorage, _migratingRelationCursor, endIndex);
							_migratingRelationCursor += migrationBufferSize;
						}
					if (_isMigratingTaggedRelationStorage)
						if (_migratingTaggedRelationSize <= _migratingTaggedRelationCursor
								+ migrationBufferSize) {
							final int endIndex = _migratingTaggedRelationSize - 1;
							emitBulk(_migratingTaggedRelationStorage, _migratingRelationIndex,
									_migratingTaggedRelationCursor, endIndex);
							_isMigratingTaggedRelationStorage = false;
						} else {
							final int endIndex = _migratingTaggedRelationCursor
									+ migrationBufferSize - 1;
							emitBulk(_migratingTaggedRelationStorage, _migratingRelationIndex,
									_migratingTaggedRelationCursor, endIndex);
							_migratingTaggedRelationCursor += migrationBufferSize;
						}

					if (_isMigratingRelationStorage == true
							|| _isMigratingTaggedRelationStorage == true) {
						final Values tplSend = new Values("N/A", MyUtilities.stringToTuple(
								SystemParameters.ThetaJoinerMigrationSignal, getConf()), "N/A", -1);
						appendTimestampZero(tplSend);
						getCollector().emit(SystemParameters.ThetaDataMigrationJoinerToReshuffler,
								tplSend);
					} else {
						final Values tplSend = new Values("N/A", MyUtilities.stringToTuple(
								SystemParameters.ThetaJoinerDataMigrationEOF, getConf()), "N/A", -1);
						appendTimestampZero(tplSend);
						getCollector().emit(SystemParameters.ThetaDataMigrationJoinerToReshuffler,
								tplSend);
					}

					getCollector().ack(stormTupleRcv);
					return; // END
				}
				// ELSE
				final int inputTupleEpochNumber = stormTupleRcv
						.getIntegerByField(StormComponent.EPOCH);

				if (isFromFirstEmitter) {
					// R update
					if (inputTupleEpochNumber == _currentEpochNumber) {
						affectedStorage = _firstTaggedRelationStorage;
						oppositeStorage = _secondRelationStorage;
						affectedIndexes = _firstTaggedRelationIndexes;
						oppositeIndexes = _secondRelationIndexes;
					} else if (inputTupleEpochNumber == _currentEpochNumber + 1) {
						final String _currentDimExcDis = stormTupleRcv
								.getStringByField(StormComponent.DIM);
						final String[] splits = _currentDimExcDis.split("-");
						indexOfMigrating = Integer.parseInt(splits[0]);

						affectedStorage = _firstTaggedRelationStorageNewEpoch;
						oppositeStorage = _secondRelationStorageNewEpoch;
						affectedIndexes = _firstTaggedRelationIndexesNewEpoch;
						oppositeIndexes = _secondRelationIndexesNewEpoch;
					} else
						LOG.info("Error epoch number is not within bounds (tagged, first emitter). "
								+ "Tuple String is "
								+ inputTupleString
								+ ", input tuple epoch is "
								+ inputTupleEpochNumber
								+ ", currentEpoch is "
								+ _currentEpochNumber
								+ ". Current input stream is "
								+ inputStream
								+ ".");

				} else // S update
				if (inputTupleEpochNumber == _currentEpochNumber) {
					affectedStorage = _secondTaggedRelationStorage;
					oppositeStorage = _firstRelationStorage;
					affectedIndexes = _secondTaggedRelationIndexes;
					oppositeIndexes = _firstRelationIndexes;
				} else if (inputTupleEpochNumber == _currentEpochNumber + 1) {
					final String _currentDimExcDis = stormTupleRcv
							.getStringByField(StormComponent.DIM);
					final String[] splits = _currentDimExcDis.split("-");
					indexOfMigrating = Integer.parseInt(splits[0]);

					affectedStorage = _secondTaggedRelationStorageNewEpoch;
					oppositeStorage = _firstRelationStorageNewEpoch;
					affectedIndexes = _secondTaggedRelationIndexesNewEpoch;
					oppositeIndexes = _firstRelationIndexesNewEpoch;
				} else
					LOG.info("Error epoch number is not within bounds (tagged, second emitter)"
							+ "Tuple String is " + inputTupleString + ", input tuple epoch is "
							+ inputTupleEpochNumber + ", currentEpoch is " + _currentEpochNumber
							+ ". Current input stream is " + inputStream + ".");
				// add the stormTuple to the specific storage
				long incomingTimestamp = 0;
				if (MyUtilities.isStoreTimestamp(getConf(), getHierarchyPosition())) {
					incomingTimestamp = stormTupleRcv.getLongByField(StormComponent.TIMESTAMP);
					inputTupleString = incomingTimestamp
							+ SystemParameters.STORE_TIMESTAMP_DELIMITER + inputTupleString;
				}
				final int row_id = affectedStorage.insert(inputTupleString);
				List<String> valuesToApplyOnIndex = null;
				if (_existIndexes)
					valuesToApplyOnIndex = updateIndexes(stormTupleRcv, affectedIndexes, row_id);
				performJoin(stormTupleRcv, tuple, inputTupleHash, isFromFirstEmitter,
						oppositeIndexes, valuesToApplyOnIndex, oppositeStorage, incomingTimestamp);
			}

			else // fresh new tuple (join with tagged and untagged)
			{
				final int inputTupleEpochNumber = stormTupleRcv
						.getIntegerByField(StormComponent.EPOCH);
				String[] splits = {};
				/* 1)first join with untagged data */
				if (isFromFirstEmitter) {
					// R update
					if (inputTupleEpochNumber == _currentEpochNumber) {
						affectedStorage = _firstRelationStorage;
						oppositeStorage = _secondRelationStorage;
						affectedIndexes = _firstRelationIndexes;
						oppositeIndexes = _secondRelationIndexes;
					} else if (inputTupleEpochNumber == _currentEpochNumber + 1) {
						final String _currentDimExcDis = stormTupleRcv
								.getStringByField(StormComponent.DIM);
						splits = _currentDimExcDis.split("-");
						indexOfMigrating = Integer.parseInt(splits[0]);

						affectedStorage = _firstRelationStorageNewEpoch;
						oppositeStorage = _secondRelationStorageNewEpoch;
						affectedIndexes = _firstRelationIndexesNewEpoch;
						oppositeIndexes = _secondRelationIndexesNewEpoch;
					} else
						LOG.info("Error epoch number is not within bounds (new tuple, first emitter)"
								+ "Tuple String is "
								+ inputTupleString
								+ ", input tuple epoch is "
								+ inputTupleEpochNumber
								+ ", currentEpoch is "
								+ _currentEpochNumber
								+ ". Current input stream is "
								+ inputStream
								+ ".");
				} else // S update
				if (inputTupleEpochNumber == _currentEpochNumber) {
					affectedStorage = _secondRelationStorage;
					oppositeStorage = _firstRelationStorage;
					affectedIndexes = _secondRelationIndexes;
					oppositeIndexes = _firstRelationIndexes;
				} else if (inputTupleEpochNumber == _currentEpochNumber + 1) {
					final String _currentDimExcDis = stormTupleRcv
							.getStringByField(StormComponent.DIM);
					splits = _currentDimExcDis.split("-");
					indexOfMigrating = Integer.parseInt(splits[0]);

					affectedStorage = _secondRelationStorageNewEpoch;
					oppositeStorage = _firstRelationStorageNewEpoch;
					affectedIndexes = _secondRelationIndexesNewEpoch;
					oppositeIndexes = _firstRelationIndexesNewEpoch;
				} else
					LOG.info("Error epoch number is not within bounds (new tuple, second emitter)"
							+ "Tuple String is " + inputTupleString + ", input tuple epoch is "
							+ inputTupleEpochNumber + ", currentEpoch is " + _currentEpochNumber
							+ ". Current input stream is " + inputStream + ".");
				// add the stormTuple to the specific storage
				long incomingTimestamp = 0;
				if (MyUtilities.isStoreTimestamp(getConf(), getHierarchyPosition())) {
					incomingTimestamp = stormTupleRcv.getLongByField(StormComponent.TIMESTAMP);
					inputTupleString = incomingTimestamp
							+ SystemParameters.STORE_TIMESTAMP_DELIMITER + inputTupleString;
				}
				final int row_id = affectedStorage.insert(inputTupleString);
				List<String> valuesToApplyOnIndex = null;
				if (_existIndexes)
					valuesToApplyOnIndex = updateIndexes(stormTupleRcv, affectedIndexes, row_id);
				// (Deprecated) If in splitting phase --> Check ifGroupLeader
				// (Should be happening here only)
				performJoin(stormTupleRcv, tuple, inputTupleHash, isFromFirstEmitter,
						oppositeIndexes, valuesToApplyOnIndex, oppositeStorage, incomingTimestamp);// add
				// tuple

				/**************************************************
				 * TODO Add one more join for untagged(new Epoch) join_with
				 * untagged (old epoch)
				 *************************************************/
				boolean isIn = false;
				if (indexOfMigrating > 0 && inputTupleEpochNumber == _currentEpochNumber + 1) {
					final int exchgDim = indexOfMigrating;
					if (isFromFirstEmitter && exchgDim == 2) {
						// R update
						isIn = true;
						oppositeStorage = _secondRelationStorage;
						oppositeIndexes = _secondRelationIndexes;
					} else if ((!isFromFirstEmitter) && exchgDim == 1) {
						// S update
						isIn = true;
						oppositeStorage = _firstRelationStorage;
						oppositeIndexes = _firstRelationIndexes;
					}
					if (isIn) {
						valuesToApplyOnIndex = null;
						if (_existIndexes)
							valuesToApplyOnIndex = updateIndexes(stormTupleRcv, null, -1);
						performJoin(stormTupleRcv, tuple, inputTupleHash, isFromFirstEmitter,
								oppositeIndexes, valuesToApplyOnIndex, oppositeStorage,
								incomingTimestamp);
					}
				}
				/************************************************/
				/**************************************************
				 * TODO Add one more join for untagged(old Epoch) join_with
				 * untagged (new epoch)
				 *************************************************/
				if (indexOfMigrating > 0 && (inputTupleEpochNumber == _currentEpochNumber)) {
					isIn = false;
					final int exchgDim = indexOfMigrating;
					if (isFromFirstEmitter && exchgDim == 1) {
						// R update
						isIn = true;
						oppositeStorage = _secondRelationStorageNewEpoch;
						oppositeIndexes = _secondRelationIndexesNewEpoch;

					} else if ((!isFromFirstEmitter) && exchgDim == 2) {
						// S update
						isIn = true;
						oppositeStorage = _firstRelationStorageNewEpoch;
						oppositeIndexes = _firstRelationIndexesNewEpoch;
					}
					if (isIn) {
						valuesToApplyOnIndex = null;
						if (_existIndexes)
							valuesToApplyOnIndex = updateIndexes(stormTupleRcv, null, -1);
						performJoin(stormTupleRcv, tuple, inputTupleHash, isFromFirstEmitter,
								oppositeIndexes, valuesToApplyOnIndex, oppositeStorage,
								incomingTimestamp);
					}
				}
				/************************************************/
				/* 2)second join with tagged data */
				if (isFromFirstEmitter) {
					// R update
					if (inputTupleEpochNumber == _currentEpochNumber) {
						oppositeStorage = _secondTaggedRelationStorage;
						oppositeIndexes = _secondTaggedRelationIndexes;
					} else if (inputTupleEpochNumber == _currentEpochNumber + 1) {
						oppositeStorage = _secondTaggedRelationStorageNewEpoch;
						oppositeIndexes = _secondTaggedRelationIndexesNewEpoch;
					} else
						LOG.info("Error epoch number is not within bounds (new tuple joined with tagged data, first emitter)"
								+ "Tuple String is "
								+ inputTupleString
								+ ", input tuple epoch is "
								+ inputTupleEpochNumber
								+ ", currentEpoch is "
								+ _currentEpochNumber
								+ ". Current input stream is "
								+ inputStream
								+ ".");
				} else // S update
				if (inputTupleEpochNumber == _currentEpochNumber) {
					oppositeStorage = _firstTaggedRelationStorage;
					oppositeIndexes = _firstTaggedRelationIndexes;
				} else if (inputTupleEpochNumber == _currentEpochNumber + 1) {
					oppositeStorage = _firstTaggedRelationStorageNewEpoch;
					oppositeIndexes = _firstTaggedRelationIndexesNewEpoch;
				} else
					LOG.info("Error epoch number is not within bounds (new tuple joined with tagged data, second emitter)"
							+ "Tuple String is "
							+ inputTupleString
							+ ", input tuple epoch is "
							+ inputTupleEpochNumber
							+ ", currentEpoch is "
							+ _currentEpochNumber
							+ ". Current input stream is " + inputStream + ".");
				valuesToApplyOnIndex = null;
				if (_existIndexes)
					valuesToApplyOnIndex = updateIndexes(stormTupleRcv, null, -1);
				// (Deprecated) If in splitting phase --> Check ifGroupLeader
				// (Should be happening here only)
				performJoin(stormTupleRcv, tuple, inputTupleHash, isFromFirstEmitter,
						oppositeIndexes, valuesToApplyOnIndex, oppositeStorage, incomingTimestamp); // Shall
				// not
				// add
				// the
				// input
				// tuple
				// again
				/**************************************************
				 * one more join for untagged(new Epoch) join_with
				 * tagged (old epoch)
				 *************************************************/
				if (indexOfMigrating > 0 && (inputTupleEpochNumber == _currentEpochNumber + 1)) {
					isIn = false;
					final int exchgDim = indexOfMigrating;
					if (isFromFirstEmitter && exchgDim == 2) {
						// R update
						isIn = true;
						oppositeStorage = _secondTaggedRelationStorage;
						oppositeIndexes = _secondTaggedRelationIndexes;
					} else if ((!isFromFirstEmitter) && exchgDim == 1) {
						// S update
						isIn = true;
						oppositeStorage = _firstTaggedRelationStorage;
						oppositeIndexes = _firstTaggedRelationIndexes;
					}
					if (isIn) {
						valuesToApplyOnIndex = null;
						if (_existIndexes)
							valuesToApplyOnIndex = updateIndexes(stormTupleRcv, null, -1);
						performJoin(stormTupleRcv, tuple, inputTupleHash, isFromFirstEmitter,
								oppositeIndexes, valuesToApplyOnIndex, oppositeStorage,
								incomingTimestamp);
					}
				}
				/************************************************/
			}
		}

		// THIS IS FOR OUTPUTTING STATISTICS!
		if ((numberOfTuplesMemory) % _statsUtils.getDipInputFreqPrint() == 0)
			printStatistics(SystemParameters.INPUT_PRINT);
		getCollector().ack(stormTupleRcv);
	}

	@Override
	public ChainOperator getChainOperator() {
		return _operatorChain;
	}

	@Override
	public Map<String, Object> getComponentConfiguration() {
		return getConf();
	}

	public InputDeclarer getCurrentBolt() {
		return _currentBolt;
	}

	@Override
	public String getInfoID() {
		final String str = "DestinationStorage " + getID() + " has ID: " + getID();
		return str;
	}

	@Override
	protected InterchangingComponent getInterComp() {
		throw new RuntimeException("Hm, should not be here!");
	}

	@Override
	public long getNumSentTuples() {
		return _numSentTuples;
	}

	@Override
	public PeriodicAggBatchSend getPeriodicAggBatch() {
		return _periodicAggBatch;
	}

	private void join(Tuple stormTuple, List<String> tuple, boolean isFromFirstEmitter,
			TupleStorage oppositeStorage, long incomingTimestamp) {

		if (oppositeStorage == null || oppositeStorage.size() == 0)
			return;

		for (int i = 0; i < oppositeStorage.size(); i++) {
			String oppositeTupleString = oppositeStorage.get(i);
			long lineageTimestamp = incomingTimestamp;
			if (MyUtilities.isStoreTimestamp(getConf(), getHierarchyPosition())) {
				// timestamp has to be removed
				final String parts[] = oppositeTupleString.split("\\@");
				final long storedTimestamp = Long.valueOf(parts[0]);
				oppositeTupleString = parts[1];

				// now we set the maximum TS to the tuple
				if (storedTimestamp > lineageTimestamp)
					lineageTimestamp = storedTimestamp;
			}

			final List<String> oppositeTuple = MyUtilities.stringToTuple(oppositeTupleString,
					getComponentConfiguration());
			List<String> firstTuple, secondTuple;
			if (isFromFirstEmitter) {
				firstTuple = tuple;
				secondTuple = oppositeTuple;
			} else {
				firstTuple = oppositeTuple;
				secondTuple = tuple;
			}
			// Check joinCondition
			// if existIndexes == true, the join condition is already checked
			// before
			if (_joinPredicate == null || _existIndexes
					|| _joinPredicate.test(firstTuple, secondTuple)) { // if
				// null,
				// cross
				// product
				// Create the output tuple by omitting the oppositeJoinKeys
				// (ONLY for equi-joins since they are added
				// by the first relation), if any (in case of cartesian product
				// there are none)
				List<String> outputTuple = null;
				// Cartesian product - Outputs all attributes
				outputTuple = MyUtilities.createOutputTuple(firstTuple, secondTuple);
				applyOperatorsAndSend(stormTuple, outputTuple, lineageTimestamp);
			}
		}
	}

	private void joinTables(TupleStorage outerRelation, TupleStorage innerRelation,
			List<Index> innerRelationIndexes, boolean fromFirstEmitter) {
		for (int i = 0; i < outerRelation.size(); i++) {
			String tupleString = outerRelation.get(i);
			long incomingTimestamp = 0;
			if (MyUtilities.isStoreTimestamp(getConf(), getHierarchyPosition())) {
				// timestamp has to be removed
				final String parts[] = tupleString.split("\\@");
				incomingTimestamp = Long.valueOf(parts[0]);
				tupleString = parts[1];
			}
			final List<String> u2tuple = MyUtilities.stringToTuple(tupleString, getConf());

			final PredicateUpdateIndexesVisitor u2visitor = new PredicateUpdateIndexesVisitor(
					fromFirstEmitter, u2tuple);
			_joinPredicate.accept(u2visitor);
			final List<String> valuesToIndex = new ArrayList<String>(u2visitor._valuesToIndex);
			performJoin(null, u2tuple, null, fromFirstEmitter, innerRelationIndexes, valuesToIndex,
					innerRelation, incomingTimestamp);
		}
	}

	/* Perform discards on the relationNumber dimension 1=first 2=second */
	private void performDiscards(int relationNumber) {
		final PredicateCreateIndexesVisitor visitor = new PredicateCreateIndexesVisitor();
		_joinPredicate.accept(visitor);
		final TupleStorage keepStorage = new TupleStorage();
		ArrayList<Index> keepIndexes;
		String discardingEmitterIndex;
		int discardingParts, discardingIndex;

		TupleStorage discardingTupleStorage, discardingTaggedRelationStorage;
		if (relationNumber == 1) {
			discardingTupleStorage = _firstRelationStorage;
			discardingTaggedRelationStorage = _firstTaggedRelationStorage;
			keepIndexes = new ArrayList<Index>(visitor._firstRelationIndexes);
			discardingEmitterIndex = _firstEmitterIndex;
			discardingParts = _currentAction.getDiscardRowSplits();
			discardingIndex = _currentAction.getRowKeptPieceIndexByNewId(_renamedIDindex);
		} else {
			discardingTupleStorage = _secondRelationStorage;
			discardingTaggedRelationStorage = _secondTaggedRelationStorage;
			keepIndexes = new ArrayList<Index>(visitor._secondRelationIndexes);
			discardingEmitterIndex = _secondEmitterIndex;
			discardingParts = _currentAction.getDiscardColumnSplits();
			discardingIndex = _currentAction.getColumnKeptPieceIndexByNewId(_renamedIDindex);
		}

		final int[] hash = new int[discardingTaggedRelationStorage.size()
				+ discardingTupleStorage.size()];
		final int[] addresses = new int[discardingTaggedRelationStorage.size()
				+ discardingTupleStorage.size()];

		final BitVector isTagged = new BitVector(discardingTaggedRelationStorage.size()
				+ discardingTupleStorage.size());
		isTagged.set(0, discardingTaggedRelationStorage.size());

		TupleStorage.preProcess(discardingTaggedRelationStorage, discardingTupleStorage, hash,
				addresses);

		final int bounds[] = Discard.keep(hash, addresses, isTagged, discardingParts,
				discardingIndex);

		for (int i = bounds[0]; i <= bounds[1]; i++) {
			final int address = addresses[i];
			final boolean isTaggedTuple = isTagged.get(i);
			String tupleString = "";
			if (isTaggedTuple)
				tupleString = discardingTaggedRelationStorage.get(address);
			else
				tupleString = discardingTupleStorage.get(address);
			final int row_id = keepStorage.insert(tupleString);
			if (_existIndexes) {
				if (MyUtilities.isStoreTimestamp(getConf(), getHierarchyPosition())) {
					// timestamp has to be removed
					final String parts[] = tupleString.split("\\@");
					tupleString = parts[1];
				}
				updateIndexes(discardingEmitterIndex,
						MyUtilities.stringToTuple(tupleString, getConf()), keepIndexes, row_id);
			}
		}
		if (relationNumber == 1) {
			_firstTaggedRelationStorage = keepStorage;
			_firstTaggedRelationIndexes = keepIndexes;
			_firstRelationStorage = _firstRelationStorageNewEpoch;
			_firstRelationIndexes = _firstRelationIndexesNewEpoch;
			_firstRelationStorageNewEpoch = new TupleStorage();
			createIndexes(1, false, true);
		} else {
			_secondTaggedRelationStorage = keepStorage;
			_secondTaggedRelationIndexes = keepIndexes;
			_secondRelationStorage = _secondRelationStorageNewEpoch;
			_secondRelationIndexes = _secondRelationIndexesNewEpoch;
			_secondRelationStorageNewEpoch = new TupleStorage();
			createIndexes(2, false, true);
		}
	}

	private void performExchanges(int relationNumber) {

		TupleStorage exchangingRelationStorage;
		TupleStorage exchangingTaggedRelationStorage;
		List<Index> exchangingIndexes;

		if (relationNumber == 1) {
			exchangingIndexes = _firstTaggedRelationIndexes;
			exchangingTaggedRelationStorage = _firstTaggedRelationStorage;
			exchangingRelationStorage = _firstRelationStorage;
			_migratingRelationStorage = _firstRelationStorage;
			_migratingTaggedRelationStorage = _firstTaggedRelationStorage;
			_migratingTaggedRelationSize = _firstTaggedRelationStorage.size();
			_migratingRelationCursor = 0;
			_migratingTaggedRelationCursor = 0;
			_migratingRelationIndex = _firstEmitterIndex;
		} else {
			exchangingIndexes = _secondTaggedRelationIndexes;
			exchangingTaggedRelationStorage = _secondTaggedRelationStorage;
			exchangingRelationStorage = _secondRelationStorage;
			_migratingRelationStorage = _secondRelationStorage;
			_migratingTaggedRelationStorage = _secondTaggedRelationStorage;
			_migratingTaggedRelationSize = _secondTaggedRelationStorage.size();
			_migratingRelationCursor = 0;
			_migratingTaggedRelationCursor = 0;
			_migratingRelationIndex = _secondEmitterIndex;
		}

		if (exchangingRelationStorage.size() <= migrationBufferSize)
			emitBulk(exchangingRelationStorage, _migratingRelationIndex, 0,
					exchangingRelationStorage.size() - 1);
		else {
			_isMigratingRelationStorage = true;
			_migratingRelationCursor += migrationBufferSize;
			emitBulk(_migratingRelationStorage, _migratingRelationIndex, 0,
					_migratingRelationCursor - 1);
		}
		if (_migratingTaggedRelationSize <= migrationBufferSize)
			emitBulk(exchangingTaggedRelationStorage, _migratingRelationIndex, 0,
					_migratingTaggedRelationSize - 1);
		else {
			_isMigratingTaggedRelationStorage = true;
			_migratingTaggedRelationCursor += migrationBufferSize;
			emitBulk(_migratingTaggedRelationStorage, _migratingRelationIndex, 0,
					_migratingTaggedRelationCursor - 1);
		}
		// Restart everything to be tagged
		addTaggedTuples(exchangingRelationStorage, exchangingTaggedRelationStorage,
				exchangingIndexes, _migratingRelationIndex);

		if (_isMigratingRelationStorage) // if it was bigger than buffer size,
			// and still has more data
			clean(_migratingRelationStorage, 0, _migratingRelationCursor - 1); // remove
		// that
		// data

		if (relationNumber == 1) {
			_firstRelationStorage = _firstRelationStorageNewEpoch;
			_firstRelationIndexes = _firstRelationIndexesNewEpoch;
			_firstRelationStorageNewEpoch = new TupleStorage();
			createIndexes(1, false, true);// reinitialize untagged
		} else {
			_secondRelationStorage = _secondRelationStorageNewEpoch;
			_secondRelationIndexes = _secondRelationIndexesNewEpoch;
			_secondRelationStorageNewEpoch = new TupleStorage();
			createIndexes(2, false, true);// reinitialize untagged
		}

		if (_isMigratingRelationStorage || _isMigratingTaggedRelationStorage) {
			final Values tplSend = new Values("N/A", MyUtilities.stringToTuple(
					SystemParameters.ThetaJoinerMigrationSignal, getConf()), "N/A", -1);
			appendTimestampZero(tplSend);
			getCollector().emit(SystemParameters.ThetaDataMigrationJoinerToReshuffler, tplSend);
		} else {
			LOG.info(getID() + " joiner: " + _thisTaskIDindex + " D-M-E-E-O-F is being emitted"
					+ " actualID: " + _thisTaskID);
			final Values tplSend = new Values("N/A", MyUtilities.stringToTuple(
					SystemParameters.ThetaJoinerDataMigrationEOF, getConf()), "N/A", -1);
			appendTimestampZero(tplSend);
			getCollector().emit(SystemParameters.ThetaDataMigrationJoinerToReshuffler, tplSend);
		}
	}

	protected void performJoin(Tuple stormTupleRcv, List<String> tuple, String inputTupleHash,
			boolean isFromFirstEmitter, List<Index> oppositeIndexes,
			List<String> valuesToApplyOnIndex, TupleStorage oppositeStorage, long incomingTimestamp) {
		final TupleStorage tuplesToJoin = new TupleStorage();
		selectTupleToJoin(oppositeStorage, oppositeIndexes, isFromFirstEmitter,
				valuesToApplyOnIndex, tuplesToJoin);
		join(stormTupleRcv, tuple, isFromFirstEmitter, tuplesToJoin, incomingTimestamp);
	}

	@Override
	public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
		setCollector(collector);

		_thisTaskID = context.getThisTaskId();
		_thisTaskIDindex = context.getThisTaskIndex();
		_renamedIDindex = _thisTaskIDindex;
		_AdvisorIndex = context.getComponentTasks(_reshuffler.getID()).get(0);

		printStatistics(SystemParameters.INITIAL_PRINT);
	}

	@Override
	protected void printStatistics(int type) {
		if (_statsUtils.isTestMode())
			if (getHierarchyPosition() == StormComponent.FINAL_COMPONENT)
				// printing
				if (!MyUtilities.isCustomTimestampMode(getConf())) {
					if (type == SystemParameters.INITIAL_PRINT) {
						// computing variables
						final int size1 = _firstRelationStorage.size();
						final int taggedSize1 = _firstTaggedRelationStorage.size();
						_secondRelationStorage.size();
						_secondTaggedRelationStorage.size();
						_statsUtils.printInitialStats(LOG, _thisTaskID, size1, taggedSize1, size1,
								taggedSize1);
					} else if (type == SystemParameters.INPUT_PRINT) {
						// computing variables
						final int size1 = _firstRelationStorage.size();
						final int taggedSize1 = _firstTaggedRelationStorage.size();
						_secondRelationStorage.size();
						_secondTaggedRelationStorage.size();
						_statsUtils.printMemoryStats(getHierarchyPosition(), LOG, _thisTaskID,
								numberOfTuplesMemory, size1, taggedSize1, size1, taggedSize1);
					} else if (type == SystemParameters.OUTPUT_PRINT)
						_statsUtils.printResultStats(getHierarchyPosition(), LOG, _numSentTuples,
								_thisTaskID, false);
					else if (type == SystemParameters.FINAL_PRINT) {
						if (numNegatives > 0)
							LOG.info("WARNINGLAT! Negative latency for " + numNegatives
									+ ", at most " + maxNegative + "ms.");
						printTupleLatencyFinal();
						// computing variables
						final int size1 = _firstRelationStorage.size();
						final int taggedSize1 = _firstTaggedRelationStorage.size();
						_secondRelationStorage.size();
						_secondTaggedRelationStorage.size();
						_statsUtils.printMemoryStats(getHierarchyPosition(), LOG, _thisTaskID,
								numberOfTuplesMemory, size1, taggedSize1, size1, taggedSize1);
						_statsUtils.printResultStats(getHierarchyPosition(), LOG, _numSentTuples,
								_thisTaskID, true);
					}
				} else // only final statistics is printed if we are measuring
						// latency
				if (type == SystemParameters.FINAL_PRINT) {
					if (numNegatives > 0)
						LOG.info("WARNINGLAT! Negative latency for " + numNegatives + ", at most "
								+ maxNegative + "ms.");
					printTupleLatencyFinal();
					// computing variables
					final int size1 = _firstRelationStorage.size();
					final int taggedSize1 = _firstTaggedRelationStorage.size();
					_secondRelationStorage.size();
					_secondTaggedRelationStorage.size();
					_statsUtils.printMemoryStats(getHierarchyPosition(), LOG, _thisTaskID,
							numberOfTuplesMemory, size1, taggedSize1, size1, taggedSize1);
					_statsUtils.printResultStats(getHierarchyPosition(), LOG, _numSentTuples,
							_thisTaskID, true);
				}
	}

	private void selectTupleToJoin(TupleStorage oppositeStorage, List<Index> oppositeIndexes,
			boolean isFromFirstEmitter, List<String> valuesToApplyOnIndex, TupleStorage tuplesToJoin) {

		if (!_existIndexes) {
			tuplesToJoin.copy(oppositeStorage);
			return;
		}

		final TIntArrayList rowIds = new TIntArrayList();
		// If there is atleast one index (so we have single join conditions with
		// 1 index per condition)
		// Get the row indices in the storage of the opposite relation that
		// satisfy each join condition (equijoin / inequality)
		// Then take the intersection of the returned row indices since each
		// join condition
		// is separated by AND
		for (int i = 0; i < oppositeIndexes.size(); i++) {
			TIntArrayList currentRowIds = null;
			final Index currentOpposIndex = oppositeIndexes.get(i);
			final String value = valuesToApplyOnIndex.get(i);
			int currentOperator = _operatorForIndexes.get(i);
			// Switch inequality operator if the tuple coming is from the other
			// relation
			if (isFromFirstEmitter) {
				final int operator = currentOperator;
				if (operator == ComparisonPredicate.GREATER_OP)
					currentOperator = ComparisonPredicate.LESS_OP;
				else if (operator == ComparisonPredicate.NONGREATER_OP)
					currentOperator = ComparisonPredicate.NONLESS_OP;
				else if (operator == ComparisonPredicate.LESS_OP)
					currentOperator = ComparisonPredicate.GREATER_OP;
				else if (operator == ComparisonPredicate.NONLESS_OP)
					currentOperator = ComparisonPredicate.NONGREATER_OP;
				// then it is an equal or not equal so we dont switch the
				// operator
				else
					currentOperator = operator;
			}
			// Get the values from the index (check type first)
			if (_typeOfValueIndexed.get(i) instanceof String)
				currentRowIds = currentOpposIndex.getValues(currentOperator, value);
			// Even if valueIndexed is at first time an integer with
			// precomputation a*col +b, it become a double
			else if (_typeOfValueIndexed.get(i) instanceof Double)
				currentRowIds = currentOpposIndex.getValues(currentOperator,
						Double.parseDouble(value));
			else if (_typeOfValueIndexed.get(i) instanceof Integer)
				currentRowIds = currentOpposIndex.getValues(currentOperator,
						Integer.parseInt(value));
			else if (_typeOfValueIndexed.get(i) instanceof Date)
				try {
					currentRowIds = currentOpposIndex.getValues(currentOperator,
							_format.parse(value));
				} catch (final ParseException e) {
					e.printStackTrace();
				}
			else
				throw new RuntimeException("non supported type");
			// Compute the intersection
			// Search only within the ids that are in rowIds from previous join
			// conditions
			// If nothing returned (and since we want intersection), no need to
			// proceed.
			if (currentRowIds == null)
				return;
			// If it's the first index, add everything. Else keep the
			// intersection
			if (i == 0)
				rowIds.addAll(currentRowIds);
			else
				rowIds.retainAll(currentRowIds);
			// If empty after intersection, return
			if (rowIds.isEmpty())
				return;
		}
		// generate tuplestorage
		for (int i = 0; i < rowIds.size(); i++) {
			final int id = rowIds.get(i);
			tuplesToJoin.insert(oppositeStorage.get(id));
		}
	}

	// another signature
	private List<String> updateIndexes(String inputComponentIndex, List<String> tuple,
			List<Index> affectedIndexes, int row_id) {

		// Get a list of tuple attributes and the key value
		boolean comeFromFirstEmitter;
		if (inputComponentIndex.equals(_firstEmitterIndex))
			comeFromFirstEmitter = true;
		else
			comeFromFirstEmitter = false;
		final PredicateUpdateIndexesVisitor visitor = new PredicateUpdateIndexesVisitor(
				comeFromFirstEmitter, tuple);
		_joinPredicate.accept(visitor);
		final List<String> valuesToIndex = new ArrayList<String>(visitor._valuesToIndex);
		if (affectedIndexes == null)
			return valuesToIndex;
		final List<Object> typesOfValuesToIndex = new ArrayList<Object>(
				visitor._typesOfValuesToIndex);
		for (int i = 0; i < affectedIndexes.size(); i++)
			if (typesOfValuesToIndex.get(i) instanceof Integer)
				affectedIndexes.get(i).put(row_id, Integer.parseInt(valuesToIndex.get(i)));
			else if (typesOfValuesToIndex.get(i) instanceof Double)
				affectedIndexes.get(i).put(row_id, Double.parseDouble(valuesToIndex.get(i)));
			else if (typesOfValuesToIndex.get(i) instanceof String)
				affectedIndexes.get(i).put(row_id, valuesToIndex.get(i));
			else if (typesOfValuesToIndex.get(i) instanceof Date)
				try {
					affectedIndexes.get(i).put(row_id, _format.parse(valuesToIndex.get(i)));
				} catch (final ParseException e) {
					throw new RuntimeException(
							"Parsing problem in ThetaJoinerDynamicAdvisedEpochs.updatedIndexes "
									+ e.getMessage());
				}
			else
				throw new RuntimeException("non supported type");
		return valuesToIndex;
	}

	private List<String> updateIndexes(Tuple stormTupleRcv, List<Index> affectedIndexes, int row_id) {
		final String inputComponentIndex = stormTupleRcv
				.getStringByField(StormComponent.COMP_INDEX);
		final List<String> tuple = (List<String>) stormTupleRcv
				.getValueByField(StormComponent.TUPLE);
		// Get a list of tuple attributes and the key value
		boolean comeFromFirstEmitter;
		if (inputComponentIndex.equals(_firstEmitterIndex))
			comeFromFirstEmitter = true;
		else
			comeFromFirstEmitter = false;
		final PredicateUpdateIndexesVisitor visitor = new PredicateUpdateIndexesVisitor(
				comeFromFirstEmitter, tuple);
		_joinPredicate.accept(visitor);
		final List<String> valuesToIndex = new ArrayList<String>(visitor._valuesToIndex);
		if (affectedIndexes == null)
			return valuesToIndex;
		final List<Object> typesOfValuesToIndex = new ArrayList<Object>(
				visitor._typesOfValuesToIndex);
		for (int i = 0; i < affectedIndexes.size(); i++)
			if (typesOfValuesToIndex.get(i) instanceof Integer)
				affectedIndexes.get(i).put(row_id, Integer.parseInt(valuesToIndex.get(i)));
			else if (typesOfValuesToIndex.get(i) instanceof Double)
				affectedIndexes.get(i).put(row_id, Double.parseDouble(valuesToIndex.get(i)));
			else if (typesOfValuesToIndex.get(i) instanceof String)
				affectedIndexes.get(i).put(row_id, valuesToIndex.get(i));
			else if (typesOfValuesToIndex.get(i) instanceof Date)
				try {
					affectedIndexes.get(i).put(row_id, _format.parse(valuesToIndex.get(i)));
				} catch (final ParseException e) {
					throw new RuntimeException(
							"Parsing problem in ThetaJoinerDynamicAdvisedEpochs.updatedIndexes "
									+ e.getMessage());
				}
			else
				throw new RuntimeException("non supported type");
		return valuesToIndex;
	}

}
