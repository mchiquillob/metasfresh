/*
 * #%L
 * de.metas.adempiere.adempiere.base
 * %%
 * Copyright (C) 2020 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

package org.adempiere.ad.dao.impl;

import de.metas.util.Check;
import de.metas.util.InSetPredicate;
import de.metas.util.lang.RepoIdAware;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import org.adempiere.ad.dao.ConstantQueryFilter;
import org.adempiere.ad.dao.ICompositeQueryFilter;
import org.adempiere.ad.dao.IInSubQueryFilterClause;
import org.adempiere.ad.dao.IQueryFilter;
import org.adempiere.ad.dao.IQueryFilterModifier;
import org.adempiere.ad.dao.ISqlQueryFilter;
import org.adempiere.ad.dao.impl.CompareQueryFilter.Operator;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.model.ModelColumn;
import org.compiere.model.IQuery;

import javax.annotation.Nullable;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Composite Query Filters. Contains a set of {@link IQueryFilter} joined together by AND or OR (see {@link #setJoinAnd()}, {@link #setJoinOr()}).
 * <p>
 * <b>Hint:</b> use {@link org.adempiere.ad.dao.IQueryBL#createCompositeQueryFilter(Class)} to obtain an instance.
 *
 * @param <T> model class type
 * @author tsa
 */
@EqualsAndHashCode(of = { "filters", "and", "_defaultAccept" })
		/* package */class CompositeQueryFilter<T> implements ICompositeQueryFilter<T>, ISqlQueryFilter
{
	/* package */static final String DEFAULT_SQL_TRUE = "1=1";
	/* package */static final String DEFAULT_SQL_FALSE = "1=0";
	/* package */static final String SQL_AND = " AND ";
	/* package */static final String SQL_OR = " OR ";

	private final String tableName;

	//
	// Status
	// NOTE: when adding new fields here, please update #copy() method too
	private final ArrayList<IQueryFilter<T>> filters = new ArrayList<>();
	private boolean and = true;
	private boolean _defaultAccept = true;

	//
	// Compiled data
	private boolean _compiled = false;
	private String _sqlWhereClause = null;
	private List<ISqlQueryFilter> _sqlFilters;
	private List<IQueryFilter<T>> _nonSqlFilters;
	private boolean _allowSqlFilters = true;

	private final ISqlQueryFilter partialSqlQueryFilter = new ISqlQueryFilter()
	{
		@Override
		public String toString()
		{
			return "PartialSqlQueryFilter["
					+ "sql=" + _sqlWhereClause
					+ "]";
		}

		@Override
		public String getSql()
		{
			return getSqlFiltersWhereClause();
		}

		@Override
		public List<Object> getSqlParams(final Properties ctx)
		{
			return getSqlFiltersParams(ctx);
		}
	};

	private final IQueryFilter<T> partialNonSqlQueryFilter = new IQueryFilter<T>()
	{
		@Override
		public String toString()
		{
			return "PartialNonSqlQueryFilter["
					+ _nonSqlFilters
					+ "]";
		}

		@Override
		public boolean accept(final T model)
		{
			final List<IQueryFilter<T>> nonSqlFilters = getNonSqlFiltersToUse();
			final boolean defaultAccept = isDefaultAccept();
			return CompositeQueryFilter.this.accept(model, nonSqlFilters, defaultAccept);
		}
	};

	public CompositeQueryFilter(final Class<T> modelClass)
	{
		this(InterfaceWrapperHelper.getTableName(modelClass));
	}

	CompositeQueryFilter(final String tableName)
	{
		this.tableName = tableName;
	}

	@Deprecated
	CompositeQueryFilter()
	{
		this.tableName = null; // N/A
	}

	@Override
	public String toString()
	{
		if (filters.isEmpty())
		{
			return "(" + _defaultAccept + ")";
		}

		final StringBuilder sb = new StringBuilder();
		for (final IQueryFilter<T> filter : filters)
		{
			if (sb.length() > 0)
			{
				sb.append(and ? SQL_AND : SQL_OR);
			}
			sb.append("(").append(filter).append(")");
		}

		return sb.insert(0, "(").append(")").toString();
	}

	@Override
	public ICompositeQueryFilter<T> copy()
	{
		final CompositeQueryFilter<T> copy = new CompositeQueryFilter<>(tableName);
		copy.filters.addAll(this.filters);
		copy.and = this.and;
		copy._defaultAccept = this._defaultAccept;
		copy._compiled = false;
		return copy;
	}

	private void compileIfNeeded()
	{
		//
		// Check: if is alread compiled, there is no need to compile it again
		if (_compiled)
		{
			return;
		}

		//
		// Check: if we have no filters, we can set the status right away
		if (filters.isEmpty())
		{
			_sqlWhereClause = isDefaultAccept() ? DEFAULT_SQL_TRUE : DEFAULT_SQL_FALSE;
			_sqlFilters = Collections.emptyList();
			_nonSqlFilters = null;
			_compiled = true;
			return;
		}

		final List<ISqlQueryFilter> resultSqlFilters = new ArrayList<>();
		final StringBuilder resultSqlWhereClause = new StringBuilder();
		final List<IQueryFilter<T>> resultNonSqlFilters = new ArrayList<>();
		final List<IQueryFilter<T>> resultAllFiltersSoFar = new ArrayList<>(filters.size());
		boolean allowSqlFilters = _allowSqlFilters; // do we allow SQL filter?

		for (final IQueryFilter<T> filter : filters)
		{
			final List<ISqlQueryFilter> sqlFilters;
			final List<IQueryFilter<T>> nonSqlFilters;

			//
			// Case: we are not accepting SQL filters
			// => no point to check forward but treat this filter as a nonSQL filter
			if (!allowSqlFilters)
			{
				sqlFilters = null;
				nonSqlFilters = Collections.singletonList(filter);
			}
			//
			// Case: Composite Filter (SQL and non-SQL)
			else if (filter instanceof ICompositeQueryFilter)
			{
				final ICompositeQueryFilter<T> compositeFilter = (ICompositeQueryFilter<T>)filter;

				// Case: our composite is a pure SQL filter
				if (compositeFilter.isPureSql())
				{
					final ISqlQueryFilter sqlFilter = compositeFilter.asSqlQueryFilter();
					sqlFilters = Collections.singletonList(sqlFilter);
					nonSqlFilters = null;
				}
				// Case: our composite is not a pure SQL filter but it's join method is AND
				// => we can mix this kind of filters
				else if (compositeFilter.isJoinAnd() == this.isJoinAnd() == true)
				{
					sqlFilters = compositeFilter.getSqlFilters();
					nonSqlFilters = compositeFilter.getNonSqlFilters();
				}
				// Case: our composite is not a pure SQL filter and it's join method is not AND
				// => we cannot mix SQL and nonSQL in this way so we consider the whole compositeFilter as non-sql
				else
				{
					sqlFilters = null;
					nonSqlFilters = Collections.singletonList(compositeFilter);
				}
			}
			//
			// Case: Pure SQL Filter
			else if (filter instanceof ISqlQueryFilter)
			{
				final ISqlQueryFilter sqlFilter = ISqlQueryFilter.cast(filter);
				sqlFilters = Collections.singletonList(sqlFilter);
				nonSqlFilters = null;
			}
			//
			// Case: non-SQL filter:
			else
			{
				sqlFilters = null;
				nonSqlFilters = Collections.singletonList(filter);
			}

			//
			// Append the SQL Part (if any)
			appendSqlWhereClause(resultSqlWhereClause, resultSqlFilters, sqlFilters);

			//
			// Append Non-SQL part (if any)
			if (nonSqlFilters != null && !nonSqlFilters.isEmpty())
			{
				resultNonSqlFilters.addAll(nonSqlFilters);
			}

			//
			// Update our all filters so far list
			resultAllFiltersSoFar.add(filter);

			//
			// Case: Until now we allowed SQL filters but our join method is not AND
			// ... and we already have SQL Filters and nonSQL filters accumulated
			//
			// => make all filters as nonSQL
			// => clear "resultNonSqlFilters" and "resultSqlWhereClause" because we will not use them from now on
			// => don't allow SQL filters from now on
			if (allowSqlFilters && !this.isJoinAnd() && !resultSqlFilters.isEmpty() && !resultNonSqlFilters.isEmpty())
			{
				resultNonSqlFilters.clear();
				resultNonSqlFilters.addAll(resultAllFiltersSoFar);
				resultSqlFilters.clear();
				resultSqlWhereClause.setLength(0);
				// we are not allowing mixing filters anymore
				// => we allow only nonSQL filters; all SQL filters will be considered nonSQL from now on
				allowSqlFilters = false;
			}
		}

		this._sqlFilters = Collections.unmodifiableList(resultSqlFilters);
		this._nonSqlFilters = Collections.unmodifiableList(resultNonSqlFilters);
		this._sqlWhereClause = resultSqlWhereClause.toString();
		this._compiled = true;
	}

	/**
	 * Append <code>sqlFiltersToAppend</code> to given:
	 * <ul>
	 * <li><code>resultSqlWhereClause</code> string builder/buffer
	 * <li>and <code>resultSqlFilters</code> list.
	 * </ul>
	 */
	private void appendSqlWhereClause(final StringBuilder resultSqlWhereClause,
									  final List<ISqlQueryFilter> resultSqlFilters,
									  final List<ISqlQueryFilter> sqlFiltersToAppend)
	{
		//
		// If there are no SQL filters to append, return right away
		if (sqlFiltersToAppend == null || sqlFiltersToAppend.isEmpty())
		{
			return;
		}

		//
		// Iterate SQL Filters to Append and
		// * update "resultSqlWhereClause" string buffer
		// * append the filter to resultSqlFilters
		for (final ISqlQueryFilter sqlFilterToAppend : sqlFiltersToAppend)
		{
			// Make sure our sqlFilter to append is not null
			// NOTE: we cannot skip it because maybe other logic was rellying on if "sqlFiltersToAppend" is empty or null
			Check.assumeNotNull(sqlFiltersToAppend, "sqlFiltersToAppend not null");

			final String sqlFilterWhereClause = sqlFilterToAppend.getSql();

			//
			// Skip filters which have an empty SQL
			// Shall not happen but we cannot control ISqlQueryFilter implementations
			if (Check.isEmpty(sqlFilterWhereClause, true))
			{
				continue;
			}

			//
			// Append the SQL where clause to given string buffer
			if (resultSqlWhereClause.length() > 0)
			{
				resultSqlWhereClause.append(and ? SQL_AND : SQL_OR);
			}
			resultSqlWhereClause.append("(").append(sqlFilterWhereClause).append(")");

			//
			// Append the SQL Filter to given SQL Filters current result
			resultSqlFilters.add(sqlFilterToAppend);
		}

	}

	@Override
	public final ICompositeQueryFilter<T> setDefaultAccept(final boolean defaultAccept)
	{
		if (this._defaultAccept == defaultAccept)
		{
			return this;
		}

		this._defaultAccept = defaultAccept;

		// recompile needed
		this._compiled = false;

		return this;
	}

	@Override
	public final boolean isDefaultAccept()
	{
		return _defaultAccept;
	}

	@Override
	public boolean isEmpty()
	{
		return filters.isEmpty();
	}

	@Override
	public int getFiltersCount()
	{
		return filters.size();
	}

	@Override
	public List<IQueryFilter<T>> getFilters()
	{
		return new ArrayList<>(filters);
	}

	@Override
	public ICompositeQueryFilter<T> addFilter(@NonNull final IQueryFilter<T> filter)
	{
		Check.errorIf(filter == this, "Attempt to add a filter to itself; filter={}", filter);

		if (filters.contains(filter))
		{
			return this;
		}

		filters.add(filter);

		// recompile needed
		this._compiled = false;

		return this;
	}

	@Override
	public ICompositeQueryFilter<T> addFilters(final List<IQueryFilter<T>> filters)
	{
		if (filters == null || filters.isEmpty())
		{
			return this;
		}

		for (final IQueryFilter<T> filter : filters)
		{
			addFilter(filter);
		}

		return this;
	}

	@Override
	public ICompositeQueryFilter<T> addFiltersUnboxed(final ICompositeQueryFilter<T> compositeFilter)
	{
		final List<IQueryFilter<T>> filtersToAdd = compositeFilter.getFilters();
		if (filtersToAdd.isEmpty())
		{
			return this;
		}
		else if (filtersToAdd.size() == 1)
		{
			return addFilters(filtersToAdd);
		}
		else if (isJoinAnd() == compositeFilter.isJoinAnd())
		{
			return addFilters(filtersToAdd);
		}
		else
		{
			return addFilter(compositeFilter);
		}
	}

	@Override
	public ICompositeQueryFilter<T> removeFilter(@NonNull final IQueryFilter<T> filter)
	{
		if (!filters.isEmpty())
		{
			filters.remove(filter);
		}

		return this;
	}

	@Override
	public ICompositeQueryFilter<T> setJoinAnd()
	{
		return setJoinAnd(true);
	}

	@Override
	public ICompositeQueryFilter<T> setJoinOr()
	{
		return setJoinAnd(false);
	}

	private ICompositeQueryFilter<T> setJoinAnd(final boolean and)
	{
		if (this.and == and)
		{
			return this;
		}

		this.and = and;

		// recompile needed
		this._compiled = false;

		return this;
	}

	@Override
	public ICompositeQueryFilter<T> addEqualsFilter(final String columnName, @Nullable final Object value)
	{
		final EqualsQueryFilter<T> filter = new EqualsQueryFilter<>(columnName, value);
		return addFilter(filter);
	}

	@Override
	public ICompositeQueryFilter<T> addEqualsFilter(final ModelColumn<T, ?> column, @Nullable final Object value)
	{
		final EqualsQueryFilter<T> filter = new EqualsQueryFilter<>(column.getColumnName(), value);
		return addFilter(filter);
	}

	@Override
	public ICompositeQueryFilter<T> addEqualsFilter(final String columnName, final Object value, final IQueryFilterModifier modifier)
	{
		final EqualsQueryFilter<T> filter = new EqualsQueryFilter<>(columnName, value, modifier);
		return addFilter(filter);
	}

	@Override
	public ICompositeQueryFilter<T> addEqualsFilter(final ModelColumn<T, ?> column, final Object value, final IQueryFilterModifier modifier)
	{
		final String columnName = column.getColumnName();
		return addEqualsFilter(columnName, value, modifier);
	}

	@Override
	public ICompositeQueryFilter<T> addStringLikeFilter(final ModelColumn<T, ?> column, final String substring, final boolean ignoreCase)
	{
		final String columnName = column.getColumnName();
		return addStringLikeFilter(columnName, substring, ignoreCase);
	}

	@Override
	public ICompositeQueryFilter<T> addStringLikeFilter(final String columnName, final String substring, final boolean ignoreCase)
	{
		final StringLikeFilter<T> filter = new StringLikeFilter<>(columnName, substring, ignoreCase);
		return addFilter(filter);
	}

	@Override
	public ICompositeQueryFilter<T> addCoalesceEqualsFilter(final Object value, final String... columnNames)
	{
		final CoalesceEqualsQueryFilter<T> filter = new CoalesceEqualsQueryFilter<>(value, columnNames);
		return addFilter(filter);
	}

	@Override
	public ICompositeQueryFilter<T> addNotEqualsFilter(final String columnName, final Object value)
	{
		final NotEqualsQueryFilter<T> filter = NotEqualsQueryFilter.of(columnName, value);
		return addFilter(filter);
	}

	@Override
	public ICompositeQueryFilter<T> addNotEqualsFilter(final ModelColumn<T, ?> column, final Object value)
	{
		final String columnName = column.getColumnName();
		return addNotEqualsFilter(columnName, value);
	}

	@Override
	public ICompositeQueryFilter<T> addNotNull(final String columnName)
	{
		return addNotEqualsFilter(columnName, null);
	}

	@Override
	public ICompositeQueryFilter<T> addNotNull(final ModelColumn<T, ?> column)
	{
		final String columnName = column.getColumnName();
		return addNotEqualsFilter(columnName, null);
	}

	@Override
	public ICompositeQueryFilter<T> addCompareFilter(final String columnName, final Operator operator, final @Nullable Object value)
	{
		final CompareQueryFilter<T> filter = new CompareQueryFilter<>(columnName, operator, value);
		return addFilter(filter);
	}

	@Override
	public ICompositeQueryFilter<T> addCompareFilter(final ModelColumn<T, ?> column, final Operator operator, final @Nullable Object value)
	{
		final CompareQueryFilter<T> filter = new CompareQueryFilter<>(column.getColumnName(), operator, value);
		return addFilter(filter);
	}

	@Override
	public ICompositeQueryFilter<T> addCompareFilter(final String columnName, final CompareQueryFilter.Operator operator, final Object value, final IQueryFilterModifier modifier)
	{
		final CompareQueryFilter<T> filter = new CompareQueryFilter<>(columnName, operator, value, modifier);
		return addFilter(filter);
	}

	@Override
	public ICompositeQueryFilter<T> addCompareFilter(final ModelColumn<T, ?> column, final Operator operator, final Object value, final IQueryFilterModifier modifier)
	{
		return addCompareFilter(column.getColumnName(), operator, value, modifier);
	}

	@Override
	public ICompositeQueryFilter<T> addOnlyActiveRecordsFilter()
	{
		final IQueryFilter<T> filter = ActiveRecordQueryFilter.getInstance();
		return addFilter(filter);
	}

	@Override
	public ICompositeQueryFilter<T> addOnlyContextClient(final Properties ctx)
	{
		final IQueryFilter<T> filter = new ContextClientQueryFilter<>(ctx);
		return addFilter(filter);
	}

	@Override
	public ICompositeQueryFilter<T> addOnlyContextClientOrSystem(final Properties ctx)
	{
		final boolean includeSystemClient = true;
		final IQueryFilter<T> filter = new ContextClientQueryFilter<>(ctx, includeSystemClient);
		return addFilter(filter);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <V> ICompositeQueryFilter<T> addInArrayOrAllFilter(final String columnName, final V... values)
	{
		final IQueryFilter<T> filter = new InArrayQueryFilter<T>(columnName, Arrays.asList(values))
				.setDefaultReturnWhenEmpty(true);
		return addFilter(filter);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <V> ICompositeQueryFilter<T> addInArrayFilter(final String columnName, final V... values)
	{
		final IQueryFilter<T> filter = new InArrayQueryFilter<T>(columnName, Arrays.asList(values))
				.setDefaultReturnWhenEmpty(false);
		return addFilter(filter);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <V> ICompositeQueryFilter<T> addInArrayOrAllFilter(final ModelColumn<T, ?> column, final V... values)
	{
		final IQueryFilter<T> filter = new InArrayQueryFilter<T>(column.getColumnName(), Arrays.asList(values))
				.setDefaultReturnWhenEmpty(true);
		return addFilter(filter);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <V> ICompositeQueryFilter<T> addInArrayFilter(final ModelColumn<T, ?> column, final V... values)
	{
		final IQueryFilter<T> filter = new InArrayQueryFilter<T>(column.getColumnName(), Arrays.asList(values))
				.setDefaultReturnWhenEmpty(false);
		return addFilter(filter);
	}

	@Override
	public <V> ICompositeQueryFilter<T> addInArrayOrAllFilter(final String columnName, final Collection<V> values)
	{
		final IQueryFilter<T> filter = new InArrayQueryFilter<T>(columnName, values)
				.setDefaultReturnWhenEmpty(true);
		return addFilter(filter);
	}

	@Override
	public <V> ICompositeQueryFilter<T> addInArrayFilter(final String columnName, final Collection<V> values)
	{
		final IQueryFilter<T> filter = new InArrayQueryFilter<T>(columnName, values)
				.setDefaultReturnWhenEmpty(false);
		return addFilter(filter);
	}

	@Override
	public <V extends RepoIdAware> ICompositeQueryFilter<T> addInArrayFilter(@NonNull final String columnName, @NonNull final InSetPredicate<V> values)
	{
		if (values.isAny())
		{
			return addFilter(ConstantQueryFilter.of(true));
		}
		else if (values.isNone())
		{
			return addFilter(ConstantQueryFilter.of(false));
		}
		else
		{
			return addInArrayFilter(columnName, values.toSet());
		}
	}

	@Override
	public <V> ICompositeQueryFilter<T> addInArrayOrAllFilter(final ModelColumn<T, ?> column, final Collection<V> values)
	{
		final IQueryFilter<T> filter = new InArrayQueryFilter<T>(column.getColumnName(), values)
				.setDefaultReturnWhenEmpty(true);
		return addFilter(filter);
	}

	@Override
	public <V> ICompositeQueryFilter<T> addInArrayFilter(final ModelColumn<T, ?> column, final Collection<V> values)
	{
		final IQueryFilter<T> filter = new InArrayQueryFilter<T>(column.getColumnName(), values)
				.setDefaultReturnWhenEmpty(false);
		return addFilter(filter);
	}

	@Override
	public <V> ICompositeQueryFilter<T> addNotInArrayFilter(final ModelColumn<T, ?> column, final Collection<V> values)
	{
		return addNotInArrayFilter(column.getColumnName(), values);
	}

	@Override
	public <V> ICompositeQueryFilter<T> addNotInArrayFilter(final String columnName, final Collection<V> values)
	{
		final InArrayQueryFilter<T> filter = new InArrayQueryFilter<>(columnName, values);

		// NOTE: in case the values collection is empty then
		// InArrayQueryFilter shall return false,
		// so negativing the expression it will "true",
		// which actually is the intuitive result
		// i.e. when there are no values then "not in array" shall return "true".
		filter.setDefaultReturnWhenEmpty(false);
		final IQueryFilter<T> notFilter = NotQueryFilter.of(filter);
		return addFilter(notFilter);
	}

	@Override
	public IInSubQueryFilterClause<T, ICompositeQueryFilter<T>> addInSubQueryFilter()
	{
		return new InSubQueryFilterClause<>(tableName, this, this::addFilter);
	}

	@Override
	public <ST> ICompositeQueryFilter<T> addInSubQueryFilter(final String columnName,
															 final String subQueryColumnName,
															 final IQuery<ST> subQuery)
	{
		final IQueryFilter<T> filter = InSubQueryFilter.<T>builder()
				.tableName(tableName)
				.subQuery(subQuery)
				.matchingColumnNames(columnName, subQueryColumnName)
				.build();
		return addFilter(filter);
	}

	@Override
	public <ST> ICompositeQueryFilter<T> addNotInSubQueryFilter(final String columnName,
																final String subQueryColumnName,
																final IQuery<ST> subQuery)
	{
		final IQueryFilter<T> filter = InSubQueryFilter.<T>builder()
				.tableName(tableName)
				.subQuery(subQuery)
				.matchingColumnNames(columnName, subQueryColumnName)
				.build();
		final IQueryFilter<T> notFilter = NotQueryFilter.of(filter);
		return addFilter(notFilter);
	}

	@Override
	public <ST> ICompositeQueryFilter<T> addNotInSubQueryFilter(final ModelColumn<T, ?> column,
																final ModelColumn<ST, ?> subQueryColumn,
																final IQuery<ST> subQuery)
	{
		final IQueryFilter<T> filter = InSubQueryFilter.<T>builder()
				.tableName(tableName)
				.subQuery(subQuery)
				.matchingColumnNames(column.getColumnName(), subQueryColumn.getColumnName())
				.build();

		final IQueryFilter<T> notFilter = NotQueryFilter.of(filter);
		return addFilter(notFilter);
	}

	@Override
	public <ST> ICompositeQueryFilter<T> addInSubQueryFilter(final ModelColumn<T, ?> column,
															 final ModelColumn<ST, ?> subQueryColumn,
															 final IQuery<ST> subQuery)
	{
		final IQueryFilter<T> filter = InSubQueryFilter.<T>builder()
				.tableName(tableName)
				.subQuery(subQuery)
				.matchingColumnNames(column.getColumnName(), subQueryColumn.getColumnName())
				.build();

		return addFilter(filter);
	}

	@Override
	public ICompositeQueryFilter<T> addEndsWithQueryFilter(final String columnName, final String endsWithString)
	{
		final EndsWithQueryFilter<T> filter = new EndsWithQueryFilter<>(columnName, endsWithString);
		return addFilter(filter);
	}

	@Override
	public <ST> ICompositeQueryFilter<T> addInSubQueryFilter(final String columnName,
															 final IQueryFilterModifier modifier,
															 final String subQueryColumnName,
															 final IQuery<ST> subQuery)
	{
		final IQueryFilter<T> filter = InSubQueryFilter.<T>builder()
				.tableName(tableName)
				.subQuery(subQuery)
				.matchingColumnNames(columnName, subQueryColumnName, modifier)
				.build();
		return addFilter(filter);
	}

	@Override
	public boolean accept(final T model)
	{
		final boolean defaultAccept = isDefaultAccept();
		return accept(model, filters, defaultAccept);
	}

	private boolean accept(final T model, final List<IQueryFilter<T>> filters, final boolean defaultAccept)
	{
		if (filters == null || filters.isEmpty())
		{
			return defaultAccept;
		}

		for (final IQueryFilter<T> filter : filters)
		{
			final boolean accepted = filter.accept(model);
			if (and && !accepted)
			{
				return false;
			}
			else if (!and && accepted)
			{
				return true;
			}
		}

		//noinspection RedundantIfStatement
		if (and)
		{
			return true;
		}
		else
		{
			return false;
		}

	}

	@Override
	public String getSqlFiltersWhereClause()
	{
		compileIfNeeded();
		return _sqlWhereClause;
	}

	@Override
	public List<Object> getSqlFiltersParams(final Properties ctx)
	{
		final List<ISqlQueryFilter> filters = getSqlFilters();

		if (filters == null || filters.isEmpty())
		{
			return Collections.emptyList();
		}

		final List<Object> params = new ArrayList<>();
		for (final ISqlQueryFilter sqlFilter : filters)
		{
			final String sqlFilterWhereClause = sqlFilter.getSql();
			if (!Check.isEmpty(sqlFilterWhereClause, true))
			{
				params.addAll(sqlFilter.getSqlParams(ctx));
			}
		}

		return params;
	}

	@Override
	public final List<IQueryFilter<T>> getNonSqlFilters()
	{
		compileIfNeeded();
		return _nonSqlFilters;
	}

	/**
	 * Gets nonSQL filters (see {@link #getNonSqlFilters()}) but it also makes sure we can use them.
	 *
	 * @return nonSQL filters
	 */
	private List<IQueryFilter<T>> getNonSqlFiltersToUse()
	{
		final List<ISqlQueryFilter> sqlFilters = getSqlFilters();
		final List<IQueryFilter<T>> nonSqlFilters = getNonSqlFilters();

		if (!and
				&& sqlFilters != null && !sqlFilters.isEmpty()
				&& nonSqlFilters != null && !nonSqlFilters.isEmpty())
		{
			throw new IllegalStateException("Cannot create a partial nonSQL filter when this filter has join method OR and it also have SQL filters: " + this);
		}

		if (!isDefaultAccept())
		{
			throw new IllegalStateException("Cannot create a partial nonSQL filter when this filter DefaultAccept=false: " + this);
		}

		return nonSqlFilters;
	}

	@Override
	public final List<ISqlQueryFilter> getSqlFilters()
	{
		compileIfNeeded();
		return _sqlFilters;
	}

	@Override
	public final boolean isPureSql()
	{
		// Case: a composite filter without any filters inside shall be considered pure SQL
		if (filters.isEmpty())
		{
			return true;
		}

		final List<IQueryFilter<T>> nonSqlFilters = getNonSqlFilters();
		return nonSqlFilters == null || nonSqlFilters.isEmpty();
	}

	@Override
	public final boolean isPureNonSql()
	{
		// Case: a composite filter without any filters inside shall not be considered pure nonSQL
		if (filters.isEmpty())
		{
			return false;
		}

		final List<ISqlQueryFilter> sqlFilters = getSqlFilters();
		return sqlFilters == null || sqlFilters.isEmpty();
	}

	@Override
	public boolean isJoinAnd()
	{
		return and;
	}

	@Override
	public boolean isJoinOr()
	{
		return !and;
	}

	@Override
	public String getSql()
	{
		if (!isPureSql())
		{
			throw new IllegalStateException("Cannot get SQL for a filter which is not pure SQL: " + this);
		}
		return getSqlFiltersWhereClause();
	}

	@Override
	public List<Object> getSqlParams(final Properties ctx)
	{
		if (!isPureSql())
		{
			throw new IllegalStateException("Cannot get SQL Parameters for a filter which is not pure SQL: " + this);
		}
		return getSqlFiltersParams(ctx);
	}

	@Override
	public ISqlQueryFilter asSqlQueryFilter()
	{
		if (!isPureSql())
		{
			throw new IllegalStateException("Cannot convert to pure SQL filter when this filter is not pure SQL: " + this);
		}
		return this;
	}

	@Override
	public ISqlQueryFilter asPartialSqlQueryFilter()
	{
		return partialSqlQueryFilter;
	}

	@Override
	public IQueryFilter<T> asPartialNonSqlFilterOrNull()
	{
		final List<IQueryFilter<T>> nonSqlFilters = getNonSqlFiltersToUse();
		if (nonSqlFilters == null || nonSqlFilters.isEmpty())
		{
			return null;
		}

		return partialNonSqlQueryFilter;
	}

	@Override
	public ICompositeQueryFilter<T> addBetweenFilter(final String columnName, final Object valueFrom, final Object valueTo)
	{
		final BetweenQueryFilter<T> filter = new BetweenQueryFilter<>(tableName, columnName, valueFrom, valueTo);
		return addFilter(filter);
	}

	@Override
	public ICompositeQueryFilter<T> addBetweenFilter(final ModelColumn<T, ?> column, final Object valueFrom, final Object valueTo)
	{
		final BetweenQueryFilter<T> filter = new BetweenQueryFilter<>(column, valueFrom, valueTo);
		return addFilter(filter);
	}

	@Override
	public ICompositeQueryFilter<T> addBetweenFilter(final String columnName, final Object valueFrom, final Object valueTo, final IQueryFilterModifier modifier)
	{
		final BetweenQueryFilter<T> filter = new BetweenQueryFilter<>(tableName, columnName, valueFrom, valueTo, modifier);
		return addFilter(filter);
	}

	@Override
	public ICompositeQueryFilter<T> addBetweenFilter(final ModelColumn<T, ?> column, final Object valueFrom, final Object valueTo, final IQueryFilterModifier modifier)
	{
		final BetweenQueryFilter<T> filter = new BetweenQueryFilter<>(column, valueFrom, valueTo, modifier);
		return addFilter(filter);
	}

	@Override
	public ICompositeQueryFilter<T> addValidFromToMatchesFilter(final ModelColumn<T, ?> validFromColumn, final ModelColumn<T, ?> validToColumn, final Date dateToMatch)
	{
		final ValidFromToMatchesQueryFilter<T> filter = new ValidFromToMatchesQueryFilter<>(validFromColumn, validToColumn, dateToMatch);
		return addFilter(filter);
	}

	@Override
	public ICompositeQueryFilter<T> addCompositeQueryFilter()
	{
		final ICompositeQueryFilter<T> filter = new CompositeQueryFilter<>(tableName);
		addFilter(filter);
		return filter;
	}

	@Override
	public CompositeQueryFilter<T> allowSqlFilters(final boolean allowSqlFilters)
	{
		if (this._allowSqlFilters == allowSqlFilters)
		{
			return this;
		}

		this._allowSqlFilters = allowSqlFilters;
		_compiled = false;
		return this;

	}

	@Override
	public CompositeQueryFilter<T> addIntervalIntersection(
			@NonNull final String lowerBoundColumnName,
			@NonNull final String upperBoundColumnName,
			@Nullable final ZonedDateTime lowerBoundValue,
			@Nullable final ZonedDateTime upperBoundValue)
	{
		addIntervalIntersection(
				lowerBoundColumnName,
				upperBoundColumnName,
				lowerBoundValue != null ? lowerBoundValue.toInstant() : null,
				upperBoundValue != null ? upperBoundValue.toInstant() : null);

		return this;
	}

	@Override
	public CompositeQueryFilter<T> addIntervalIntersection(
			@NonNull final String lowerBoundColumnName,
			@NonNull final String upperBoundColumnName,
			@Nullable final Instant lowerBoundValue,
			@Nullable final Instant upperBoundValue)
	{
		addFilter(new DateIntervalIntersectionQueryFilter<>(
				ModelColumnNameValue.forColumnName(lowerBoundColumnName),
				ModelColumnNameValue.forColumnName(upperBoundColumnName),
				lowerBoundValue,
				upperBoundValue));
		return this;
	}
}
