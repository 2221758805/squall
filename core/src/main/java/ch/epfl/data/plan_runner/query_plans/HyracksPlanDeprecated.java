package ch.epfl.data.plan_runner.query_plans;

import java.util.Map;

import ch.epfl.data.plan_runner.components.Component;
import ch.epfl.data.plan_runner.operators.AggregateCountOperator;
import ch.epfl.data.plan_runner.operators.ProjectOperator;

public class HyracksPlanDeprecated {

	private final QueryBuilder _queryBuilder = new QueryBuilder();

	public HyracksPlanDeprecated(Map conf) {
		// -------------------------------------------------------------------------------------
		Component relationCustomer = _queryBuilder
				.createDataSource("customer", conf)
				.add(new ProjectOperator(0, 6)).setOutputPartKey(0);

		// -------------------------------------------------------------------------------------
		Component relationOrders = _queryBuilder
				.createDataSource("orders", conf).add(new ProjectOperator(1))
				.setOutputPartKey(0);

		// -------------------------------------------------------------------------------------
		_queryBuilder.createEquiJoin(relationCustomer, relationOrders).add(
				new AggregateCountOperator(conf).setGroupByColumns(1));
		// -------------------------------------------------------------------------------------
	}

	public QueryBuilder getQueryBuilder() {
		return _queryBuilder;
	}
}