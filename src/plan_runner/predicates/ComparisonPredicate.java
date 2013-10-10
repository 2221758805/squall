package plan_runner.predicates;

import java.util.ArrayList;
import java.util.List;

import plan_runner.conversion.IntegerConversion;
import plan_runner.expressions.Addition;
import plan_runner.expressions.ValueExpression;
import plan_runner.expressions.ValueSpecification;
import plan_runner.visitors.PredicateVisitor;

public class ComparisonPredicate<T extends Comparable<T>> implements Predicate {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	public static final int EQUAL_OP = 0;
	public static final int NONEQUAL_OP = 1;
	public static final int LESS_OP = 2;
	public static final int NONLESS_OP = 3;
	public static final int GREATER_OP = 4;
	public static final int NONGREATER_OP = 5;
	public static final int SYM_BAND_WITH_BOUNDS_OP = 6;
	public static final int SYM_BAND_NO_BOUNDS_OP = 7;

	public static final int BPLUSTREE = 0;
	public static final int BALANCEDBINARYTREE = 1;

	private Object _diff;
	private int indexType; // Either B+tree or BBinarytree

	private ValueExpression<T> _ve1, _ve2;
	private int _operation;

	public ComparisonPredicate(int operation, ValueExpression<T> ve1, ValueExpression<T> ve2) {
		_operation = operation;
		_ve1 = ve1;
		_ve2 = ve2;
	}

	public ComparisonPredicate(int operation, ValueExpression<T> ve1, ValueExpression<T> ve2,
			int difference) {
		this(operation, ve1, ve2);
		_diff = difference;
	}

	public ComparisonPredicate(int operation, ValueExpression<T> ve1, ValueExpression<T> ve2,
			int difference, int inequalityIndexType) {
		_operation = operation;
		_ve1 = ve1;
		_ve2 = new Addition(ve2, new ValueSpecification(new IntegerConversion(), difference));
		_diff = Integer.valueOf(-2 * difference);
		indexType = inequalityIndexType;
	}

	public ComparisonPredicate(ValueExpression<T> ve1, ValueExpression<T> ve2) {
		this(EQUAL_OP, ve1, ve2);
	}

	@Override
	public void accept(PredicateVisitor pv) {
		pv.visit(this);
	}

	public Object getDiff() {
		return _diff;
	}

	public List<ValueExpression> getExpressions() {
		final List<ValueExpression> result = new ArrayList<ValueExpression>();
		result.add(_ve1);
		result.add(_ve2);
		return result;
	}

	public int getIndexType() {
		return indexType;
	}

	@Override
	public List<Predicate> getInnerPredicates() {
		return new ArrayList<Predicate>();
	}

	public int getOperation() {
		return _operation;
	}

	private String getOperationStr() {
		String result = null;
		switch (_operation) {
		case NONEQUAL_OP:
			result = " != ";
			break;
		case EQUAL_OP:
			result = " = ";
			break;
		case LESS_OP:
			result = " < ";
			break;
		case NONLESS_OP:
			result = " >= ";
			break;
		case GREATER_OP:
			result = " > ";
			break;
		case NONGREATER_OP:
			result = " <= ";
			break;
		}
		return result;
	}

	public int getOperator(boolean inverse) {
		int result = 0;
		if (inverse)
			switch (_operation) {
			case NONEQUAL_OP:
				result = NONEQUAL_OP;
				break;
			case EQUAL_OP:
				result = EQUAL_OP;
				break;
			case LESS_OP:
				result = GREATER_OP;
				break;
			case NONLESS_OP:
				result = NONGREATER_OP;
				break;
			case GREATER_OP:
				result = LESS_OP;
				break;
			case NONGREATER_OP:
				result = NONLESS_OP;
				break;
			}
		else
			result = _operation;
		return result;
	}

	public T getType() {
		return (T) _ve1.getType().getInitialValue();
	}

	@Override
	public boolean test(List<String> tupleValues) {
		Comparable val1 = _ve1.eval(tupleValues);
		Comparable val2 = _ve2.eval(tupleValues);

		// All the Numeric types are converted to double,
		// because different types cannot be compared
		if (val1 instanceof Long)
			val1 = (((Long) val1).doubleValue());
		if (val2 instanceof Long)
			val2 = (((Long) val2).doubleValue());
		final int compared = val1.compareTo(val2);

		boolean result = false;
		switch (_operation) {
		case EQUAL_OP:
			result = (compared == 0);
			break;
		case NONEQUAL_OP:
			result = (compared != 0);
			break;
		case LESS_OP:
			result = (compared < 0);
			break;
		case NONLESS_OP:
			result = (compared >= 0);
			break;
		case GREATER_OP:
			result = (compared > 0);
			break;
		case NONGREATER_OP:
			result = (compared <= 0);
			break;
		default:
			throw new RuntimeException("Unsupported operation " + _operation);
		}
		return result;
	}

	@Override
	public boolean test(List<String> firstTupleValues, List<String> secondTupleValues) {
		final Comparable val1 = _ve1.eval(firstTupleValues);
		final Comparable val2 = _ve2.eval(secondTupleValues);
		final int compared = val1.compareTo(val2);

		boolean result = false;
		switch (_operation) {
		case EQUAL_OP:
			result = (compared == 0);
			break;
		case NONEQUAL_OP:
			result = (compared != 0);
			break;
		case LESS_OP:
			result = (compared < 0);
			break;
		case NONLESS_OP:
			result = (compared >= 0);
			break;
		case GREATER_OP:
			result = (compared > 0);
			break;
		case NONGREATER_OP:
			result = (compared <= 0);
			break;
		default:
			throw new RuntimeException("Unsupported operation " + _operation);
		}
		return result;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		sb.append(_ve1.toString());
		sb.append(getOperationStr());
		sb.append(_ve2.toString());
		return sb.toString();
	}

}