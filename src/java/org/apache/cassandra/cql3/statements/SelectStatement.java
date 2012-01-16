/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cassandra.cql3.statements;

import java.nio.ByteBuffer;
import java.util.*;

import org.apache.cassandra.cql3.*;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.db.IColumn;
import org.apache.cassandra.db.CounterColumn;
import org.apache.cassandra.db.Row;
import org.apache.cassandra.db.Table;
import org.apache.cassandra.db.context.CounterContext;
import org.apache.cassandra.db.marshal.CompositeType;
import org.apache.cassandra.db.marshal.TypeParser;
import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.CqlMetadata;
import org.apache.cassandra.thrift.CqlResult;
import org.apache.cassandra.thrift.CqlRow;
import org.apache.cassandra.thrift.IndexExpression;
import org.apache.cassandra.thrift.IndexOperator;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.thrift.RequestType;
import org.apache.cassandra.thrift.ThriftValidation;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.Pair;

/**
 * Encapsulates a completely parsed SELECT query, including the target
 * column family, expression, result count, and ordering clause.
 *
 */
public class SelectStatement
{
    private final static ByteBuffer countColumn = ByteBufferUtil.bytes("count");

    public final CFDefinition cfDef;
    public final Parameters parameters;
    private final List<Pair<CFDefinition.Name, ColumnIdentifier>> selectedNames = new ArrayList<Pair<CFDefinition.Name, ColumnIdentifier>>(); // empty => wildcard
    private final Map<ColumnIdentifier, Restriction> restrictions = new HashMap<ColumnIdentifier, Restriction>();
    private boolean hasIndexedExpression;

    public SelectStatement(CFDefinition cfDef, Parameters parameters)
    {
        this.cfDef = cfDef;
        this.parameters = parameters;
    }

    public String keyspace()
    {
        return cfDef.cfm.ksName;
    }

    public String columnFamily()
    {
        return cfDef.cfm.cfName;
    }

    public ConsistencyLevel getConsistencyLevel()
    {
        return parameters.consistencyLevel;
    }

    public int getLimit()
    {
        // For sparse, we'll end up merging all defined colums into the same CqlRow. Thus we should query up
        // to 'defined columns' * 'asked limit' to be sure to have enough columns. We'll trim after query if
        // this end being too much.
        if (!cfDef.isCompact())
            return cfDef.metadata.size() * parameters.limit;
        return parameters.limit;
    }

    public boolean isColumnsReversed()
    {
        return parameters.isColumnsReversed;
    }

    public boolean isKeyRange()
    {
        if (hasIndexedExpression)
            return true;

        Restriction r = restrictions.get(cfDef.key.name);
        return r == null || !r.isEquality();
    }

    public Collection<ByteBuffer> getKeys(final List<ByteBuffer> variables) throws InvalidRequestException
    {
        final Restriction r = restrictions.get(cfDef.key.name);
        if (r == null || !r.isEquality())
            throw new IllegalStateException();

        List<ByteBuffer> keys = new ArrayList(r.eqValues.size());
        for (Term t : r.eqValues)
            keys.add(t.getByteBuffer(cfDef.key.type, variables));
        return keys;
    }

    public ByteBuffer getKeyStart(List<ByteBuffer> variables) throws InvalidRequestException
    {
        Restriction r = restrictions.get(cfDef.key.name);
        if (r == null)
        {
            return null;
        }
        else if (r.isEquality())
        {
            assert r.eqValues.size() == 1;
            return r.eqValues.get(0).getByteBuffer(cfDef.key.type, variables);
        }
        else
        {
            return r.start == null ? ByteBufferUtil.EMPTY_BYTE_BUFFER : r.start.getByteBuffer(cfDef.key.type, variables);
        }
    }

    public boolean includeStartKey()
    {
        Restriction r = restrictions.get(cfDef.key.name);
        if (r == null || r.isEquality())
            return true;
        else
            return r.startInclusive;
    }

    public ByteBuffer getKeyFinish(List<ByteBuffer> variables) throws InvalidRequestException
    {
        Restriction r = restrictions.get(cfDef.key.name);
        if (r == null)
        {
            return null;
        }
        else if (r.isEquality())
        {
            assert r.eqValues.size() == 1;
            return r.eqValues.get(0).getByteBuffer(cfDef.key.type, variables);
        }
        else
        {
            return r.end == null ? ByteBufferUtil.EMPTY_BYTE_BUFFER : r.end.getByteBuffer(cfDef.key.type, variables);
        }
    }

    public boolean includeFinishKey()
    {
        Restriction r = restrictions.get(cfDef.key.name);
        if (r == null || r.isEquality())
            return true;
        else
            return r.endInclusive;
    }

    public boolean isColumnRange()
    {
        // Static CF never entails a column slice
        if (cfDef.kind == CFDefinition.Kind.STATIC)
            return false;

        // Otherwise, it is a range query if it has at least one the column alias
        // for which no relation is defined or is not EQ.
        for (CFDefinition.Name name : cfDef.columns.values())
        {
            Restriction r = restrictions.get(name.name);
            if (r == null || !r.isEquality())
                return true;
        }
        return false;
    }

    public boolean isWildcard()
    {
        return selectedNames.isEmpty();
    }

    public List<ByteBuffer> getRequestedColumns(List<ByteBuffer> variables) throws InvalidRequestException
    {
        assert !isColumnRange();

        CompositeType.Builder builder = null;
        if (cfDef.isComposite())
        {
            builder = new CompositeType.Builder((CompositeType)cfDef.cfm.comparator);
            for (CFDefinition.Name name : cfDef.columns.values())
            {
                Restriction r = restrictions.get(name.name);
                assert r != null && r.isEquality() && r.eqValues.size() == 1;
                builder.add(r.eqValues.get(0), Relation.Type.EQ, variables);
            }
        }

        switch (cfDef.kind)
        {
            case STATIC:
            case SPARSE:
                List<ByteBuffer> columns = new ArrayList<ByteBuffer>();
                // Adds all (requested) columns
                for (Pair<CFDefinition.Name, ColumnIdentifier> p : getExpandedSelection())
                {
                    CFDefinition.Name name = p.left;
                    // Skip everything that is not a 'metadata' column
                    if (name.kind != CFDefinition.Name.Kind.COLUMN_METADATA)
                        continue;
                    ByteBuffer cname = builder == null ? name.name.key : builder.copy().add(name.name.key).build();
                    columns.add(cname);
                }
                return columns;
            case DYNAMIC:
                CFDefinition.Name name = cfDef.columnNameForDynamic();
                Restriction r = restrictions.get(name.name);
                assert r != null && r.isEquality() && r.eqValues.size() == 1;
                return Collections.singletonList(r.eqValues.get(0).getByteBuffer(name.type, variables));
            case DENSE:
                return Collections.singletonList(builder.build());
        }
        throw new AssertionError();
    }

    public ByteBuffer getRequestedStart(List<ByteBuffer> variables) throws InvalidRequestException
    {
        return getRequestedBound(true, variables);
    }

    public ByteBuffer getRequestedFinish(List<ByteBuffer> variables) throws InvalidRequestException
    {
        return getRequestedBound(false, variables);
    }

    private ByteBuffer getRequestedBound(boolean isStart, List<ByteBuffer> variables) throws InvalidRequestException
    {
        assert isColumnRange();

        CompositeType.Builder builder = null;
        if (cfDef.isComposite())
        {
            builder = new CompositeType.Builder((CompositeType)cfDef.cfm.comparator);
            for (CFDefinition.Name name : cfDef.columns.values())
            {
                Restriction r = restrictions.get(name.name);
                if (r == null)
                    break;

                if (r.isEquality())
                {
                    assert r.eqValues.size() == 1;
                    builder.add(r.eqValues.get(0), Relation.Type.EQ, variables);
                }
                else
                {
                    Term t = isStart ? r.start : r.end;
                    Relation.Type op = isStart
                                     ? (r.startInclusive ? Relation.Type.GTE : Relation.Type.GT)
                                     : (r.endInclusive ? Relation.Type.LTE : Relation.Type.LT);
                    if (t != null)
                        builder.add(t, op, variables);
                    break;
                }
            }
        }

        switch (cfDef.kind)
        {
            case STATIC:
                // only wildard should entail a slice query
                assert isWildcard();
                return ByteBufferUtil.EMPTY_BYTE_BUFFER;
            case DYNAMIC:
                CFDefinition.Name name = cfDef.columnNameForDynamic();
                Restriction r = restrictions.get(name.name);
                assert r == null || !r.isEquality();
                if (r == null)
                    return ByteBufferUtil.EMPTY_BYTE_BUFFER;
                Term t = isStart ? r.start : r.end;
                if (t == null)
                    return ByteBufferUtil.EMPTY_BYTE_BUFFER;
                Relation.Type op = isStart
                                 ? (r.startInclusive ? Relation.Type.GTE : Relation.Type.GT)
                                 : (r.endInclusive ? Relation.Type.LTE : Relation.Type.LT);
                return t.getByteBuffer(name.type, variables);
            case SPARSE:
            case DENSE:
                return builder.build();
        }
        throw new AssertionError();
    }

    public List<IndexExpression> getIndexExpressions(List<ByteBuffer> variables) throws InvalidRequestException
    {
        if (!hasIndexedExpression)
            return Collections.<IndexExpression>emptyList();

        List<IndexExpression> expressions = new ArrayList<IndexExpression>();
        for (CFDefinition.Name name : cfDef.metadata.values())
        {
            Restriction restriction = restrictions.get(name.name);
            if (restriction == null)
                continue;

            if (restriction.isEquality())
            {
                for (Term t : restriction.eqValues)
                {
                    ByteBuffer value = t.getByteBuffer(name.type, variables);
                    expressions.add(new IndexExpression(name.name.key, IndexOperator.EQ, value));
                }
            }
            else
            {
                if (restriction.start != null)
                {
                    ByteBuffer value = restriction.start.getByteBuffer(name.type, variables);
                    expressions.add(new IndexExpression(name.name.key, restriction.startInclusive ? IndexOperator.GTE : IndexOperator.GT, value));
                }
                if (restriction.end != null)
                {
                    ByteBuffer value = restriction.end.getByteBuffer(name.type, variables);
                    expressions.add(new IndexExpression(name.name.key, restriction.endInclusive ? IndexOperator.GTE : IndexOperator.GT, value));
                }
            }
        }
        return expressions;
    }

    private List<Pair<CFDefinition.Name, ColumnIdentifier>> getExpandedSelection()
    {
        if (selectedNames.isEmpty())
        {
            List<Pair<CFDefinition.Name, ColumnIdentifier>> selection = new ArrayList<Pair<CFDefinition.Name, ColumnIdentifier>>();
            for (CFDefinition.Name name : cfDef)
                selection.add(Pair.create(name, name.name));
            return selection;
        }
        else
        {
            return selectedNames;
        }
    }

    private ByteBuffer value(IColumn c)
    {
        return (c instanceof CounterColumn)
             ? ByteBufferUtil.bytes(CounterContext.instance().total(c.value()))
             : c.value();
    }

    private void addToSchema(CqlMetadata schema, Pair<CFDefinition.Name, ColumnIdentifier> p)
    {
        ByteBuffer nameAsRequested = p.right.key;
        schema.name_types.put(nameAsRequested, TypeParser.getShortName(cfDef.cfm.comparator));
        schema.value_types.put(nameAsRequested, TypeParser.getShortName(p.left.type));
    }

    public void processResult(List<Row> rows, CqlResult result)
    {
        // count resultset is a single column named "count"
        if (parameters.isCount)
        {
            result.schema = new CqlMetadata(Collections.<ByteBuffer, String>emptyMap(),
                                            Collections.<ByteBuffer, String>emptyMap(),
                                            "AsciiType",
                                            "LongType");
            List<Column> columns = Collections.singletonList(new Column(countColumn).setValue(ByteBufferUtil.bytes((long) rows.size())));
            result.rows = Collections.singletonList(new CqlRow(countColumn, columns));
            return;
        }

        // otherwise create resultset from query results
        result.schema = new CqlMetadata(new HashMap<ByteBuffer, String>(),
                                        new HashMap<ByteBuffer, String>(),
                                        TypeParser.getShortName(cfDef.cfm.comparator),
                                        TypeParser.getShortName(cfDef.cfm.getDefaultValidator()));
        result.rows = process(rows, result.schema);
    }

    private List<CqlRow> process(List<Row> rows, CqlMetadata schema)
    {
        List<CqlRow> cqlRows = new ArrayList<CqlRow>();
        List<Pair<CFDefinition.Name, ColumnIdentifier>> selection = getExpandedSelection();
        List<Column> thriftColumns = null;

        for (org.apache.cassandra.db.Row row : rows)
        {
            switch (cfDef.kind)
            {
                case STATIC:
                    // One cqlRow for all columns
                    thriftColumns = new ArrayList<Column>();
                    // Respect selection order
                    for (Pair<CFDefinition.Name, ColumnIdentifier> p : selection)
                    {
                        CFDefinition.Name name = p.left;
                        ByteBuffer nameAsRequested = p.right.key;

                        if (name.kind == CFDefinition.Name.Kind.KEY_ALIAS)
                        {
                            addToSchema(schema, p);
                            thriftColumns.add(new Column(nameAsRequested).setValue(row.key.key).setTimestamp(-1L));
                            continue;
                        }

                        if (row.cf == null)
                            continue;

                        addToSchema(schema, p);
                        IColumn c = row.cf.getColumn(name.name.key);
                        Column col = new Column(name.name.key);
                        if (c != null && !c.isMarkedForDelete())
                            col.setValue(value(c)).setTimestamp(c.timestamp());
                        thriftColumns.add(col);
                    }

                    cqlRows.add(new CqlRow(row.key.key, thriftColumns));
                    break;
                case SPARSE:
                    // Group column in cqlRow when composite prefix is equal
                    if (row.cf == null)
                        continue;

                    CompositeType composite = (CompositeType)cfDef.cfm.comparator;
                    int last = composite.types.size() - 1;

                    ByteBuffer[] previous = null;
                    Map<ByteBuffer, IColumn> group = new HashMap<ByteBuffer, IColumn>();
                    for (IColumn c : row.cf)
                    {
                        if (c.isMarkedForDelete())
                            continue;

                        ByteBuffer[] current = composite.split(c.name());
                        // If current differs from previous, we've just finished a group
                        if (previous != null && !isSameRow(previous, current))
                        {
                            cqlRows.add(handleGroup(selection, row.key.key, previous, group, schema));
                            group = new HashMap<ByteBuffer, IColumn>();
                        }

                        // Accumulate the current column
                        group.put(current[last], c);
                        previous = current;
                    }
                    // Handle the last group
                    if (previous != null)
                        cqlRows.add(handleGroup(selection, row.key.key, previous, group, schema));
                    break;
                case DYNAMIC:
                case DENSE:
                    // One cqlRow per column
                    if (row.cf == null)
                        continue;

                    for (IColumn c : row.cf.getSortedColumns())
                    {
                        if (c.isMarkedForDelete())
                            continue;

                        thriftColumns = new ArrayList<Column>();

                        ByteBuffer[] components = cfDef.isComposite()
                                                ? ((CompositeType)cfDef.cfm.comparator).split(c.name())
                                                : null;

                        // Respect selection order
                        for (Pair<CFDefinition.Name, ColumnIdentifier> p : selection)
                        {
                            CFDefinition.Name name = p.left;
                            ByteBuffer nameAsRequested = p.right.key;

                            addToSchema(schema, p);
                            Column col = new Column(nameAsRequested);
                            switch (name.kind)
                            {
                                case KEY_ALIAS:
                                    col.setValue(row.key.key).setTimestamp(-1L);
                                    break;
                                case COLUMN_ALIAS:
                                    col.setTimestamp(c.timestamp());
                                    if (cfDef.isComposite())
                                    {
                                        if (name.compositePosition < components.length)
                                            col.setValue(components[name.compositePosition]);
                                        else
                                            col.setValue(ByteBufferUtil.EMPTY_BYTE_BUFFER);
                                    }
                                    else
                                    {
                                        col.setValue(c.name());
                                    }
                                    break;
                                case VALUE_ALIAS:
                                    col.setValue(value(c)).setTimestamp(c.timestamp());
                                    break;
                                case COLUMN_METADATA:
                                    // This should not happen for DYNAMIC or DENSE
                                    throw new AssertionError();
                            }
                            thriftColumns.add(col);
                        }
                        cqlRows.add(new CqlRow(row.key.key, thriftColumns));
                    }
                    break;
            }
        }
        // We don't allow reversed on range scan, but we do on multiget (IN (...)), so let's reverse the rows there too.
        if (parameters.isColumnsReversed)
            Collections.reverse(cqlRows);

        cqlRows = cqlRows.size() > parameters.limit ? cqlRows.subList(0, parameters.limit) : cqlRows;

        // Trim result if needed to respect the limit
        return cqlRows;
    }

    /**
     * For sparse composite, returns wheter two columns belong to the same
     * cqlRow base on the full list of component in the name.
     * Two columns do belong together if they differ only by the last
     * component.
     */
    private static boolean isSameRow(ByteBuffer[] c1, ByteBuffer[] c2)
    {
        // Cql don't allow to insert columns who doesn't have all component of
        // the composite set for sparse composite. Someone coming from thrift
        // could hit that though. But since we have no way to handle this
        // correctly, better fail here and tell whomever may hit that (if
        // someone ever do) to change the definition to a dense composite
        assert c1.length == c2.length : "Sparse composite should not have partial column names";
        for (int i = 0; i < c1.length - 1; i++)
        {
            if (!c1[i].equals(c2[i]))
                return false;
        }
        return true;
    }

    private CqlRow handleGroup(List<Pair<CFDefinition.Name, ColumnIdentifier>> selection, ByteBuffer key, ByteBuffer[] components, Map<ByteBuffer, IColumn> columns, CqlMetadata schema)
    {
        List<Column> thriftColumns = new ArrayList<Column>();

        // Respect requested order
        for (Pair<CFDefinition.Name, ColumnIdentifier> p : selection)
        {
            CFDefinition.Name name = p.left;
            ByteBuffer nameAsRequested = p.right.key;

            addToSchema(schema, p);
            Column col = new Column(nameAsRequested);
            switch (name.kind)
            {
                case KEY_ALIAS:
                    col.setValue(key).setTimestamp(-1L);
                    break;
                case COLUMN_ALIAS:
                    col.setValue(components[name.compositePosition]);
                    col.setTimestamp(-1L);
                    break;
                case VALUE_ALIAS:
                    // This should not happen for SPARSE
                    throw new AssertionError();
                case COLUMN_METADATA:
                    IColumn c = columns.get(name.name.key);
                    if (c != null && !c.isMarkedForDelete())
                        col.setValue(value(c)).setTimestamp(c.timestamp());
                    break;
            }
            thriftColumns.add(col);
        }
        return new CqlRow(key, thriftColumns);
    }

    public static class RawStatement extends CFStatement implements Preprocessable
    {
        private final Parameters parameters;
        private final List<ColumnIdentifier> selectClause;
        private final List<Relation> whereClause;

        public RawStatement(CFName cfName, Parameters parameters, List<ColumnIdentifier> selectClause, List<Relation> whereClause)
        {
            super(cfName);
            this.parameters = parameters;
            this.selectClause = selectClause;
            this.whereClause = whereClause == null ? Collections.<Relation>emptyList() : whereClause;
        }

        public SelectStatement preprocess() throws InvalidRequestException
        {
            CFMetaData cfm = ThriftValidation.validateColumnFamily(keyspace(), columnFamily());
            ThriftValidation.validateConsistencyLevel(keyspace(), parameters.consistencyLevel, RequestType.READ);

            if (parameters.limit <= 0)
                throw new InvalidRequestException("LIMIT must be strictly positive");

            CFDefinition cfDef = cfm.getCfDef();
            SelectStatement stmt = new SelectStatement(cfDef, parameters);

            // Select clause
            if (parameters.isCount)
            {
                if (selectClause.size() != 1 || (!selectClause.get(0).equals("*") && !selectClause.get(0).equals("1")))
                    throw new InvalidRequestException("Only COUNT(*) and COUNT(1) operations are currently supported.");
            }
            else
            {
                for (ColumnIdentifier t : selectClause)
                {
                    CFDefinition.Name name = cfDef.get(t);
                    if (name == null)
                        throw new InvalidRequestException(String.format("Undefined name %s in selection clause", t));
                    // Keeping the case (as in 'case sensitive') of the input name for the resultSet
                    stmt.selectedNames.add(Pair.create(name, t));
                }
            }

            /*
             * WHERE clause. For a given entity, rules are:
             *   - EQ relation conflicts with anything else (including a 2nd EQ)
             *   - Can't have more than one LT(E) relation (resp. GT(E) relation)
             *   - IN relation are restricted to row keys (for now) and conflics with anything else
             *     (we could allow two IN for the same entity but that doesn't seem very useful)
             *   - The value_alias cannot be restricted in any way (we don't support wide rows with indexed value in CQL so far)
             */
            for (Relation rel : whereClause)
            {
                CFDefinition.Name name = cfDef.get(rel.getEntity());
                if (name == null)
                    throw new InvalidRequestException(String.format("Undefined name %s in where clause ('%s')", rel.getEntity(), rel));

                if (name.kind == CFDefinition.Name.Kind.VALUE_ALIAS)
                    throw new InvalidRequestException(String.format("Restricting the value of a compact CF (%s) is not supported", name.name));

                Restriction restriction = stmt.restrictions.get(name.name);
                switch (rel.operator())
                {
                    case EQ:
                        if (restriction != null)
                            throw new InvalidRequestException(String.format("%s cannot be restricted by more than one relation if it includes an Equal", name));
                        stmt.restrictions.put(name.name, new Restriction(Collections.singletonList(rel.getValue())));
                        break;
                    case GT:
                    case GTE:
                        if (restriction == null)
                        {
                            restriction = new Restriction();
                            stmt.restrictions.put(name.name, restriction);
                        }
                        if (restriction.start != null)
                            throw new InvalidRequestException(String.format("%s cannot be restricted by more than one Greater-Than relation", name));
                        restriction.start = rel.getValue();
                        if (rel.operator() == Relation.Type.GTE)
                            restriction.startInclusive = true;
                        break;
                    case LT:
                    case LTE:
                        if (restriction == null)
                        {
                            restriction = new Restriction();
                            stmt.restrictions.put(name.name, restriction);
                        }
                        if (restriction.end != null)
                            throw new InvalidRequestException(String.format("%s cannot be restricted by more than one Lesser-Than relation", name));
                        restriction.end = rel.getValue();
                        if (rel.operator() == Relation.Type.LTE)
                            restriction.endInclusive = true;
                        break;
                    case IN:
                        if (restriction != null)
                            throw new InvalidRequestException(String.format("%s cannot be restricted by more than one reation if it includes a IN", name));
                        if (name.kind != CFDefinition.Name.Kind.KEY_ALIAS)
                            throw new InvalidRequestException("IN relation can only be applied to the first component of the PRIMARY KEY");
                        stmt.restrictions.put(name.name, new Restriction(rel.getInValues()));
                        break;
                }
            }

            /*
             * At this point, the select statement if fully constructed, but we still have a few things to validate
             */

            // If a component of the PRIMARY KEY is restricted by a non-EQ relation, all preceding
            // components must have a EQ, and all following must have no restriction
            boolean shouldBeDone = false;
            CFDefinition.Name previous = null;
            for (CFDefinition.Name cname : cfDef.columns.values())
            {
                Restriction restriction = stmt.restrictions.get(cname.name);
                if (restriction == null)
                    shouldBeDone = true;
                else if (shouldBeDone)
                    throw new InvalidRequestException(String.format("PRIMARY KEY part %s cannot be restricted (preceding part %s is either not restricted or by a non-EQ relation)", cname, previous));
                else if (!restriction.isEquality())
                    shouldBeDone = true;

                previous = cname;
            }

            // Deal with indexed columns
            if (!cfDef.metadata.values().isEmpty())
            {
                boolean hasEq = false;
                Set<ByteBuffer> indexed = Table.open(keyspace()).getColumnFamilyStore(columnFamily()).indexManager.getIndexedColumns();

                for (CFDefinition.Name name : cfDef.metadata.values())
                {
                    Restriction restriction = stmt.restrictions.get(name.name);
                    if (restriction == null)
                        continue;

                    stmt.hasIndexedExpression = true;
                    if (restriction.isEquality() && indexed.contains(name.name.key))
                    {
                        hasEq = true;
                        break;
                    }
                }

                if (stmt.hasIndexedExpression && !hasEq)
                    throw new InvalidRequestException("No indexed columns present in by-columns clause with Equal operator");

                // If we have indexed columns and the key = X clause, we transform it into a key >= X AND key <= X clause.
                // If it's a IN relation however, we reject it.
                Restriction r = stmt.restrictions.get(cfDef.key.name);
                if (r != null && r.isEquality())
                {
                    if (r.eqValues.size() > 1)
                        throw new InvalidRequestException("Select on indexed columns and with IN clause for the PRIMARY KEY are not supported");

                    r.start = r.eqValues.get(0);
                    r.startInclusive = true;
                    r.end = r.eqValues.get(0);
                    r.endInclusive = true;
                    r.eqValues = null;
                }
            }

            // Only allow reversed if the row key restriction is an equality,
            // since we don't know how to reverse otherwise
            if (stmt.parameters.isColumnsReversed)
            {
                Restriction r = stmt.restrictions.get(cfDef.key.name);
                if (r == null || !r.isEquality())
                    throw new InvalidRequestException("Descending order is only supported is the first part of the PRIMARY KEY is restricted by an Equal or a IN");
            }
            return stmt;
        }

        @Override
        public String toString()
        {
            return String.format("SelectRawStatement[name=%s, selectClause=%s, whereClause=%s, isCount=%s, cLevel=%s, limit=%s]",
                    cfName,
                    selectClause,
                    whereClause,
                    parameters.isCount,
                    parameters.consistencyLevel,
                    parameters.limit);
        }
    }

    // A rather raw class that simplify validation and query for select
    // Don't made public as this can be easily badly used
    private static class Restriction
    {
        // for equality
        List<Term> eqValues; // if null, it's a restriction by bounds

        Restriction(List<Term> values)
        {
            this.eqValues = values;
        }

        // for bounds
        Term start;
        boolean startInclusive;
        Term end;
        boolean endInclusive;

        Restriction()
        {
            this(null);
        }

        boolean isEquality()
        {
            return eqValues != null;
        }
    }

    public static class Parameters
    {
        private final int limit;
        private final ConsistencyLevel consistencyLevel;
        private final boolean isColumnsReversed;
        private final boolean isCount;

        public Parameters(ConsistencyLevel consistency, int limit, boolean reversed, boolean isCount)
        {
            this.consistencyLevel = consistency;
            this.limit = limit;
            this.isColumnsReversed = reversed;
            this.isCount = isCount;
        }
    }
}
