package optimizers;

import components.Component;
import components.DataSourceComponent;
import components.OperatorComponent;
import expressions.ValueExpression;
import java.util.List;
import java.util.Map;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.SelectItem;
import operators.AggregateOperator;
import operators.ProjectOperator;
import operators.SelectOperator;
import optimizers.rule.RuleParallelismAssigner;
import queryPlans.QueryPlan;
import schema.Schema;
import schema.TPCH_Schema;
import util.ParserUtil;
import utilities.DeepCopy;
import utilities.SystemParameters;
import visitors.jsql.SQLVisitor;
import visitors.squall.IndexSelectItemsVisitor;
import visitors.squall.IndexWhereVisitor;

/*
 * Generate a query plan as it was parsed from the SQL.
 * SELECT and WHERE clause are attached to the final component.
 */
public class SimpleOptimizer implements Optimizer {
    private SQLVisitor _pq;
    private Schema _schema;
    private Map _map;
    
    private IndexComponentGenerator _cg;
    private IndexTranslator _it;
    
    public SimpleOptimizer(SQLVisitor pq, Map map){
        _pq = pq;
        _map = map;
        
        double scallingFactor = SystemParameters.getDouble(map, "DIP_DB_SIZE");
        _schema = new TPCH_Schema(scallingFactor);
        _it = new IndexTranslator(_schema, pq.getTan());
    }

    @Override
    public QueryPlan generate(){
        _cg = generateTableJoins();

        //selectItems might add OperatorComponent, this is why it goes first
        processSelectClause(_pq.getSelectItems());
        processWhereClause(_pq.getWhereExpr());

        ParserUtil.orderOperators(_cg.getQueryPlan());

        RuleParallelismAssigner parAssign = new RuleParallelismAssigner(_cg.getQueryPlan(), _pq.getTan(), _schema, _map);
        parAssign.assignPar();

        return _cg.getQueryPlan();
    }

    private IndexComponentGenerator generateTableJoins() {
        List<Table> tableList = _pq.getTableList();
        List<Join> joinList = _pq.getJoinList();
        
        IndexComponentGenerator cg = new IndexComponentGenerator(_schema, _pq.getTan(), _map);
        Component firstParent = cg.generateDataSource(ParserUtil.getComponentName(tableList.get(0)));

        //a special case
        if(tableList.size()==1){    
            return cg;
        }

        // This generates a lefty query plan.
        for(int i=0; i<joinList.size(); i++){
            DataSourceComponent secondParent = cg.generateDataSource(ParserUtil.getComponentName(tableList.get(i+1)));
            List<Expression> currentJoinCondition = ParserUtil.createListExp(joinList.get(i).getOnExpression());
            firstParent = cg.generateEquiJoin(firstParent, secondParent, currentJoinCondition);
        }
        return cg;
    }

    private int processSelectClause(List<SelectItem> selectItems) {
        //TODO: take care in nested case
        IndexSelectItemsVisitor selectVisitor = new IndexSelectItemsVisitor(_cg.getQueryPlan(), _schema, _pq.getTan(), _map);
        for(SelectItem elem: selectItems){
            elem.accept(selectVisitor);
        }
        List<AggregateOperator> aggOps = selectVisitor.getAggOps();
        List<ValueExpression> groupByVEs = selectVisitor.getGroupByVEs();

        Component affectedComponent = _cg.getQueryPlan().getLastComponent();
        attachSelectClause(aggOps, groupByVEs, affectedComponent);
        return (aggOps.isEmpty() ? IndexSelectItemsVisitor.NON_AGG : IndexSelectItemsVisitor.AGG);
    }

    private void attachSelectClause(List<AggregateOperator> aggOps, List<ValueExpression> groupByVEs, Component affectedComponent) {
        if (aggOps.isEmpty()){
            ProjectOperator project = new ProjectOperator(groupByVEs);
            affectedComponent.addOperator(project);
        }else if (aggOps.size() == 1){
            //all the others are group by
            AggregateOperator firstAgg = aggOps.get(0);

            if(ParserUtil.isAllColumnRefs(groupByVEs)){
                //plain fields in select
                List<Integer> groupByColumns = ParserUtil.extractColumnIndexes(groupByVEs);
                firstAgg.setGroupByColumns(groupByColumns);

                //Setting new level of components is necessary for correctness only for distinct in aggregates
                    //  but it's certainly pleasant to have the final result grouped on nodes by group by columns.
                boolean newLevel = !(_it.isHashedBy(affectedComponent, groupByColumns));
                if(newLevel){
                    affectedComponent.setHashIndexes(groupByColumns);
                    OperatorComponent newComponent = new OperatorComponent(affectedComponent,
                                                                      ParserUtil.generateUniqueName("OPERATOR"),
                                                                      _cg.getQueryPlan()).addOperator(firstAgg);

                }else{
                    affectedComponent.addOperator(firstAgg);
                }
             }else{
                //Sometimes groupByVEs contains other functions, so we have to use projections instead of simple groupBy
                //always new level

                if (affectedComponent.getHashExpressions()!=null && !affectedComponent.getHashExpressions().isEmpty()){
                    //TODO: probably will be solved in cost-based optimizer
                    throw new RuntimeException("Too complex: cannot have hashExpression both for joinCondition and groupBy!");
                }

                //WARNING: groupByVEs cannot be used on two places: that's why we do deep copy
                ProjectOperator groupByProj = new ProjectOperator((List<ValueExpression>)DeepCopy.copy(groupByVEs));
                firstAgg.setGroupByProjection(groupByProj);

                //current component
                affectedComponent.setHashExpressions((List<ValueExpression>)DeepCopy.copy(groupByVEs));

                //next component
                OperatorComponent newComponent = new OperatorComponent(affectedComponent,
                                                                      ParserUtil.generateUniqueName("OPERATOR"),
                                                                      _cg.getQueryPlan()).addOperator(firstAgg);

            }
        }else{
            throw new RuntimeException("For now only one aggregate function supported!");
        }
    }

    private void processWhereClause(Expression whereExpr) {
        // TODO: in non-nested case, there is a single Expression
        
        //all the selection are performed on the last component
        Component affectedComponent = _cg.getQueryPlan().getLastComponent();
        IndexWhereVisitor whereVisitor = new IndexWhereVisitor(affectedComponent, _schema, _pq.getTan());
        if(whereExpr != null){
            whereExpr.accept(whereVisitor);
            attachWhereClause(whereVisitor.getSelectOperator(), affectedComponent);
        }
    }

    private void attachWhereClause(SelectOperator select, Component affectedComponent) {
        affectedComponent.addOperator(select);
    }

}
