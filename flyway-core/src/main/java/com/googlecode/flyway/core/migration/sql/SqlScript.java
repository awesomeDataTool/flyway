/**
 * Copyright (C) 2010-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.googlecode.flyway.core.migration.sql;

import com.googlecode.flyway.core.dbsupport.DbSupport;
import com.googlecode.flyway.core.util.StringUtils;
import com.googlecode.flyway.core.util.jdbc.JdbcTemplate;
import com.googlecode.flyway.core.util.logging.Log;
import com.googlecode.flyway.core.util.logging.LogFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Sql script containing a series of statements terminated by semi-columns (;). Single-line (--) and multi-line (/* * /)
 * comments are stripped and ignored.
 */
public class SqlScript {
    private static final Log LOG = LogFactory.getLog(SqlScript.class);

    /**
     * The database-specific support.
     */
    private final DbSupport dbSupport;

    /**
     * The sql statements contained in this script.
     */
    private final List<SqlStatement> sqlStatements;

    /**
     * Creates a new sql script from this source with these placeholders to replace.
     *
     * @param sqlScriptSource     The sql script as a text block with all placeholders still present.
     * @param placeholderReplacer The placeholder replacer to apply to sql migration scripts.
     * @param dbSupport           The database-specific support.
     */
    public SqlScript(String sqlScriptSource, PlaceholderReplacer placeholderReplacer, DbSupport dbSupport) {
        this.dbSupport = dbSupport;
        this.sqlStatements = parse(sqlScriptSource, placeholderReplacer);
    }

    /**
     * Creates a new SqlScript with these statements and this name.
     *
     * @param sqlStatements The statements of the script.
     * @param dbSupport     The database-specific support.
     */
    public SqlScript(List<SqlStatement> sqlStatements, DbSupport dbSupport) {
        this.dbSupport = dbSupport;
        this.sqlStatements = sqlStatements;
    }

    /**
     * Dummy constructor to increase testability.
     *
     * @param dbSupport The database-specific support.
     */
    SqlScript(DbSupport dbSupport) {
        this.dbSupport = dbSupport;
        this.sqlStatements = null;
    }

    /**
     * For increased testability.
     *
     * @return The sql statements contained in this script.
     */
    public List<SqlStatement> getSqlStatements() {
        return sqlStatements;
    }

    /**
     * Executes this script against the database.
     *
     * @param jdbcTemplate The jdbc template to use to execute this script.
     */
    public void execute(final JdbcTemplate jdbcTemplate) {
        for (SqlStatement sqlStatement : sqlStatements) {
            sqlStatement.execute(jdbcTemplate);
        }
    }

    /**
     * Parses this script source into statements.
     *
     * @param sqlScriptSource     The script source to parse.
     * @param placeholderReplacer The placeholder replacer to use.
     * @return The parsed statements.
     */
    /* private -> for testing */
    List<SqlStatement> parse(String sqlScriptSource, PlaceholderReplacer placeholderReplacer) {
        Reader reader = new StringReader(sqlScriptSource);
        List<String> rawLines = readLines(reader);
        List<String> noPlaceholderLines = replacePlaceholders(rawLines, placeholderReplacer);
        return linesToStatements(noPlaceholderLines);
    }


    /**
     * Turns these lines in a series of statements.
     *
     * @param lines The lines to analyse.
     * @return The statements contained in these lines (in order).
     */
    /* private -> for testing */
    List<SqlStatement> linesToStatements(List<String> lines) {
        List<SqlStatement> statements = new ArrayList<SqlStatement>();

        boolean inMultilineComment = false;
        Delimiter nonStandardDelimiter = null;
        SqlStatementBuilder sqlStatementBuilder = dbSupport.createSqlStatementBuilder();

        for (int lineNumber = 1; lineNumber <= lines.size(); lineNumber++) {
            String line = lines.get(lineNumber - 1);

            if (sqlStatementBuilder.isEmpty()) {
                if (!StringUtils.hasText(line)) {
                    // Skip empty line between statements.
                    continue;
                }

                String trimmedLine = line.trim();

                if (!sqlStatementBuilder.isCommentDirective(trimmedLine)) {
                    if (trimmedLine.startsWith("/*")) {
                        inMultilineComment = true;
                    }

                    if (inMultilineComment) {
                        if (trimmedLine.endsWith("*/")) {
                            inMultilineComment = false;
                        }
                        // Skip line part of a multi-line comment
                        continue;
                    }

                    if (sqlStatementBuilder.isSingleLineComment(trimmedLine)) {
                        // Skip single-line comment
                        continue;
                    }
                }

                Delimiter newDelimiter = sqlStatementBuilder.extractNewDelimiterFromLine(line);
                if (newDelimiter != null) {
                    nonStandardDelimiter = newDelimiter;
                    // Skip this line as it was an explicit delimiter change directive outside of any statements.
                    continue;
                }

                sqlStatementBuilder.setLineNumber(lineNumber);

                // Start a new statement, marking it with this line number.
                if (nonStandardDelimiter != null) {
                    sqlStatementBuilder.setDelimiter(nonStandardDelimiter);
                }
            }

            sqlStatementBuilder.addLine(line);

            if (sqlStatementBuilder.isTerminated()) {
                SqlStatement sqlStatement = sqlStatementBuilder.getSqlStatement();
                statements.add(sqlStatement);
                LOG.debug("Found statement at line " + sqlStatement.getLineNumber() + ": " + sqlStatement.getSql());

                sqlStatementBuilder = dbSupport.createSqlStatementBuilder();
            }
        }

        // Catch any statements not followed by delimiter.
        if (!sqlStatementBuilder.isEmpty()) {
            statements.add(sqlStatementBuilder.getSqlStatement());
        }

        return statements;
    }

    /**
     * Parses the textual data provided by this reader into a list of lines.
     *
     * @param reader The reader for the textual data.
     * @return The list of lines (in order).
     * @throws IllegalStateException Thrown when the textual data parsing failed.
     */
    private List<String> readLines(Reader reader) {
        List<String> lines = new ArrayList<String>();

        BufferedReader bufferedReader = new BufferedReader(reader);
        String line;

        try {
            while ((line = bufferedReader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot parse lines", e);
        }

        return lines;
    }

    /**
     * Replaces the placeholders in these lines with their values.
     *
     * @param lines               The input lines.
     * @param placeholderReplacer The placeholder replacer to apply to sql migration scripts.
     * @return The lines with placeholders replaced.
     */
    private List<String> replacePlaceholders(List<String> lines, PlaceholderReplacer placeholderReplacer) {
        List<String> noPlaceholderLines = new ArrayList<String>(lines.size());

        for (String line : lines) {
            noPlaceholderLines.add(placeholderReplacer.replacePlaceholders(line));
        }

        return noPlaceholderLines;
    }
}
