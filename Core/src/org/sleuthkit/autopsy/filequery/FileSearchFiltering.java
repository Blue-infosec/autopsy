/*
 * Autopsy Forensic Browser
 *
 * Copyright 2019 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.filequery;

import org.sleuthkit.autopsy.centralrepository.datamodel.CorrelationAttributeInstance;
import org.sleuthkit.autopsy.centralrepository.datamodel.CorrelationAttributeNormalizationException;
import org.sleuthkit.autopsy.centralrepository.datamodel.EamDb;
import org.sleuthkit.autopsy.centralrepository.datamodel.EamDbException;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.filequery.FileSearchData.FileSize;
import org.sleuthkit.autopsy.filequery.FileSearchData.FileType;
import org.sleuthkit.autopsy.filequery.FileSearchData.Frequency;

import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.DataSource;
import org.sleuthkit.datamodel.SleuthkitCase;
import org.sleuthkit.datamodel.TskCoreException;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.openide.util.NbBundle;

/**
 * Run various filters to return a subset of files from the current case.
 */
class FileSearchFiltering {
    
    private final static Logger logger = Logger.getLogger(FileSearchFiltering.class.getName());
    
    /**
     * Run the given filters to get a list of matching files.
     * 
     * @param filters  The filters to run
     * @param caseDb   The case database
     * @param crDb     The central repo. Can be null as long as no filters need it.
     * 
     * @return 
     */
    static List<ResultFile> runQueries(List<FileFilter> filters, SleuthkitCase caseDb, EamDb centralRepoDb) throws FileSearchException {
        
        if (caseDb == null) {
            throw new FileSearchException("Case DB parameter is null"); // NON-NLS
        }
        
        // Record the selected filters
        String filterStr = "";
        for (FileFilter filter : filters) {
            filterStr += "  " + filter.getDesc() + "\n";
        }
        logger.log(Level.INFO, "Running filters:\n{0}", filterStr);
        
        // Combine all the SQL queries from the filters into one query
        String combinedQuery = "";
        for (FileFilter filter : filters) {            
            if ( ! filter.getWhereClause().isEmpty()) {
                if ( ! combinedQuery.isEmpty()) {
                    combinedQuery += " AND "; // NON-NLS
                }
                combinedQuery += "(" + filter.getWhereClause() + ")"; // NON-NLS
            }
        }
        
        if (combinedQuery.isEmpty()) {
            // The file search filter is required, so this should never be empty.
            throw new FileSearchException("Selected filters do not include a case database query");
        }
        
        try {
            // Get all matching abstract files
            List<ResultFile> resultList = new ArrayList<>();


            logger.log(Level.INFO, "Running SQL query: {0}", combinedQuery);
            List<AbstractFile> sqlResults = caseDb.findAllFilesWhere(combinedQuery);

            // If there are no results, return now
            if (sqlResults.isEmpty()) {
                return resultList;
            }

            // Wrap each result in a ResultFile
            for (AbstractFile abstractFile : sqlResults) {
                resultList.add(new ResultFile(abstractFile));
            }
            
            // Now run any non-SQL filters. 
            for (FileFilter filter : filters) {
                if (filter.useAlternateFilter()) {
                    resultList = filter.applyAlternateFilter(resultList, caseDb, centralRepoDb);
                }
                
                // There are no matches for the filters run so far, so return
                if (resultList.isEmpty()) {
                    return resultList;
                }
            }
            
            return resultList;
        } catch (TskCoreException ex) {
            throw new FileSearchException("Error querying case database", ex); // NON-NLS
        }
    }
    
    /**
     * Base class for the filters.
     */
    static abstract class FileFilter {      
        /**
         * Returns part of a query on the tsk_files table that can be AND-ed with other pieces
         * @return the SQL query or an empty string if there is no SQL query for this filter.
         */
        abstract String getWhereClause();
        
        /**
         * Indicates whether this filter needs to use the secondary, non-SQL method applyAlternateFilter().
         * @return false by default
         */
        boolean useAlternateFilter() {
            return false;
        }
        
        /**
         * Run a secondary filter that does not operate on tsk_files.
         * 
         * @param currentResults The current list of matching files; empty if no filters have yet been run.
         * @param caseDb         The case database
         * @param centralRepoDb  The central repo database. Can be null if the filter does not require it.
         * 
         * @return The list of files that match this filter (and any that came before it)
         * 
         * @throws FileSearchException 
         */
        List<ResultFile> applyAlternateFilter (List<ResultFile> currentResults, SleuthkitCase caseDb, 
                EamDb centralRepoDb) throws FileSearchException {
            return new ArrayList<>();
        }
        
        /**
         * Get a description of the selected filter.
         * 
         * @return A description of the filter
         */
        abstract String getDesc();
    }
    
    /**
     * A filter for specifying the file size
     */
    static class SizeFilter extends FileFilter {
        private final List<FileSize> fileSizes;
        
        /**
         * Create the SizeFilter
         * 
         * @param fileSizes the file sizes that should match
         */
        SizeFilter(List<FileSize> fileSizes) {
            this.fileSizes = fileSizes;
        }
        
        @Override
        String getWhereClause() {
            String queryStr = ""; // NON-NLS
            for (FileSize size : fileSizes) {
                if (! queryStr.isEmpty()) {
                    queryStr += " OR "; // NON-NLS
                } 
                if (size.getMaxBytes() != FileSize.NO_MAXIMUM) {
                    queryStr += "(size > \'" + size.getMinBytes() + "\' AND size <= \'" + size.getMaxBytes() + "\')"; // NON-NLS
                } else {
                    queryStr += "(size >= \'" + size.getMinBytes() + "\')"; // NON-NLS
                }
            }
            return queryStr;
        }
        
        @NbBundle.Messages({
            "# {0} - filters",
            "FileSearchFiltering.SizeFilter.desc=Files with size in range(s): {0}",
            "FileSearchFiltering.SizeFilter.or= or ",
            "# {0} - Minimum bytes",
            "# {1} - Maximum bytes",
            "FileSearchFiltering.SizeFilter.range=({0} to {1})",
        })
        @Override
        String getDesc() {
            String desc = ""; // NON-NLS
            for (FileSize size : fileSizes) {
                if ( ! desc.isEmpty()) {
                    desc += Bundle.FileSearchFiltering_SizeFilter_or();
                }
                desc += Bundle.FileSearchFiltering_SizeFilter_range(size.getMinBytes(), size.getMaxBytes());
            }
            desc = Bundle.FileSearchFiltering_SizeFilter_desc(desc);
            return desc;
        }
    }
    
    /**
     * A utility class for the ParentFilter to store the search string
     * and whether it is a full path or a substring.
     */
    static class ParentSearchTerm {
        private final String searchStr;
        private final boolean isFullPath;
        
        /**
         * Create the ParentSearchTerm object
         * 
         * @param searchStr  The string to search for in the file path
         * @param isFullPath True if the path should exactly match the given 
         *                   string, false to do a substring search
         */
        ParentSearchTerm(String searchStr, boolean isFullPath) {
            this.searchStr = searchStr;
            this.isFullPath = isFullPath;
        }
        
        /**
         * Get the SQL term to search for
         * 
         * @return The SQL for a where clause to search for a matching path
         */
        String getSQLForTerm() {
            // TODO - these should really be prepared statements
            if (isFullPath) {
                return "parent_path=\'" + searchStr + "\'"; // NON-NLS
            } else {
                return "parent_path LIKE \'%" + searchStr + "%\'"; // NON-NLS
            }
        }
        
        @NbBundle.Messages({
            "# {0} - search term",
            "FileSearchFiltering.ParentSearchTerm.fullString= {0} (exact)",
            "# {0} - search term",
            "FileSearchFiltering.ParentSearchTerm.subString= {0} (substring)",
        })
        @Override
        public String toString() {
            if (isFullPath) {
                return Bundle.FileSearchFiltering_ParentSearchTerm_fullString(searchStr);
            }
            return Bundle.FileSearchFiltering_ParentSearchTerm_subString(searchStr);
        }
    }
    
    /**
     * A filter for specifying parent path (either full path or substring)
     */    
    static class ParentFilter extends FileFilter {
        private final List<ParentSearchTerm> parentSearchTerms;
        
        /**
         * Create the ParentFilter
         * 
         * @param parentSearchTerms Full paths or substrings to filter on
         */
        ParentFilter(List<ParentSearchTerm> parentSearchTerms) {
            this.parentSearchTerms = parentSearchTerms;
        }
        
        @Override
        String getWhereClause() {
            String queryStr = ""; // NON-NLS
            for (ParentSearchTerm searchTerm : parentSearchTerms) {
                if (! queryStr.isEmpty()) {
                    queryStr += " OR "; // NON-NLS
                } 
                queryStr += searchTerm.getSQLForTerm();
            }
            return queryStr;
        }
        
        @NbBundle.Messages({
            "# {0} - filters",
            "FileSearchFiltering.ParentFilter.desc=Files with paths matching: {0}",
            "FileSearchFiltering.ParentFilter.or= or ",
            "FileSearchFiltering.ParentFilter.exact=(exact match)",
            "FileSearchFiltering.ParentFilter.substring=(substring)",
        })
        @Override
        String getDesc() {
            String desc = ""; // NON-NLS
            for (ParentSearchTerm searchTerm : parentSearchTerms) {
                if ( ! desc.isEmpty()) {
                    desc += Bundle.FileSearchFiltering_ParentFilter_or();
                }
                if (searchTerm.isFullPath) {
                    desc += searchTerm.searchStr + Bundle.FileSearchFiltering_ParentFilter_exact();
                } else {
                    desc += searchTerm.searchStr + Bundle.FileSearchFiltering_ParentFilter_substring();
                }
            }
            desc = Bundle.FileSearchFiltering_ParentFilter_desc(desc);
            return desc;
        }
    }
    
    /**
     * A filter for specifying data sources
     */ 
    static class DataSourceFilter extends FileFilter {
        private final List<DataSource> dataSources;
        
        /**
         * Create the DataSourceFilter
         * 
         * @param dataSources the data sources to filter on
         */
        DataSourceFilter(List<DataSource> dataSources) {
            this.dataSources = dataSources;
        }
        
        @Override
        String getWhereClause() {
            String queryStr = ""; // NON-NLS
            for (DataSource ds : dataSources) {
                if (! queryStr.isEmpty()) {
                    queryStr += ","; // NON-NLS
                } 
                queryStr += "\'" + ds.getId() + "\'"; // NON-NLS
            }
            queryStr = "data_source_obj_id IN (" + queryStr + ")"; // NON-NLS
            return queryStr;
        }
        
        @NbBundle.Messages({
            "# {0} - filters",
            "FileSearchFiltering.DataSourceFilter.desc=Files in data source(s): {0}",
            "FileSearchFiltering.DataSourceFilter.or= or ",
            "# {0} - Data source name",
            "# {1} - Data source ID",
            "FileSearchFiltering.DataSourceFilter.datasource={0}({1})",
        })
        @Override
        String getDesc() {
            String desc = ""; // NON-NLS
            for (DataSource ds : dataSources) {
                if ( ! desc.isEmpty()) {
                    desc += Bundle.FileSearchFiltering_DataSourceFilter_or();
                }
                desc += Bundle.FileSearchFiltering_DataSourceFilter_datasource(ds.getName(), ds.getId());
            }
            desc = Bundle.FileSearchFiltering_DataSourceFilter_desc(desc);
            return desc;
        }
    }
    
    /**
     * A filter for specifying keyword list names.
     * A file must contain a keyword from one of the given lists to pass.
     */
    static class KeywordListFilter extends FileFilter {
        private final List<String> listNames;
        
        /**
         * Create the KeywordListFilter
         * @param listNames 
         */
        KeywordListFilter(List<String> listNames) {
            this.listNames = listNames;
        }
        
        @Override
        String getWhereClause() {
            String keywordListPart = ""; // NON-NLS
            for (String listName : listNames) {
                if (! keywordListPart.isEmpty()) {
                    keywordListPart += " OR "; // NON-NLS
                } 
                keywordListPart += "value_text = \'" + listName + "\'"; // TODO - these should really be prepared statements  // NON-NLS
            }
            
            String queryStr = "(obj_id IN (SELECT obj_id from blackboard_artifacts WHERE artifact_id IN " + 
                    "(SELECT artifact_id FROM blackboard_attributes WHERE artifact_type_id = 9 AND attribute_type_ID = 37 " + 
                    "AND (" + keywordListPart + "))))";  // NON-NLS
            
            return queryStr;
        }
        
        @NbBundle.Messages({
            "# {0} - filters",
            "FileSearchFiltering.KeywordListFilter.desc=Files with keywords in list(s): {0}",
            "FileSearchFiltering.KeywordListFilter.comma=, ",
        })
        @Override
        String getDesc() {
            String desc = ""; // NON-NLS
            for (String listName : listNames) {
                if ( ! desc.isEmpty()) {
                    desc += Bundle.FileSearchFiltering_KeywordListFilter_comma();
                }
                desc += listName;
            }
            desc = Bundle.FileSearchFiltering_KeywordListFilter_desc(desc);
            return desc;
        }
    }    
    
    /**
     * A filter for specifying file types.
     */    
    static class FileTypeFilter extends FileFilter {
        private final List<FileType> categories;
        
        /**
         * Create the FileTypeFilter
         * @param categories List of file types to filter on
         */
        FileTypeFilter(List<FileType> categories) {
            this.categories = categories;
        }
        
        FileTypeFilter(FileType category){
            this.categories = new ArrayList<>();
            this.categories.add(category);
        }
        
        @Override
        String getWhereClause() {
            String queryStr = ""; // NON-NLS
            for (FileType cat : categories) {
                for (String type : cat.getMediaTypes()) {
                    if (! queryStr.isEmpty()) {
                        queryStr += ","; // NON-NLS
                    } 
                    queryStr += "\'" + type + "\'"; // NON-NLS
                }
            }
            queryStr = "mime_type IN (" + queryStr + ")"; // NON-NLS
            return queryStr;
        }
        
        @NbBundle.Messages({
            "# {0} - filters",
            "FileSearchFiltering.FileTypeFilter.desc=Files with type: {0}",
            "FileSearchFiltering.FileTypeFilter.or= or ",
        })
        @Override
        String getDesc() {
            String desc = "";
            for (FileType cat : categories) {
                if ( ! desc.isEmpty()) {
                    desc += Bundle.FileSearchFiltering_FileTypeFilter_or();
                }
                desc += cat.toString();
            }
            desc = Bundle.FileSearchFiltering_FileTypeFilter_desc(desc);
            return desc;
        }
    } 
        
    /**
     * A filter for specifying frequency in the central repository.
     */
    static class FrequencyFilter extends FileFilter {
        
        private final List<Frequency> frequencies;
        
        /**
         * Create the FrequencyFilter
         * 
         * @param frequencies List of frequencies that will pass the filter
         */
        FrequencyFilter(List<Frequency> frequencies) {
            this.frequencies = frequencies;
        }
        
        @Override
        String getWhereClause() {
            // Since this relies on the central repository database, there is no
            // query on the case database.
            return ""; // NON-NLS
        }
        
        @Override
        boolean useAlternateFilter() {
            return true;
        }
        
        @Override
        List<ResultFile> applyAlternateFilter (List<ResultFile> currentResults, SleuthkitCase caseDb, 
                EamDb centralRepoDb) throws FileSearchException {
            
            if (centralRepoDb == null) {
                throw new FileSearchException("Can not run Frequency filter with null Central Repository DB"); // NON-NLS
            }
            
            // We have to have run some kind of SQL filter before getting to this point,
            // and should have checked afterward to see if the results were empty.
            if (currentResults.isEmpty()) {
                throw new FileSearchException("Can not run on empty list"); // NON-NLS
            }

            // We can try to make this more efficient later - for now, check the frequency of each file individually
            List<ResultFile> frequencyResults = new ArrayList<>();
            for (ResultFile file : currentResults) {
                try {
                    if (file.getAbstractFile().getMd5Hash() != null && ! file.getAbstractFile().getMd5Hash().isEmpty()) {
                        CorrelationAttributeInstance.Type attributeType = centralRepoDb.getCorrelationTypeById(CorrelationAttributeInstance.FILES_TYPE_ID);
                        long count = centralRepoDb.getCountUniqueCaseDataSourceTuplesHavingTypeValue(attributeType, file.getAbstractFile().getMd5Hash());
                        file.setFrequency(Frequency.fromCount(count));
                    }

                    if (frequencies.contains(file.getFrequency())) {
                        frequencyResults.add(file);
                    }
                } catch (EamDbException | CorrelationAttributeNormalizationException ex) {
                    throw new FileSearchException("Error querying central repository", ex); // NON-NLS
                }
            }
            return frequencyResults;
        }
        
        @NbBundle.Messages({
            "# {0} - filters",
            "FileSearchFiltering.FrequencyFilter.desc=Files with frequency: {0}",
            "FileSearchFiltering.FrequencyFilter.or= or ",
        })
        @Override
        String getDesc() {
            String desc = ""; // NON-NLS
            for (Frequency freq : frequencies) {
                if ( ! desc.isEmpty()) {
                    desc += Bundle.FileSearchFiltering_FrequencyFilter_or();
                }
                desc += freq.name();
            }
            return Bundle.FileSearchFiltering_FrequencyFilter_desc(desc);
        }
    }
    
    private FileSearchFiltering() {
        // Class should not be instantiated
    }    
}
