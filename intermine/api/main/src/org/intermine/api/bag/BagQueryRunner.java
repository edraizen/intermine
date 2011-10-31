package org.intermine.api.bag;

/*
 * Copyright (C) 2002-2011 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.intermine.InterMineException;
import org.intermine.metadata.FieldDescriptor;
import org.intermine.metadata.Model;
import org.intermine.model.InterMineObject;
import org.intermine.objectstore.ObjectStore;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.objectstore.query.Query;
import org.intermine.objectstore.query.Results;
import org.intermine.objectstore.query.ResultsBatches;
import org.intermine.objectstore.query.ResultsRow;
import org.intermine.api.template.ApiTemplate;
import org.intermine.api.template.TemplateManager;
import org.intermine.template.TemplateQuery;
import org.intermine.util.CollectionUtil;
import org.intermine.util.TypeUtil;

/**
 * For a given list of input strings search for objects using default and configured queries for a
 * particular type.
 *
 * @author Richard Smith
 */
public class BagQueryRunner
{
    private static final Logger LOG = Logger.getLogger(BagQueryRunner.class);
    private ObjectStore os;
    private Model model;
    private Map<String, List<FieldDescriptor>> classKeys;
    private BagQueryConfig bagQueryConfig;
    private TemplateManager templateManager;

    /**
     * Construct with configured bag queries and a map of type -&gt; key fields.
     *
     * @param os
     *            the ObjectStore to run queries on
     * @param classKeys
     *            the class keys Map
     * @param bagQueryConfig
     *            the configuration for running queries
     * @param templateManager the InterMine template manager to access conversion templates
     *            a list of template queries to be used when type converting results
     */
    public BagQueryRunner(ObjectStore os, Map<String, List<FieldDescriptor>> classKeys,
            BagQueryConfig bagQueryConfig, TemplateManager templateManager) {
        this.model = os.getModel();
        this.os = os;
        this.classKeys = classKeys;
        this.bagQueryConfig = bagQueryConfig;
        this.templateManager = templateManager;
    }

    /**
     * Given an input list of string identifiers search for corresponding objects. First run a
     * default query then any queries configured for the specified type.
     *
     * @param type an unqualified class name to search for objects
     * @param input a list of strings to query
     * @param extraFieldValue the value used when adding an extra constraint to the bag query,
     * configured in BagQueryConfig (e.g. if connectField is "organism", the extraClassName is
     * "Organism" and the constrainField is "name", the extraFieldValue might be "Drosophila
     * melanogaster")
     * @param doWildcards true if the strings should be evaluated as wildcards
     * @return the matches, issues and unresolved input
     * @throws ClassNotFoundException if the type isn't in the model
     * @throws InterMineException if there is any other exception
     */
    public BagQueryResult searchForBag(String type, List<String> input, String extraFieldValue,
            boolean doWildcards) throws ClassNotFoundException, InterMineException {

        Map<String, String> lowerCaseInput = new HashMap<String, String>();
        List<String> cleanInput = new ArrayList<String>();
        List<String> wildcardInput = new ArrayList<String>();
        Map<String, Pattern> patterns = new HashMap<String, Pattern>();
        for (String inputString : input) {
            if (!(inputString == null) && !("".equals(inputString))) {
                if (inputString.indexOf('*') == -1 || (!doWildcards)) {
                    if (!lowerCaseInput.containsKey(inputString.toLowerCase())) {
                        cleanInput.add(inputString);
                        lowerCaseInput.put(inputString.toLowerCase(), inputString);
                    }
                } else {
                    wildcardInput.add(inputString);
                    patterns.put(inputString, Pattern.compile(inputString.toLowerCase()
                            .replaceAll("\\*", "\\.\\*")));
                }
            }
        }

        // TODO tidy up using type String and Class

        // TODO BagQueryResult.getUnresolved() needs to return a map from input
        // to null (if not found) or a set of objects.
        // or just leave as a list of identifiers and objects of the qrong type
        // CollectionUtil.groupByClass will sort out the strings and types
        Class<?> typeCls = Class.forName(model.getPackageName() + "." + type);
        List<BagQuery> queries =
            getBagQueriesForType(bagQueryConfig, typeCls.getName());
        Set<String> unresolved = new LinkedHashSet<String>(cleanInput);
        Set<String> wildcardUnresolved = new LinkedHashSet<String>(wildcardInput);
        BagQueryResult bqr = new BagQueryResult();
        for (BagQuery bq : queries) {
            // run the next query on identifiers not yet resolved
            if (!unresolved.isEmpty()) {
                Map<String, Set<Integer>> resMap = new HashMap<String, Set<Integer>>();
                Query q = null;
                try {
                    q = bq.getQuery(unresolved, extraFieldValue);
                    Results res = os.execute(q, 10000, true, true, false);
                    for (Object rowObj : res) {
                        ResultsRow<?> row = (ResultsRow<?>) rowObj;
                        Integer id = (Integer) row.get(0);
                        for (int i = 1; i < row.size(); i++) {
                            Object fieldObject = row.get(i);
                            if (fieldObject != null) {
                                if (!(fieldObject instanceof String)) {
                                    fieldObject = fieldObject.toString();
                                }
                                String field = (String) fieldObject;
                                String lowerField = field.toLowerCase();
                                if (lowerCaseInput.containsKey(lowerField)) {
                                    // because we are converting to lower case we need to match
                                    // to original input so that 'h' matches 'H' and 'h' becomes
                                    // a duplicate.
                                    String originalInput = lowerCaseInput.get(lowerField);
                                    Set<Integer> ids = resMap.get(originalInput);
                                    if (ids == null) {
                                        ids = new LinkedHashSet<Integer>();
                                        resMap.put(originalInput, ids);
                                    }
                                    // obj is an Integer
                                    ids.add(id);
                                    if (bagQueryConfig.getMatchOnFirst().booleanValue()) {
                                        // remove any identifiers that are now resolved
                                        unresolved.remove(lowerCaseInput.get(lowerField));
                                    }
                                }
                            }
                        }
                    }
                } catch (IllegalArgumentException e) {
                    // Query couldn't handle extra value
                }
                addResults(resMap, unresolved, bqr, bq, typeCls, false);
            }
            if (!wildcardInput.isEmpty()) {
                try {
                    Map<String, Set<Integer>> resMap = new HashMap<String, Set<Integer>>();
                    Query q = bq.getQueryForWildcards(wildcardInput, extraFieldValue);
                    Results res = os.execute(q, ResultsBatches.DEFAULT_BATCH_SIZE, true, true,
                            false);
                    for (Object rowObj : res) {
                        ResultsRow<?> row = (ResultsRow<?>) rowObj;
                        Integer id = (Integer) row.get(0);
                        for (int i = 1; i < row.size(); i++) {
                            String field = "" + row.get(i);
                            String lowerField = field.toLowerCase();
                            for (String wildcard : wildcardInput) {
                                Pattern pattern = patterns.get(wildcard);
                                if (pattern.matcher(lowerField).matches()) {
                                    Set<Integer> ids = resMap.get(wildcard);
                                    if (ids == null) {
                                        ids = new LinkedHashSet<Integer>();
                                        resMap.put(wildcard, ids);
                                    }
                                    ids.add(id);
                                    // we have matched at least once with wildcard
                                    wildcardUnresolved.remove(wildcard);
                                }
                            }
                        }
                    }
                    for (Map.Entry<String, Set<Integer>> entry : resMap.entrySet()) {
                        // This is a dummy issue just to give a message when running queries
                        bqr.addIssue(BagQueryResult.WILDCARD, bq.getMessage(),
                                entry.getKey(), new ArrayList<Object>(entry.getValue()));
                        addResults(resMap, wildcardUnresolved, bqr, bq, typeCls, true);
                    }
                } catch (IllegalArgumentException e) {
                    LOG.error("Error running bag query lookup: ", e);
                    // Query couldn't handle extra value
                }
            }
        }

        unresolved.addAll(wildcardUnresolved);
        Map<String, ?> unresolvedMap = new HashMap<String, Object>();
        for (String unresolvedStr : unresolved) {
            unresolvedMap.put(unresolvedStr, null);
        }
        bqr.getUnresolved().putAll(unresolvedMap);

        return bqr;
    }

    /**
     * Add results from resMap to a a BagQueryResults object.
     *
     * @throws InterMineException
     */
    private void addResults(Map<String, Set<Integer>> resMap, Set<String> unresolved,
            BagQueryResult bqr, BagQuery bq, Class<?> type,
            boolean areWildcards) throws InterMineException {
        Map<String, Set<Object>> objsOfWrongType = new HashMap<String, Set<Object>>();

        // Gather together all the id lookups and perform them in one.
        Map<Integer, InterMineObject> fetchedObjects = new HashMap<Integer, InterMineObject>();
        Set<Integer> idsToFetch = new HashSet<Integer>();
        try {
            for (Map.Entry<String, Set<Integer>> resEntry : resMap.entrySet()) {
                if (bq.matchesAreIssues() || (resEntry.getValue().size() > 1)) {
                    idsToFetch.addAll(resEntry.getValue());
                }
            }
            List<InterMineObject> idsFetched = os.getObjectsByIds(idsToFetch);
            for (InterMineObject fetchedObject : idsFetched) {
                fetchedObjects.put(fetchedObject.getId(), fetchedObject);
            }
        } catch (ObjectStoreException e) {
            throw new InterMineException("can't fetch: " + idsToFetch, e);
        }

        for (Map.Entry<String, Set<Integer>> entry : resMap.entrySet()) {
            String input = entry.getKey();
            Set<Integer> ids = entry.getValue();
            boolean resolved = true;

            if (!bq.matchesAreIssues()) {

                // if matches are not issues then each entry will be a match or a duplicate
                if (ids.size() == 1) {
                    bqr.addMatch(input, ids.iterator().next());
                } else if (!areWildcards) {
                    List<Object> objs = new ArrayList<Object>();
                    for (Integer objectId : ids) {
                        Object obj = fetchedObjects.get(objectId);
                        if (obj == null) {
                            throw new NullPointerException("Could not find object with ID "
                                    + objectId);
                        }
                        objs.add(obj);
                    }
                    bqr.addIssue(BagQueryResult.DUPLICATE, bq.getMessage(), entry.getKey(), objs);
                }
            } else {
                List<Object> objs = new ArrayList<Object>();
                Set<Object> localObjsOfWrongType = new HashSet<Object>();

                // we have a list of objects that result from some query, divide into any that
                // match the type of the bag to be created and candidates for conversion
                for (Integer objectId : ids) {
                    InterMineObject obj = fetchedObjects.get(objectId);
                    if (obj == null) {
                        throw new NullPointerException("Could not find object with ID " + objectId);
                    }

                    if (type.isInstance(obj)) {
                        objs.add(obj);
                    } else {
                        localObjsOfWrongType.add(obj);
                    }
                }

                if (!objs.isEmpty()) {
                    // we have a list of objects, if any match the type then add to bqr as an issue
                    // discard objects that matched a different type
                    if (objs.size() == 1) {
                        bqr.addIssue(BagQueryResult.OTHER, bq.getMessage(), input, objs);
                    } else if (!areWildcards) {
                        bqr.addIssue(BagQueryResult.DUPLICATE, bq.getMessage(), input, objs);
                    }
                } else {
                    // all wrong, allow conversion attempts
                    resolved = false;
                    objsOfWrongType.put(input, localObjsOfWrongType);
                }
            }
            if (resolved && bagQueryConfig.getMatchOnFirst().booleanValue()) {
                unresolved.remove(input);
            }
        }

        // now objsOfWrongType contains all wrong types found for this query, try converting
        convertObjects(bqr, bq, type, objsOfWrongType);

        bqr.getUnresolved().putAll(objsOfWrongType);
    }

    /**
     * Find any objects in the objsOfWrongType Map that can be converted to the destination type,
     * add them to bqr as TYPE_CONVERTED issues and remove them from objsOfWrongType.
     */
    private void convertObjects(BagQueryResult bqr, BagQuery bq, Class<?> type,
            Map<String, Set<Object>> objsOfWrongType)
        throws InterMineException {
        if (!objsOfWrongType.isEmpty()) {
            // group objects by class
            Map<InterMineObject, Set<String>> objectToInput =
                new HashMap<InterMineObject, Set<String>>();
            for (Map.Entry<String, Set<Object>> entry : objsOfWrongType.entrySet()) {
                String input = entry.getKey();
                for (Object o : entry.getValue()) {
                    InterMineObject imo = (InterMineObject) o;
                    Set<String> inputSet = objectToInput.get(imo);
                    if (inputSet == null) {
                        inputSet = new HashSet<String>();
                        objectToInput.put(imo, inputSet);
                    }
                    inputSet.add(input);
                }
            }

            Map<Class<?>, List<InterMineObject>> objTypes =
                CollectionUtil.groupByClass(objectToInput.keySet(), true);

            for (Class<?> fromClass : objTypes.keySet()) {
                List<InterMineObject> candidateObjs = objTypes.get(fromClass);

                // we may have already converted some of these types, remove any that have been.
                List<Integer> idsToConvert = new ArrayList<Integer>();
                for (InterMineObject candidate : candidateObjs) {
                    if (objectToInput.containsKey(candidate)) {
                        idsToConvert.add(candidate.getId());
                    }
                }

                // there was nothing to convert for this class
                if (idsToConvert.isEmpty()) {
                    continue;
                }

                // try to convert objects to target type
                Map<InterMineObject, List<InterMineObject>> convertedObjsMap =
                    TypeConverter.getConvertedObjectMap(getConversionTemplates(),
                            fromClass, type, idsToConvert, os);
                if (convertedObjsMap == null) {
                    // no conversion found
                    continue;
                }

                // loop over the old objects
                for (InterMineObject origObj : convertedObjsMap.keySet()) {
                    boolean toRemove = false;
                    // then for each new object ...
                    for (InterMineObject convertedObj : convertedObjsMap.get(origObj)) {
                        ConvertedObjectPair convertedPair = new ConvertedObjectPair(origObj,
                                                                                    convertedObj);
                        List<Object> objPairList = new ArrayList<Object>();
                        objPairList.add(convertedPair);
                        // remove this object so we don't try to convert it again
                        toRemove = true;
                        // make an issue for each input identifier that matched the objects in
                        // this old/new pair
                        for (String origInputString : objectToInput.get(origObj)) {
                            bqr.addIssue(BagQueryResult.TYPE_CONVERTED,
                                         bq.getMessage() + " found by converting from x",
                                         origInputString, objPairList);
                            objsOfWrongType.remove(origInputString);
                        }
                    }
                    if (toRemove) {
                        objectToInput.remove(origObj);
                    }
                }
            }
        }
    }

    // temporary method - will be replaced by BagQueryHelper method
    private List<BagQuery> getBagQueriesForType(BagQueryConfig config, String type) {
        List<BagQuery> queries = new ArrayList<BagQuery>();

        // some queries should run before the default
        queries.addAll(config.getPreDefaultBagQueries(TypeUtil.unqualifiedName(type)));

        // create the default query and put it first in the list
        queries.add(new BagQuery(config, model, classKeys, type));

        // add any queries that are configured for this type
        queries.addAll(config.getBagQueries(TypeUtil.unqualifiedName(type)));

        return queries;
    }

    /**
     * Fetch conversion template queries from the template manager.  This is in a separate method
     * so we can override and easily use a custom list of templates for testing.
     * @return a list of conversion templates
     */
    protected List<ApiTemplate> getConversionTemplates() {
        return templateManager.getConversionTemplates();
    }
}
