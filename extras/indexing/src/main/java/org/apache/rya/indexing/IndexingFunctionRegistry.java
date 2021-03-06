package org.apache.rya.indexing;

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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.algebra.ValueConstant;
import org.eclipse.rdf4j.query.algebra.ValueExpr;
import org.eclipse.rdf4j.query.algebra.Var;

import com.google.common.collect.Maps;

public class IndexingFunctionRegistry {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final Map<IRI, FUNCTION_TYPE> SEARCH_FUNCTIONS = Maps.newHashMap();
    
    static {
        
        String TEMPORAL_NS = "tag:rya-rdf.org,2015:temporal#";         

        SEARCH_FUNCTIONS.put(VF.createIRI(TEMPORAL_NS+"after"),FUNCTION_TYPE.TEMPORAL);
        SEARCH_FUNCTIONS.put(VF.createIRI(TEMPORAL_NS+"before"), FUNCTION_TYPE.TEMPORAL);
        SEARCH_FUNCTIONS.put(VF.createIRI(TEMPORAL_NS+"equals"), FUNCTION_TYPE.TEMPORAL);
        SEARCH_FUNCTIONS.put(VF.createIRI(TEMPORAL_NS+"beforeInterval"), FUNCTION_TYPE.TEMPORAL);
        SEARCH_FUNCTIONS.put(VF.createIRI(TEMPORAL_NS+"afterInterval"), FUNCTION_TYPE.TEMPORAL);
        SEARCH_FUNCTIONS.put(VF.createIRI(TEMPORAL_NS+"insideInterval"), FUNCTION_TYPE.TEMPORAL);
        SEARCH_FUNCTIONS.put(VF.createIRI(TEMPORAL_NS+"hasBeginningInterval"), FUNCTION_TYPE.TEMPORAL);
        SEARCH_FUNCTIONS.put(VF.createIRI(TEMPORAL_NS+"hasEndInterval"), FUNCTION_TYPE.TEMPORAL);
        
        
        SEARCH_FUNCTIONS.put(VF.createIRI("http://rdf.useekm.com/fts#text"), FUNCTION_TYPE.FREETEXT);

        SEARCH_FUNCTIONS.put(GeoConstants.GEO_SF_EQUALS, FUNCTION_TYPE.GEO);
        SEARCH_FUNCTIONS.put(GeoConstants.GEO_SF_DISJOINT, FUNCTION_TYPE.GEO);
        SEARCH_FUNCTIONS.put(GeoConstants.GEO_SF_INTERSECTS, FUNCTION_TYPE.GEO);
        SEARCH_FUNCTIONS.put(GeoConstants.GEO_SF_TOUCHES, FUNCTION_TYPE.GEO);
        SEARCH_FUNCTIONS.put(GeoConstants.GEO_SF_WITHIN, FUNCTION_TYPE.GEO);
        SEARCH_FUNCTIONS.put(GeoConstants.GEO_SF_CONTAINS, FUNCTION_TYPE.GEO);
        SEARCH_FUNCTIONS.put(GeoConstants.GEO_SF_OVERLAPS, FUNCTION_TYPE.GEO);
        SEARCH_FUNCTIONS.put(GeoConstants.GEO_SF_CROSSES, FUNCTION_TYPE.GEO);
        SEARCH_FUNCTIONS.put(GeoConstants.GEO_SF_NEAR, FUNCTION_TYPE.GEO);

    }
    
    public enum FUNCTION_TYPE {GEO, TEMPORAL, FREETEXT, NONE}

    public static Set<IRI> getFunctions() {
        return SEARCH_FUNCTIONS.keySet();
    }
    
    
    public static Var getResultVarFromFunctionCall(IRI function, List<ValueExpr> args) {
        
        FUNCTION_TYPE type = SEARCH_FUNCTIONS.getOrDefault(function, FUNCTION_TYPE.NONE);
        
        switch(type) {
        case GEO: 
            return findBinaryResultVar(args);
        case FREETEXT:
            return findLiteralResultVar(args);
        case TEMPORAL:
            return findBinaryResultVar(args);
        default:
            return null;
        }
        
    }
    
    
    public static FUNCTION_TYPE getFunctionType(IRI func) {
        return SEARCH_FUNCTIONS.get(func);
    }
    
    
    
    private static boolean isUnboundVariable(ValueExpr expr) {
        return expr instanceof Var && !((Var)expr).hasValue();
    }
    
    private static boolean isConstant(ValueExpr expr) {
        return expr instanceof ValueConstant || (expr instanceof Var && ((Var)expr).hasValue());
    }
    
    
    private static Var findBinaryResultVar(List<ValueExpr> args) {
     
        if (args.size() >= 2) {
            ValueExpr arg1 = args.get(0);
            ValueExpr arg2 = args.get(1);
            if (isUnboundVariable(arg1) && isConstant(arg2))
                return (Var) arg1;
            else if (isUnboundVariable(arg2) && isConstant(arg1))
                return (Var) arg2;
            else 
                return (Var) arg1;
        }
        return null;
    }
    
    
    private static Var findLiteralResultVar(List<ValueExpr> args) {
        if (args.size() >= 2) {
            ValueExpr arg1 = args.get(0);
            ValueExpr arg2 = args.get(1);
            if (isUnboundVariable(arg1) && isConstant(arg2))
                return (Var)arg1;
        }
        return null;
    }
    
    
    
}
