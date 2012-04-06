/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package operators;

import conversion.IntegerConversion;
import conversion.NumericConversion;
import expressions.Addition;
import expressions.ValueExpression;
import expressions.ValueSpecification;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.log4j.Logger;
import utilities.MyUtilities;


public class AggregateCountOperator implements AggregateOperator{
        private static final long serialVersionUID = 1L;
        private static Logger LOG = Logger.getLogger(AggregateCountOperator.class);

        //the GroupBy type
        private static final int GB_UNSET = -1;
        private static final int GB_COLUMNS = 0;
        private static final int GB_PROJECTION =1;

        private DistinctOperator _distinct;
        private int _groupByType = GB_UNSET;
        private List<Integer> _groupByColumns = new ArrayList<Integer>();
        private ProjectionOperator _groupByProjection;
        private int _tuplesProcessed = 0;

        private NumericConversion<Integer> _wrapper = new IntegerConversion();
        private HashMap<String, Integer> _aggregateMap = new HashMap<String, Integer>();

        private Map _map;

        public AggregateCountOperator(Map map){
            _map = map;
        }

        //from AgregateOperator
        @Override
        public AggregateCountOperator setGroupByColumns(List<Integer> groupByColumns) {
            if(!alreadySetOther(GB_COLUMNS)){
                _groupByType = GB_COLUMNS;
                _groupByColumns = groupByColumns;
                return this;
            }else{
                throw new RuntimeException("Aggragation already has groupBy set!");
            }
        }

        @Override
        public AggregateCountOperator setGroupByProjection(ProjectionOperator groupByProjection){
             if(!alreadySetOther(GB_PROJECTION)){
                _groupByType = GB_PROJECTION;
                _groupByProjection = groupByProjection;
                return this;
            }else{
                throw new RuntimeException("Aggragation already has groupBy set!");
            }
        }

        @Override
        public AggregateCountOperator setDistinct(DistinctOperator distinct){
            _distinct = distinct;
            return this;
        }

        @Override
        public List<Integer> getGroupByColumns() {
            return _groupByColumns;
        }

        @Override
        public ProjectionOperator getGroupByProjection(){
            return _groupByProjection;
        }

        @Override
        public DistinctOperator getDistinct() {
            return _distinct;
        }

        @Override
        public List<ValueExpression> getExpressions() {
            return new ArrayList<ValueExpression>();
        }

         //from Operator
        @Override
        public List<String> process(List<String> tuple){
            _tuplesProcessed++;
            if(_distinct != null){
                tuple = _distinct.process(tuple);
                if(tuple == null){
                    return null;
                }
            }
            String tupleHash;
            if(_groupByType == GB_PROJECTION){
                tupleHash = MyUtilities.createHashString(tuple, _groupByColumns, _groupByProjection.getExpressions(), _map);
            }else{
                tupleHash = MyUtilities.createHashString(tuple, _groupByColumns, _map);
            }
            Integer value = updateContent(tuple, tupleHash);
            String strValue = _wrapper.toString(value);
            
            // propagate further the affected tupleHash-tupleValue pair
            ArrayList<String> affectedTuple = new ArrayList<String>();
            affectedTuple.add(tupleHash);
            affectedTuple.add(strValue);

            return affectedTuple;
        }

        private Integer updateContent(List<String> tuple, String tupleHash){
            Integer value = _aggregateMap.get(tupleHash);
            if(value == null){
                value=_wrapper.getInitialValue();
            }
            value = runAggregateFunction(value);
            _aggregateMap.put(tupleHash, value);
            return value;
        }

        //actual operator implementation
        private Integer runAggregateFunction(Integer value) {
            return value+1;
        }

        @Override
        public boolean isBlocking() {
            return true;
        }

        @Override
        public void addContent(AggregateOperator otherAgg){
            HashMap<String, Integer> otherStorage = (HashMap<String, Integer>) otherAgg.getStorage();

            Iterator<Entry<String, Integer>> it = otherStorage.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry pairs = (Map.Entry)it.next();
                String key = (String)pairs.getKey();
                Integer otherValue = (Integer)pairs.getValue();

                Integer value = _aggregateMap.get(key);
                if(value == null){
                    _aggregateMap.put(key, otherValue);
                }else{
                    ValueExpression<Integer> base = new ValueSpecification<Integer>(_wrapper, value);
                    ValueExpression<Integer> other = new ValueSpecification<Integer>(_wrapper, otherValue);
                    Addition<Integer> result = new Addition<Integer>(_wrapper, base, other);
                    Integer newValue = result.eval(null);
                    _aggregateMap.put(key, newValue);
                }
            }
        }

        @Override
        public HashMap<String, Integer> getStorage(){
            return _aggregateMap;
        }

        @Override
	public String printContent(){
            StringBuilder sb = new StringBuilder();
            Iterator<Entry<String, Integer>> it = _aggregateMap.entrySet().iterator();
	    while (it.hasNext()) {
	       Map.Entry pairs = (Map.Entry)it.next();
	       Integer value = (Integer) pairs.getValue();
	       sb.append(pairs.getKey()).append(" = ").append(value).append("\n");
	    }
            return sb.toString();
        }

        @Override
        public int tuplesProcessed(){
            return _tuplesProcessed;
        }

        //for this method it is essential that HASH_DELIMITER, which is used in tupleToString method,
        //  is the same as DIP_GLOBAL_ADD_DELIMITER
        @Override
        public List<String> getContent() {
            List<String> content = new ArrayList<String>();
            if(_groupByType == GB_UNSET){
                Iterator<Entry<String, Integer>> it = _aggregateMap.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry pairs = (Map.Entry)it.next();
                    int value = (Integer)pairs.getValue();

                    //we neglect key and add only value (should be exactly one value)
                    content.add(_wrapper.toString(value));
                }
            }else{
                Iterator<Entry<String, Integer>> it = _aggregateMap.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry pairs = (Map.Entry)it.next();
                    String key = (String)pairs.getKey();
                    int value = (Integer)pairs.getValue();

                    List<String> tuple = new ArrayList<String>();
                    tuple.add(key);
                    tuple.add(_wrapper.toString(value));

                    content.add(MyUtilities.tupleToString(tuple, _map));
                }

            }
            return content;
        }


        @Override
        public String toString(){
            StringBuilder sb = new StringBuilder();
            sb.append("AggregateCountOperator ");
            if(_groupByColumns.isEmpty() && _groupByProjection == null){
                sb.append("\n  No groupBy!");
            }else if (!_groupByColumns.isEmpty()){
                sb.append("\n  GroupByColumns are ").append(getGroupByStr()).append(".");
            }else if (_groupByProjection != null){
                sb.append("\n  GroupByProjection is ").append(_groupByProjection.toString()).append(".");
            }
            if(_distinct!=null){
                sb.append("\n  It also has distinct ").append(_distinct.toString());
            }
            return sb.toString();
        }

        private String getGroupByStr(){
            StringBuilder sb = new StringBuilder();
            sb.append("(");
            for(int i=0; i<_groupByColumns.size(); i++){
                sb.append(_groupByColumns.get(i));
                if(i==_groupByColumns.size()-1){
                    sb.append(")");
                }else{
                    sb.append(", ");
                }
            }
            return sb.toString();
        }

        private boolean alreadySetOther(int GB_COLUMNS) {
            return (_groupByType != GB_COLUMNS && _groupByType != GB_UNSET);
        }

}