/***** BEGIN LICENSE BLOCK *****
 * Copyright (c) 2012-2013 Karol Bucek <self@kares.org>
 * Copyright (c) 2006-2010 Nick Sieger <nick@nicksieger.com>
 * Copyright (c) 2006-2007 Ola Bini <ola.bini@gmail.com>
 * Copyright (c) 2008-2009 Thomas E Enebo <enebo@acm.org>
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 ***** END LICENSE BLOCK *****/

package arjdbc.sqlite3;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.DatabaseMetaData;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.List;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.RubyString;
import org.jruby.anno.JRubyMethod;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

import arjdbc.jdbc.Callable;
import arjdbc.jdbc.RubyJdbcConnection;
import arjdbc.util.StringHelper;
import org.jruby.util.SafePropertyAccessor;

/**
 *
 * @author enebo
 */
@org.jruby.anno.JRubyClass(name = "ActiveRecord::ConnectionAdapters::SQLite3JdbcConnection")
public class SQLite3RubyJdbcConnection extends RubyJdbcConnection {
    private static final long serialVersionUID = -5783855018818472773L;

    public SQLite3RubyJdbcConnection(Ruby runtime, RubyClass metaClass) {
        super(runtime, metaClass);
    }

    public static RubyClass createSQLite3JdbcConnectionClass(Ruby runtime, RubyClass jdbcConnection) {
        final RubyClass clazz = getConnectionAdapters(runtime). // ActiveRecord::ConnectionAdapters
            defineClassUnder("SQLite3JdbcConnection", jdbcConnection, ALLOCATOR);
        clazz.defineAnnotatedMethods( SQLite3RubyJdbcConnection.class );
        return clazz;
    }

    public static RubyClass load(final Ruby runtime) {
        RubyClass jdbcConnection = getJdbcConnection(runtime);
        return createSQLite3JdbcConnectionClass(runtime, jdbcConnection);
    }

    protected static ObjectAllocator ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            return new SQLite3RubyJdbcConnection(runtime, klass);
        }
    };

    @JRubyMethod(name = {"last_insert_rowid", "last_insert_id"}, alias = "last_insert_row_id")
    public IRubyObject last_insert_rowid(final ThreadContext context)
        throws SQLException {
        return withConnection(context, new Callable<IRubyObject>() {
            public IRubyObject call(final Connection connection) throws SQLException {
                Statement statement = null; ResultSet genKeys = null;
                try {
                    statement = connection.createStatement();
                    // NOTE: strangely this will work and has been used for quite some time :
                    //return mapGeneratedKeys(context.runtime, connection, statement, true);
                    // but we should assume SQLite JDBC will prefer sane API usage eventually :
                    genKeys = statement.executeQuery("SELECT last_insert_rowid()");
                    return doMapGeneratedKeys(context.runtime, genKeys, true);
                }
                catch (final SQLException e) {
                    debugMessage(context.runtime, "failed to get generated keys: ", e.getMessage());
                    throw e;
                }
                finally { close(genKeys); close(statement); }
            }
        });
    }

    // NOTE: interestingly it supports getGeneratedKeys but not executeUpdate
    // + the driver does not report it supports it via the meta-data yet does
    @Override
    protected boolean supportsGeneratedKeys(final Connection connection) throws SQLException {
        return true;
    }

    @Override
    protected Statement createStatement(final ThreadContext context, final Connection connection)
        throws SQLException {
        final Statement statement = connection.createStatement();
        IRubyObject escapeProcessing = getConfigValue(context, "statement_escape_processing");
        if ( escapeProcessing != null && ! escapeProcessing.isNil() ) {
            statement.setEscapeProcessing( escapeProcessing.isTrue() );
        }
        // else leave as is by default
        return statement;
    }

    @Override
    protected IRubyObject indexes(final ThreadContext context, String table, final String name, String schema) {
        if ( table != null ) {
            final int i = table.indexOf('.');
            if ( i > 0 && schema == null ) {
                schema = table.substring(0, i);
                table = table.substring(i + 1);
            }
        }
        final String tableName = table;
        final String schemaName = schema;
        // return super.indexes(context, tableName, name, schemaName);
        return withConnection(context, new Callable<IRubyObject>() {
            public RubyArray call(final Connection connection) throws SQLException {
                final Ruby runtime = context.runtime;
                final RubyClass indexDefinition = getIndexDefinition(runtime);

                final TableName table = extractTableName(connection, schemaName, tableName);

                final List<RubyString> primaryKeys = primaryKeys(context, connection, table);

                final DatabaseMetaData metaData = connection.getMetaData();
                ResultSet indexInfoSet = null;
                try {
                    indexInfoSet = metaData.getIndexInfo(table.catalog, table.schema, table.name, false, true);
                }
                catch (SQLException e) {
                    final String msg = e.getMessage();
                    if ( msg != null && msg.startsWith("[SQLITE_ERROR] SQL error or missing database") ) {
                        return RubyArray.newEmptyArray(runtime); // on 3.8.7 getIndexInfo fails if table has no indexes
                    }
                    throw e;
                }
                final RubyArray indexes = RubyArray.newArray(runtime, 8);
                try {
                    String currentIndex = null;

                    while ( indexInfoSet.next() ) {
                        String indexName = indexInfoSet.getString(INDEX_INFO_NAME);
                        if ( indexName == null ) continue;

                        final String columnName = indexInfoSet.getString(INDEX_INFO_COLUMN_NAME);
                        final RubyString rubyColumnName = RubyString.newUnicodeString(runtime, columnName);
                        if ( primaryKeys.contains(rubyColumnName) ) continue;

                        // We are working on a new index
                        if ( ! indexName.equals(currentIndex) ) {
                            currentIndex = indexName;

                            String indexTableName = indexInfoSet.getString(INDEX_INFO_TABLE_NAME);

                            final boolean nonUnique = indexInfoSet.getBoolean(INDEX_INFO_NON_UNIQUE);

                            IRubyObject[] args = new IRubyObject[] {
                                RubyString.newUnicodeString(runtime, indexTableName), // table_name
                                RubyString.newUnicodeString(runtime, indexName), // index_name
                                runtime.newBoolean( ! nonUnique ), // unique
                                runtime.newArray() // [] for column names, we'll add to that in just a bit
                                // orders, (since AR 3.2) where, type, using (AR 4.0)
                            };

                            indexes.append( indexDefinition.callMethod(context, "new", args) ); // IndexDefinition.new
                        }

                        // One or more columns can be associated with an index
                        IRubyObject lastIndexDef = indexes.isEmpty() ? null : indexes.entry(-1);
                        if ( lastIndexDef != null ) {
                            ( (RubyArray) lastIndexDef.callMethod(context, "columns") ).append(rubyColumnName);
                        }
                    }

                    return indexes;

                } finally { close(indexInfoSet); }
            }
        });
    }

    @Override
    protected TableName extractTableName(
            final Connection connection, String catalog, String schema,
            final String tableName) throws IllegalArgumentException, SQLException {

        final List<String> nameParts = split(tableName, '.');
        final int len = nameParts.size();
        if ( len > 3 ) {
            throw new IllegalArgumentException("table name: " + tableName + " should not contain more than 2 '.'");
        }

        String name = tableName;

        if ( len == 2 ) {
            schema = nameParts.get(0);
            name = nameParts.get(1);
        }
        else if ( len == 3 ) {
            catalog = nameParts.get(0);
            schema = nameParts.get(1);
            name = nameParts.get(2);
        }

        if ( schema != null ) {
            // NOTE: hack to work-around SQLite JDBC ignoring schema :
            return new TableName(catalog, null, schema + '.' + name);
        }
        return new TableName(catalog, schema, name);
    }

    @Override
    protected IRubyObject jdbcToRuby(final ThreadContext context,
        final Ruby runtime, final int column, int type, final ResultSet resultSet)
        throws SQLException {
        // This is rather gross, and only needed because the resultset metadata for SQLite tries to be overly
        // clever, and returns a type for the column of the "current" row, so an integer value stored in a
        // decimal column is returned as Types.INTEGER.  Therefore, if the first row of a resultset was an
        // integer value, all rows of that result set would get truncated.
        if ( resultSet instanceof ResultSetMetaData ) {
            type = ((ResultSetMetaData) resultSet).getColumnType(column);
        }
        // since JDBC 3.8 there seems to be more cleverness built-in that
        // causes (<= 3.8.7) to get things wrong ... reports DATE SQL type
        // for "datetime" columns :
        if ( type == Types.DATE ) {
            // return timestampToRuby(context, runtime, resultSet, column);
            return stringToRuby(context, runtime, resultSet, column);
        }
        return super.jdbcToRuby(context, runtime, column, type, resultSet);
    }

    @Override
    protected boolean useByteStrings() { return true; }

    @Override
    protected IRubyObject streamToRuby(final ThreadContext context,
        final Ruby runtime, final ResultSet resultSet, final int column)
        throws SQLException, IOException {
        final byte[] bytes = resultSet.getBytes(column);
        // CAST(X'' AS BLOB) since SQLite 3.8 are returned as null
        // while they were returned as byte[0] previously in 3.7.x
        if ( resultSet.wasNull() ) return context.nil;
        if ( bytes == null ) return RubyString.newEmptyString(runtime);
        return StringHelper.newString( runtime, bytes );
    }

    @Override
    protected IRubyObject stringToRuby(final ThreadContext context,
        final Ruby runtime, final ResultSet resultSet, final int column)
        throws SQLException {
        final byte[] value = resultSet.getBytes(column);
        if ( resultSet.wasNull() ) return context.nil;
        if ( value == null ) return StringHelper.newEmptyUTF8String(runtime);
        return StringHelper.newUTF8String(runtime, value);
    }

    @Override
    protected RubyArray mapTables(final ThreadContext context, final Connection connection,
            final String catalog, final String schemaPattern, final String tablePattern,
            final ResultSet tablesSet) throws SQLException {
        final RubyArray tables = RubyArray.newArray(context.runtime, 24);
        while ( tablesSet.next() ) {
            String name = tablesSet.getString(TABLES_TABLE_NAME);
            name = name.toLowerCase(); // simply lower-case for SQLite3
            tables.append( cachedString(context, name) );
        }
        return tables;
    }

    @Override
    protected String caseConvertIdentifierForRails(final Connection connection, final String value) {
        return value;
    }

    @Override
    protected String caseConvertIdentifierForJdbc(final Connection connection, final String value) {
        return value;
    }

    private static class SavepointStub implements Savepoint {

        static final SavepointStub INSTANCE = new SavepointStub();

        @Override
        public int getSavepointId() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getSavepointName() throws SQLException {
            throw new UnsupportedOperationException();
        }

    }

    private static Boolean useSavepointAPI;
    static {
        final String savepoints = SafePropertyAccessor.getProperty("arjdbc.sqlite.savepoints");
        if ( savepoints != null ) useSavepointAPI = Boolean.parseBoolean(savepoints);
    }

    private static boolean useSavepointAPI(final ThreadContext context) {
        final Boolean working = useSavepointAPI;
        if ( working == null ) {
            try { // available since JDBC-SQLite 3.8.9
                context.runtime.getJavaSupport().loadJavaClass("org.sqlite.jdbc3.JDBC3Savepoint");
                return useSavepointAPI = Boolean.TRUE;
            }
            catch (ClassNotFoundException ex) { /* < 3.8.9 */ }
            // catch (RuntimeException ex) { }
            return useSavepointAPI = Boolean.FALSE;
        }
        return working;
    }

    @Override
    @JRubyMethod(name = "create_savepoint", required = 1)
    public IRubyObject create_savepoint(final ThreadContext context, final IRubyObject name) {
        if ( useSavepointAPI(context) ) return super.create_savepoint(context, name);

        if ( name == null || name.isNil() ) {
            throw new IllegalArgumentException("create_savepoint (without name) not implemented!");
        }
        final Connection connection = getConnection(); Statement statement = null;
        try {
            connection.setAutoCommit(false);
            // NOTE: JDBC driver does not support setSavepoint(String) :
            ( statement = connection.createStatement() ).execute("SAVEPOINT " + name.toString());

            getSavepoints(context).put(name, SavepointStub.INSTANCE);

            return name;
        }
        catch (SQLException e) {
            return handleException(context, e);
        }
        finally { close(statement); }
    }

    @Override
    @JRubyMethod(name = "rollback_savepoint", required = 1)
    public IRubyObject rollback_savepoint(final ThreadContext context, final IRubyObject name) {
        if ( useSavepointAPI(context) ) return super.rollback_savepoint(context, name);

        final Connection connection = getConnection(true); Statement statement = null;
        try {
            if ( getSavepoints(context).get(name) == null ) {
                throw context.runtime.newRuntimeError("could not rollback savepoint: '" + name + "' (not set)");
            }
            // NOTE: JDBC driver does not implement rollback(Savepoint) :
            ( statement = connection.createStatement() ).execute("ROLLBACK TO SAVEPOINT " + name.toString());

            return context.nil;
        }
        catch (SQLException e) {
            return handleException(context, e);
        }
        finally { close(statement); }
    }

    @Override
    @JRubyMethod(name = "release_savepoint", required = 1)
    public IRubyObject release_savepoint(final ThreadContext context, final IRubyObject name) {
        if ( useSavepointAPI(context) ) return super.release_savepoint(context, name);

        final Connection connection = getConnection(true); Statement statement = null;
        try {
            if ( getSavepoints(context).remove(name) == null ) {
                throw context.runtime.newRuntimeError("could not release savepoint: '" + name + "' (not set)");
            }
            // NOTE: JDBC driver does not implement release(Savepoint) :
            ( statement = connection.createStatement() ).execute("RELEASE SAVEPOINT " + name.toString());

            return context.nil;
        }
        catch (SQLException e) {
            return handleException(context, e);
        }
        finally { close(statement); }
    }

}
