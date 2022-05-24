/*******************************************************************************
 * Copyright (c) 2022 Eurotech and/or its affiliates and others
 * 
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 * 
 * Contributors:
 *  Eurotech
 *******************************************************************************/
package org.eclipse.kura.wire.basedb.common;

import static java.util.Objects.requireNonNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.kura.db.BaseDbService;

/**
 * The Class DbServiceHelper is responsible for providing {@link BaseDbService}
 * instance dependent helper methods for quick database related operations
 */
public class BaseDbServiceHelper {

    private static final Logger logger = LogManager.getLogger(BaseDbServiceHelper.class);

    protected final BaseDbService dbService;

    /**
     * Instantiates a new DB Service Helper.
     *
     * @param dbService
     *            the DB service
     * @throws NullPointerException
     *             if argument is null
     */
    protected BaseDbServiceHelper(final BaseDbService dbService) {
        requireNonNull(dbService, "DB Service cannot be null");
        this.dbService = dbService;
    }

    /**
     * Creates instance of {@link BaseDbServiceHelper}
     *
     * @param dbService
     *            the {@link BaseDbService}
     * @return the instance of {@link BaseDbServiceHelper}
     * @throws org.eclipse.kura.KuraRuntimeException
     *             if argument is null
     */
    public static BaseDbServiceHelper of(final BaseDbService dbService) {
        return new BaseDbServiceHelper(dbService);
    }

    /**
     * Executes the provided SQL query.
     *
     * @param sql
     *            the SQL query to execute
     * @param params
     *            the extra parameters needed for the query
     * @throws SQLException
     *             the SQL exception
     * @throws NullPointerException
     *             if SQL query argument is null
     */
    public synchronized void execute(final Connection c, final String sql, final Integer... params)
            throws SQLException {
        requireNonNull(sql, "SQL query cannot be null");
        logger.debug("Executing SQL query... {}", sql);

        try (final PreparedStatement stmt = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setInt(1 + i, params[i]);
            }
            stmt.execute();
            c.commit();
        }

        logger.debug("Executing SQL query... Done");
    }

    public Connection getConnection() throws SQLException {
        return this.dbService.getConnection();
    }

    /**
     * Encloses the provided String between double quotes and escapes
     * any double quote present in the string.
     *
     * @param string
     *            the string to be sanitized
     * @return the escaped string
     * @throws NullPointerException
     *             if argument is null
     */
    public String sanitizeSqlTableAndColumnName(final String string) {
        requireNonNull(string, "Provided string cannot be null");
        logger.debug("Sanitizing the provided string... {}", string);
        final String sanitizedName = string.replaceAll("\"", "\"\"");
        return "\"" + sanitizedName + "\"";
    }
}
