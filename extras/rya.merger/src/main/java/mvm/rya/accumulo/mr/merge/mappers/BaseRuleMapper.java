package mvm.rya.accumulo.mr.merge.mappers;

/*
 * #%L
 * mvm.rya.accumulo.mr.merge
 * %%
 * Copyright (C) 2014 Rya
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.io.IOException;
import java.util.List;

import org.apache.accumulo.core.client.mapreduce.RangeInputSplit;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.hadoop.conf.Configuration;
import org.apache.log4j.Logger;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.query.algebra.Or;
import org.openrdf.query.algebra.ValueExpr;

import mvm.rya.accumulo.AccumuloRdfConfiguration;
import mvm.rya.accumulo.mr.merge.MergeTool;
import mvm.rya.accumulo.mr.merge.util.AccumuloQueryRuleset;
import mvm.rya.accumulo.mr.merge.util.CopyRule;
import mvm.rya.accumulo.mr.merge.util.QueryRuleset.QueryRulesetException;
import mvm.rya.accumulo.mr.utils.MRUtils;
import mvm.rya.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import mvm.rya.api.RdfCloudTripleStoreUtils;
import mvm.rya.api.domain.RyaStatement;
import mvm.rya.api.resolver.RyaToRdfConversions;
import mvm.rya.api.resolver.triple.TripleRow;
import mvm.rya.api.resolver.triple.TripleRowResolver;
import mvm.rya.api.resolver.triple.TripleRowResolverException;
import mvm.rya.api.resolver.triple.impl.WholeRowTripleResolver;
import mvm.rya.rdftriplestore.evaluation.ParallelEvaluationStrategyImpl;

/**
 * Take in rows from a table and range defined by query-based rules, convert the rows to
 * statements based on the table name, and process those statements that match the rule(s).
 */
public abstract class BaseRuleMapper<KEYOUT, VALUEOUT> extends BaseCopyToolMapper<Key, Value, KEYOUT, VALUEOUT> {
    /**
     * Hadoop counters for tracking the number of statements and/or raw rows that have been processed.
     */
    public static enum Counters { STATEMENTS_COPIED, DIRECT_ROWS_COPIED };

    private static final Logger log = Logger.getLogger(BaseRuleMapper.class);

    private TripleRowResolver resolver = new WholeRowTripleResolver();
    private TABLE_LAYOUT parentLayout = null;
    private ValueExpr condition;
    private ParallelEvaluationStrategyImpl strategy;
    private RangeInputSplit split;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        Configuration conf = context.getConfiguration();
        split = (RangeInputSplit) context.getInputSplit();
        Range range = split.getRange();

        // Determine the table and table layout we're scanning
        parentTableName = split.getTableName();
        parentTablePrefix = conf.get(MRUtils.TABLE_PREFIX_PROPERTY);
        for (TABLE_LAYOUT layout : TABLE_LAYOUT.values()) {
            String tableName = RdfCloudTripleStoreUtils.layoutPrefixToTable(layout, parentTablePrefix);
            if (tableName.equals(parentTableName)) {
                parentLayout = layout;
            }
        }
        conf.set(MergeTool.TABLE_NAME_PROP, parentTableName);

        // Set up connections and parent/child table information, if necessary
        super.setup(context);

        // If we're working at the statement level, get the relevant rules and conditions:
        if (parentLayout != null) {
            AccumuloQueryRuleset ruleset;
            try {
                ruleset = new AccumuloQueryRuleset(new AccumuloRdfConfiguration(conf));
            } catch (QueryRulesetException e) {
                throw new IOException("Error parsing the input query", e);
            }

            List<CopyRule> rules = ruleset.getRules(parentLayout, range);

            for (CopyRule rule : rules) {
                log.info("Mapper applies to rule:");
                for (String line : rule.toString().split("\n")) {
                    log.info("\t" + line);
                }
            }

            // Combine the rules' conditions so that if any of the individual conditions matches, the
            // composite condition will match as well. We know all the rules match all the statements
            // this input split will receive, so if any condition is true we'll want to copy the statement.
            for (CopyRule rule : rules) {
                // Attach any relevant filter conditions given by this rule.
                // If there are no conditions, all statements read by this mapper should be accepted
                // (even if there are redundant rules with conditions)
                if (rule.getCondition() == null) {
                    condition = null;
                    break;
                }
                // If there is a set of conditions, matching it means we should accept the statement.
                else if (condition == null) {
                    condition = rule.getCondition();
                }
                // If there are more than one rules that match, satisfying any conditions means we should accept.
                else {
                    condition = new Or(condition, rule.getCondition());
                }
            }

            // Set up the strategy to evaluate those conditions
            strategy = new ParallelEvaluationStrategyImpl(null, null, null, childAccumuloRdfConfiguration);

            // Log info about the split and combined condition
            log.info("Table: " + parentTableName);
            log.info("Range:");
            log.info("\tfrom " + keyToString(range.getStartKey(), Integer.MAX_VALUE));
            log.info("\tto " + keyToString(range.getEndKey(), Integer.MAX_VALUE));
            if (condition == null) {
                log.info("Condition: none");
            }
            else {
                log.info("Condition:");
                for (String line : condition.toString().split("\n")) {
                    log.info("\t" + line);
                }
            }
        }

        else {
            log.info("(Copying all rows from " + parentTableName + " directly.)");
        }
    }

    @Override
    protected void map(Key key, Value value, Context context) throws IOException, InterruptedException {
        TripleRow row = new TripleRow(key.getRowData().toArray(), key.getColumnFamilyData().toArray(),
                key.getColumnQualifierData().toArray(), key.getTimestamp(),
                key.getColumnVisibilityData().toArray(), value == null ? null : value.get());
        try {
            // If there's no layout, copy the row directly
            if (parentLayout == null) {
                copyRow(key, value, context);
                context.getCounter(Counters.DIRECT_ROWS_COPIED).increment(1);
            }
            // If there is a layout, deserialize the statement and insert it if it meets the condition
            else {
                RyaStatement rs = resolver.deserialize(parentLayout, row);
                if (condition == null || CopyRule.accept(RyaToRdfConversions.convertStatement(rs), condition, strategy)) {
                    copyStatement(rs, context);
                    context.getCounter(Counters.STATEMENTS_COPIED).increment(1);
                }
            }
        } catch (TripleRowResolverException e) {
            throw new IOException("Error deserializing triple", e);
        } catch (QueryEvaluationException e) {
            throw new IOException("Error evaluating the filter condition", e);
        }
    }

    /**
     * Copy a Statement, serializing it and/or indexing it as necessary.
     * @param rstmt RyaStatement to copy to the child
     * @param context Context to use for writing
     * @throws InterruptedException If the Hadoop framework reports an interrupt
     * @throws IOException If any error is encountered while serializing or writing the statement
     */
    abstract protected void copyStatement(RyaStatement rstmt, Context context) throws IOException, InterruptedException;

    /**
     * Copy a row directly, as opposed to starting with a higher-level object and serializing it.
     * @param key Row's key
     * @param value Row's value
     * @param context Context to use for writing
     * @throws InterruptedException If the Hadoop framework reports an interrupt
     * @throws IOException If an error is encountered writing the row
     */
    abstract protected void copyRow(Key key, Value value, Context context) throws IOException, InterruptedException;

    /**
     * Get a printable representation of a Key, with parts truncated to a parameterized length.
     * (Key.toString() truncates to a fixed length that is sometimes too short to usefully log ranges).
     * @param key Any Accumulo Key
     * @param max The maximum printed length of each individual portion
     * @return A human-readable representation of the Key
     */
    private static String keyToString(Key key, int max) {
        StringBuilder sb = new StringBuilder();
        byte[] row = key.getRow().copyBytes();
        byte[] colFamily = key.getColumnFamily().copyBytes();
        byte[] colQualifier = key.getColumnQualifier().copyBytes();
        byte[] colVisibility = key.getColumnVisibility().copyBytes();
        Key.appendPrintableString(row, 0, row.length, max, sb);
        sb.append(" ");
        Key.appendPrintableString(colFamily, 0, colFamily.length, max, sb);
        sb.append(":");
        Key.appendPrintableString(colQualifier, 0, colQualifier.length, max, sb);
        sb.append(" [");
        Key.appendPrintableString(colVisibility, 0, colVisibility.length, max, sb);
        sb.append("] ");
        sb.append(Long.toString(key.getTimestamp()));
        return sb.toString();
    }
}
