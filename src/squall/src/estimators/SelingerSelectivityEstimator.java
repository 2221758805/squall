/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package estimators;

import conversion.TypeConversion;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import schema.Schema;
import schema.TPCH_Schema;
import util.ParserUtil;
import util.TableAliasName;


/* TODO high prio:
 * do not work when invoked on something which is not DataSourceComponent (no exception)
 *    no matter on which component we do invoke, the only important is to know previous projections(TPCH7, 8)
 * AndExpression (exception) - if the columns are not the same, look at the textbook
 *
 * TODO low prio:
 * do not support R.A + 4 < 2, we will need ValueExpression with ranges to support that, and a user can rewrite it itself
 * do not support R.A + R.B = 2 (no exception)
 *   if there are multiple fields addressed, they have probably some dependency, which we don't model yet
 */
public class SelingerSelectivityEstimator implements SelectivityEstimator{

    private Schema _schema;
    private TableAliasName _tan;

    public SelingerSelectivityEstimator(Schema schema, TableAliasName tan){
        _schema = schema;
        _tan = tan;
    }

    public double estimate(List<Expression> exprs){
        //this is threated as a list of AndExpressions
        if(exprs.size() == 1){
            return estimate(exprs.get(0));
        }

        //at least two expressions in the list
        AndExpression and = new AndExpression(exprs.get(0), exprs.get(1));
        for(int i = 2; i < exprs.size(); i++){
            and = new AndExpression(and, exprs.get(i));
        }
        return estimate(and);
    }

    public double estimate(Expression expr) {
        //TODO without instanceof
        //  similarly to JSQLTypeConvertor, it can be done via visitor pattern, 
        //  but result has to be read, and cannot implement SelectivityEstimator anymore
        if(expr instanceof EqualsTo){
            return estimate((EqualsTo)expr);
        }else if(expr instanceof NotEqualsTo){
            return estimate((NotEqualsTo)expr);
        }else if(expr instanceof MinorThan){
            return estimate((MinorThan)expr);
        }else if(expr instanceof MinorThanEquals){
            return estimate((MinorThanEquals)expr);
        }else if(expr instanceof GreaterThan){
            return estimate((GreaterThan)expr);
        }else if(expr instanceof GreaterThanEquals){
            return estimate((GreaterThanEquals)expr);
        }else if(expr instanceof AndExpression){
            return estimate((AndExpression)expr);
        }else if(expr instanceof OrExpression){
            return estimate((OrExpression)expr);
        }
        throw new RuntimeException("We should be in a more specific method!");
    }

    public double estimate(EqualsTo equals){
        List<Column> columns = ParserUtil.getJSQLColumns(equals);

        Column column = columns.get(0);
        String fullSchemaColumnName = _tan.getFullSchemaColumnName(column);

        int distinctValues = _schema.getNumDistinctValues(fullSchemaColumnName);
        return 1.0/distinctValues;
    }


    public double estimate(MinorThan mt){
        List<Column> columns = ParserUtil.getJSQLColumns(mt);
        Column column = columns.get(0);

        String fullSchemaColumnName = _tan.getFullSchemaColumnName(column);
        TypeConversion tc = _schema.getType(fullSchemaColumnName);

        //assume uniform distribution
        Object minValue = _schema.getRange(fullSchemaColumnName).getMin();
        Object maxValue = _schema.getRange(fullSchemaColumnName).getMax();
        double fullRange = tc.getDistance(maxValue, minValue);

        //on one of the sides we have to have a constant
        JSQLTypeConverter rightConverter = new JSQLTypeConverter();
        mt.getRightExpression().accept(rightConverter);
        Object currentValue = rightConverter.getResult();
        if(currentValue == null){
            JSQLTypeConverter leftConverter = new JSQLTypeConverter();
            mt.getLeftExpression().accept(leftConverter);
            currentValue = leftConverter.getResult();
        }
        double distance = tc.getDistance(currentValue, minValue);

        return distance/fullRange;
    }

    /*
     * computed using the basic ones (= and <)
     */
    public double estimate(NotEqualsTo ne){
        EqualsTo equals = new EqualsTo();
        equals.setLeftExpression(ne.getLeftExpression());
        equals.setRightExpression(ne.getRightExpression());

        return 1 - estimate(equals);
    }

    public double estimate(GreaterThanEquals gt){
        MinorThan minorThan = new MinorThan();
        minorThan.setLeftExpression(gt.getLeftExpression());
        minorThan.setRightExpression(gt.getRightExpression());

        return 1 - estimate(minorThan);
    }

    public double estimate(GreaterThan gt){
        EqualsTo equals = new EqualsTo();
        equals.setLeftExpression(gt.getLeftExpression());
        equals.setRightExpression(gt.getRightExpression());

        MinorThan minorThan = new MinorThan();
        minorThan.setLeftExpression(gt.getLeftExpression());
        minorThan.setRightExpression(gt.getRightExpression());

        return 1 - estimate(equals) - estimate(minorThan);
    }

    public double estimate(MinorThanEquals mte){
        EqualsTo equals = new EqualsTo();
        equals.setLeftExpression(mte.getLeftExpression());
        equals.setRightExpression(mte.getRightExpression());

        MinorThan minorThan = new MinorThan();
        minorThan.setLeftExpression(mte.getLeftExpression());
        minorThan.setRightExpression(mte.getRightExpression());

        return estimate(minorThan) + estimate(equals);
    }

    /*
     * And, Or expressions
     */
    public double estimate(OrExpression or){
        return estimate(or.getLeftExpression()) + estimate(or.getRightExpression());
    }

    public double estimate(AndExpression and){
        //the case when we have the same single column on both sides
        Expression leftExpr = and.getLeftExpression();
        List<Column> leftColumns = ParserUtil.getJSQLColumns(leftExpr);
        Column leftColumn = leftColumns.get(0);

        Expression rightExpr = and.getRightExpression();
        List<Column> rightColumns = ParserUtil.getJSQLColumns(rightExpr);
        Column rightColumn = rightColumns.get(0);

        if(leftColumn.toString().equals(rightColumn.toString())){
            //not using leftExpr and rightExpr, because we want to preserve type
            return 1 - (1 - estimate(and.getLeftExpression())) - (1 - estimate(and.getRightExpression()));
        }else{
            throw new RuntimeException("And expressions with different columns on two sides are not supported!");
            //for implementing this take a look at textbook and tpch7
        }

    }

    //just for testing purposes
    public static void main(String[] args){

        Table table = new Table();
        table.setName("ORDERS");
        List<Table> tableList = new ArrayList<Table>(Arrays.asList(table));

        Column column = new Column();
        column.setTable(table);
        column.setColumnName("ORDERDATE");

        MinorThan mt = new MinorThan();
        mt.setLeftExpression(column);
        mt.setRightExpression(new DateValue("d" + "1995-01-01" + "d"));
        
        SelingerSelectivityEstimator selEstimator = new SelingerSelectivityEstimator(new TPCH_Schema(), new TableAliasName(tableList));
        System.out.println(selEstimator.estimate(mt)); 
    }


}