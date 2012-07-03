package sql.optimizers.cost;

import java.util.*;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import plan_runner.components.DataSourceComponent;
import plan_runner.components.EquiJoinComponent;
import plan_runner.utilities.SystemParameters;
import sql.schema.Schema;
import sql.util.ParserUtil;
import sql.visitors.jsql.SQLVisitor;


public class CostParallelismAssigner {
    private final SQLVisitor _pq;
    private final Map _map;
    private final Schema _schema;
    private final Map<String, Expression> _compNamesAndExprs;
    private final Map<Set<String>, Expression> _compNamesOrExprs;
    
    private final ProjGlobalCollect _globalCollect;
    
    private Map<String, Integer> _sourcePars;

    public CostParallelismAssigner(Schema schema,
            SQLVisitor pq,
            Map map,
            Map<String, Expression> compNamesAndExprs,
            Map<Set<String>, Expression> compNamesOrExprs,
            ProjGlobalCollect globalCollect) {
        _pq = pq;
        _schema = schema;
        _map = map;
        _compNamesAndExprs = compNamesAndExprs;
        _compNamesOrExprs = compNamesOrExprs;
        
        _globalCollect = globalCollect;
    }

    /*
     * This is done on fake ComponentGenerator, so there is no computeSourcePar
     *   parallelism on source components is input variable
     * This method is idempotent, no side effects, can be called multiple times.
     */
    public Map<String, Integer> computeSourcePar(int totalSourcePar){
        List<Table> tableList = _pq.getTableList();
        if(totalSourcePar < tableList.size()){
            throw new RuntimeException("There is not enought nodes such that all the sources get at least parallelism = 1");
        }
        /*
         * We need a way to generate parallelism for all the DataSources
         *   This depends on its selectivity/cardinality of each dataSource
         *   So we will generate all the sources witihin a fake sourceCG,
         *   and then proportionally assign parallelism.
         */
        NameComponentGenerator sourceCG = new NameComponentGenerator(_schema, _pq,
                 _map, this, _compNamesAndExprs, _compNamesOrExprs, _globalCollect, true);

         List<OrderedCostParams> sourceCostParams = new ArrayList<OrderedCostParams>();
         long totalCardinality = 0;
         for(Table table: tableList){
             DataSourceComponent source = sourceCG.generateDataSource(ParserUtil.getComponentName(table));
             String compName = source.getName();
             long cardinality = sourceCG.getCostParameters(compName).getCardinality();
             totalCardinality += cardinality;
             sourceCostParams.add(new OrderedCostParams(compName, cardinality));
         }

         /* 
          * Sort by incresing cardinalities, 
          *   the tables with dblNumNodes = 0 is assigned 1
          *   and all other proportionally shares the number of nodes
          * We divide by its output cardinality, because network traffic is the dominant cost
         */
         Collections.sort(sourceCostParams);
         int remainingPhysicalNodes = totalSourcePar;
         int remainingLogicalNodes = sourceCostParams.size();
         double remainingCardinality = totalCardinality;
         for(OrderedCostParams cnc: sourceCostParams){
             long cardinality = cnc.getCardinality();
             double ratioNodes = (double)cardinality/remainingCardinality;
             double dblNumNodes = ratioNodes * remainingPhysicalNodes;
             int nodeParallelism = (int) (dblNumNodes + 0.5); // rounding effect out of the default flooring

             if(nodeParallelism == 0){
                 //lower bounds
                 nodeParallelism++;
             }
             if(nodeParallelism + remainingLogicalNodes > remainingPhysicalNodes){
                 //upper bound is that all the following sources has at least parallelism = 1
                 if(nodeParallelism > 1){
                     nodeParallelism--;
                 }
             }
             if(remainingPhysicalNodes == 0 || nodeParallelism == 0){
                 throw new RuntimeException("Not enough nodes, should not be here!");
             }

             remainingLogicalNodes--;
             remainingPhysicalNodes -= nodeParallelism;
             remainingCardinality -= cardinality;
             
             //if I am the last guy, I will take all the remaining HW slots
             if(remainingLogicalNodes == 0){
                 nodeParallelism += remainingPhysicalNodes;
             }
             cnc.setParallelism(nodeParallelism);
         }

         /*
          * Now convert it to a Map, so that parallelism for source can be easier obtained
          */
         _sourcePars = convertListToMap(sourceCostParams);
         return _sourcePars;
    }


    public void setParallelism(DataSourceComponent source, Map<String, CostParams> compCost){
        if(_sourcePars == null){
            throw new RuntimeException("CostParallelismAssigner.computeSourcePar has to be invoked before setting parallelism of sources!");
        }
                
        String sourceName = source.getName();        
        int parallelism = _sourcePars.get(sourceName);
        compCost.get(sourceName).setParallelism(parallelism);
    }

    private Map<String, Integer> convertListToMap(List<OrderedCostParams> sourceCostParams) {
         Map<String, Integer> compParallelism = new HashMap<String, Integer>();
         for(OrderedCostParams cnc: sourceCostParams){
             String compName = cnc.getComponentName();
             int parallelism = cnc.getParallelism();
             compParallelism.put(compName, parallelism);
         }
         return compParallelism;
    }

    /*
     * cost-function
     * also idempotent, no changes to `this`
     *   changes only compCost
     */
    public void setParallelism(EquiJoinComponent joinComponent, Map<String, CostParams> compCost) {
        String leftParent = joinComponent.getParents()[0].getName();
        String rightParent = joinComponent.getParents()[1].getName();

        CostParams leftParentParams = compCost.get(leftParent);
        CostParams rightParentParams = compCost.get(rightParent);

        int leftParallelism = leftParentParams.getParallelism();
        int rightParallelism = rightParentParams.getParallelism();

        //compute
        int parallelism = parallelismFormula(leftParentParams, rightParentParams);

        //upper bound
        int maxParallelism = estimateDistinctHashes(leftParentParams, rightParentParams);
        if(parallelism > maxParallelism){
            if(leftParallelism == 1 && rightParallelism == 1){
                //if parallelism of both parents is 1, then we should not raise an exception
                //  exception serves to force smaller parallelism at sources
                parallelism = maxParallelism;
            }else{
                throw new RuntimeException("Component " + joinComponent.getName() + " cannot have parallelism more than " + maxParallelism);
            }
        }

        //setting
        String currentComp = joinComponent.getName();
        compCost.get(currentComp).setParallelism(parallelism);
    }

    private int parallelismFormula(CostParams leftParentParams, CostParams rightParentParams) {
        //computing TODO: does not take into account when joinComponent send tuples further down
        double dblParallelism = leftParentParams.getSelectivity() * leftParentParams.getParallelism() +
                            rightParentParams.getSelectivity() * rightParentParams.getParallelism() +
                            1.0/8 * (leftParentParams.getParallelism() + rightParentParams.getParallelism());
        int parallelism = (int) dblParallelism;
        if(parallelism != dblParallelism){
            //parallelism is ceil of dblParallelism
            parallelism++;
        }
        return parallelism;
    }

    /*
     * We take the number of tuples as the upper limit.
     *   The real number of distinct hashes may be much smaller,
     *   for example when having multiple tuples with the very same hash value.
     *   This is rare in practice, in the example we tried,
     *      it occurs only for the final aggregation when join and final aggregation are not on the last node.
     */
    private int estimateDistinctHashes(CostParams leftParentParams, CostParams rightParentParams) {
        /* TODO: to implement this properly, we need to:
         *   - find all the parent column appearing in joinCondition
         *         - column are found by using ParserUtil.getJSQLColumns(joinCondition)
         *         - joinCondition related to the parents is available in CostOptimizer (obtained byJoinTableExpr.getExpression(table1, table2))
         *   - check if this column is the key, or its functional dependency
         *          (for example NATION.NATIONNAME is a functional dependency of NATION.NATIONKEY)
         */
        long leftCardinality = leftParentParams.getCardinality();
        long rightCardinality = rightParentParams.getCardinality();

        long distinctValues = leftCardinality;
        if(distinctValues > rightCardinality){
            //we return the smaller one
            distinctValues = rightCardinality;
        }

        int result = (int) distinctValues;
        if(result != distinctValues){
            //so huge that cannot fit in int, we will set to MAX_PARALLELISM
            result = SystemParameters.getInt(_map, "DIP_NUM_WORKERS");
        }
        return result;
    }

    /*
     * we need separate class from CostParams, because here we want to order them based on cardinality
     * This class will contain all the parallelism for DataSourceComponents
     */
    private class OrderedCostParams extends CostParams implements Comparable<OrderedCostParams>{
        private String _componentName;

        public OrderedCostParams(String componentName, long cardinality){
            _componentName = componentName;
            setCardinality(cardinality);
        }

        public String getComponentName() {
            return _componentName;
        }

        @Override
        public int compareTo(OrderedCostParams t) {
            long myCardinality = getCardinality();
            long otherCardinality = t.getCardinality();
            return (new Long(myCardinality)).compareTo(new Long(otherCardinality));
        }
    }
}
