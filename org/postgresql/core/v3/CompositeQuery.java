/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2004, Open Cloud Limited.
 *
 * IDENTIFICATION
 *	  $PostgreSQL: pgjdbc/org/postgresql/core/v3/CompositeQuery.java,v 1.1 2004/06/29 06:43:25 jurka Exp $
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.core.v3;

import org.postgresql.core.*;

/**
 * V3 Query implementation for queries that involve multiple statements.
 * We split it up into one SimpleQuery per statement, and wrap the
 * corresponding per-statement SimpleParameterList objects in
 * a CompositeParameterList.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
class CompositeQuery implements V3Query {
	CompositeQuery(SimpleQuery[] subqueries, int[] offsets) {
		this.subqueries = subqueries;
		this.offsets = offsets;
	}
	
	public ParameterList createParameterList() {
		SimpleParameterList[] subparams = new SimpleParameterList[subqueries.length];
		for (int i = 0; i < subqueries.length; ++i)
			subparams[i] = (SimpleParameterList)subqueries[i].createParameterList();
		return new CompositeParameterList(subparams, offsets);
	}

	public String toString(ParameterList parameters) {
		StringBuffer sbuf = new StringBuffer(subqueries[0].toString());
		for (int i = 1; i < subqueries.length; ++i) {
			sbuf.append(';');
			sbuf.append(subqueries[i]);
		}
		return sbuf.toString();
	}

	public String toString() {
		return toString(null);
	}

	public void close() {
		for (int i = 0; i < subqueries.length; ++i)
			subqueries[i].close();
	}

	public SimpleQuery[] getSubqueries() { 
		return subqueries;
	}

	private final SimpleQuery[] subqueries;
	private final int[] offsets;
}		
