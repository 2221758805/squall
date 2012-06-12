/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package optimizers.cost;

import components.DataSourceComponent;
import components.EquiJoinComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import schema.Schema;
import util.ParserUtil;
import util.TableAliasName;


public class CostParallelismAssigner {
    private final Schema _schema;
    private final TableAliasName _tan;
    private final NameTranslator _ot;
    private final String _dataPath;
    private final String _extension;
    private final Map _map;
    private final Map<String, Expression> _compNamesAndExprs;
    private final Map<Set<String>, Expression> _compNamesOrExprs;

    public CostParallelismAssigner(Schema schema,
            TableAliasName tan,
            NameTranslator ot,
            String dataPath,
            String extension,
            Map map,
            Map<String, Expression> compNamesAndExprs,
            Map<Set<String>, Expression> compNamesOrExprs) {

        _schema = schema;
        _tan = tan;
        _ot = ot;
        _dataPath = dataPath;
        _extension = extension;
        _map = map;
        _compNamesAndExprs = compNamesAndExprs;
        _compNamesOrExprs = compNamesOrExprs;
    }

    /*
     * This is done on fake ComponentGenerator, so there is no setSourceParallelism
     *   parallelism on source components is input variable
     * This method is idempotent, no side effects, can be called multiple times.
     */
    public Map<String, Integer> getSourceParallelism(List<Table> tableList, int totalSourcePar){
        /*
         * We need a way to generate parallelism for all the DataSources
         *   This depends on its selectivity/cardinality of each dataSource
         *   So we will generate all the sources witihin a fake sourceCG,
         *   and then proportionally assign parallelism.
         */
        NameComponentGenerator sourceCG = new NameComponentGenerator(_schema, _tan,
                 _ot, _dataPath, _extension, _map, this, _compNamesAndExprs, _compNamesOrExprs);

         List<OrderedCostParams> sourceCostParams = new ArrayList<OrderedCostParams>();
         long totalCardinality = 0;
         for(Table table: tableList){
             DataSourceComponent source = sourceCG.generateDataSource(ParserUtil.getComponentName(table));
             String compName = source.getName();
             long cardinality = sourceCG.getCostParameters(compName).getCardinality();
             totalCardinality += cardinality;
             sourceCostParams.add(new OrderedCostParams(compName, cardinality));
         }

         /* Sort by cardinalities, so that the smallest tables does not end up with parallelism = 0
          * We divide by its output cardinality, because network traffic is the dominant cost
          */
         int availableNodes = totalSourcePar;
         Collections.sort(sourceCostParams);
         for(OrderedCostParams cnc: sourceCostParams){
             long cardinality = cnc.getCardinality();
             double ratioNodes = (double)cardinality/totalCardinality;
             double dblNumNodes = ratioNodes * totalSourcePar;
             int numNodes = (int) (dblNumNodes + 0.5); // rounding effect out of the default flooring

             if(availableNodes == 0){
                 throw new RuntimeException("There is not enought nodes such that all the sources get at least parallelism = 1");
             }
             if(numNodes == 0){
                 //lower bounds
                 numNodes = 1;
             }
             if(numNodes > availableNodes){
                 //upper bounds, we already checked if _totalSourcePar is not 0
                 numNodes = availableNodes;
             }

             cnc.setParallelism(numNodes);
             availableNodes -= numNodes;
         }

         /*
          * Now convert it to a Map, so that parallelism for source can be easier obtained
          */
         Map<String, Integer> compParallelism = convertListToMap(sourceCostParams);

         return compParallelism;
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

        //computing TODO: does not take into account when joinComponent send tuples further down
        double dblParallelism = leftParentParams.getSelectivity() * leftParentParams.getParallelism() +
                            rightParentParams.getSelectivity() * rightParentParams.getParallelism() +
                            1/8 * (leftParentParams.getParallelism() + rightParentParams.getParallelism());
        int parallelism = (int) dblParallelism;
        if(parallelism == dblParallelism){
            //parallelism is ceil of dblParallelism
            parallelism ++;
        }

        //setting
        String currentComp = joinComponent.getName();
        compCost.get(currentComp).setParallelism(parallelism);
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
