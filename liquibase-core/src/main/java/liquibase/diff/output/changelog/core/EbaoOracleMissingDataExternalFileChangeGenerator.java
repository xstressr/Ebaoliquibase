package liquibase.diff.output.changelog.core;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;

import liquibase.change.Change;
import liquibase.change.ColumnConfig;
import liquibase.change.core.AddForeignKeyConstraintChange;
import liquibase.change.core.AddPrimaryKeyChange;
import liquibase.change.core.AddUniqueConstraintChange;
import liquibase.change.core.CreateIndexChange;
import liquibase.change.core.CreateSequenceChange;
import liquibase.change.core.CreateTableChange;
import liquibase.change.core.CreateViewChange;
import liquibase.change.core.InsertDataChange;
import liquibase.change.core.InsertUpdateDataChange;
import liquibase.change.core.LoadDataChange;
import liquibase.change.core.LoadDataColumnConfig;
import liquibase.change.core.LoadUpdateDataChange;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.IncludedFile;
import liquibase.database.Database;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.diff.output.ChangeSetToChangeLog;
import liquibase.diff.output.DataInterceptor;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.EbaoDiffOutputControl;
import liquibase.diff.output.changelog.ChangeGeneratorChain;
import liquibase.exception.DatabaseException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.logging.LogFactory;
import liquibase.logging.Logger;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Column;
import liquibase.structure.core.Data;
import liquibase.structure.core.PrimaryKey;
import liquibase.structure.core.Schema;
import liquibase.structure.core.Table;
import liquibase.util.ISODateFormat;
import liquibase.util.csv.CSVWriter;

public class EbaoOracleMissingDataExternalFileChangeGenerator extends MissingDataExternalFileChangeGenerator {

    private final Logger logger = LogFactory.getInstance().getLog();

    private static final String sqlPrefix = "select * from ( select /*+ FIRST_ROWS(n) */ a.*, ROWNUM rnum from (";
    private static final String sqlSuffix = ") a where ROWNUM <= :MAX ) where rnum  >= :MIN";
    private static final int ROWS_PER_FILE = 10000;
    private static final int ROWS_LIMIT = 1000;

    private String dataDir;

    public EbaoOracleMissingDataExternalFileChangeGenerator(String dataDir) {
        super(dataDir);
        this.dataDir = dataDir;
    }

    @Override
    public int getPriority(Class<? extends DatabaseObject> objectType, Database database) {
        int priority = super.getPriority(objectType, database);
        if (PRIORITY_NONE != priority) {
            priority = priority + 1;
        }
        return priority;
    }

    @Override
    public ChangeSet[] fixMissing(DatabaseObject missingObject, DiffOutputControl outputControl, Database referenceDatabase,
            Database comparisionDatabase, ChangeGeneratorChain chain) {
        Data data = (Data) missingObject;

        Table table = data.getTable();
        if (referenceDatabase.isLiquibaseObject(table)) {
            return null;
        }

        EbaoDiffOutputControl ebaoOutputControl = (EbaoDiffOutputControl) outputControl;

        List<ChangeSet> changes = new ArrayList<ChangeSet>();
        try {
            for (EbaoDiffOutputControl.TableCondition filter : ebaoOutputControl.getDiffWhereClause(table.getName())) {
                List<ChangeSet> change = fixMissing(ebaoOutputControl, referenceDatabase, table, filter);
                changes.addAll(change);
            }
        } catch (Exception e) {
            throw new UnexpectedLiquibaseException(e);    
        }

        return changes.toArray(new ChangeSet[changes.size()]);
    }

    public List<ChangeSet> fixMissing(DiffOutputControl outputControl, Database referenceDatabase, Table table,
            EbaoDiffOutputControl.TableCondition filter) throws DatabaseException, IOException, ParserConfigurationException {
            String escapedTableName = referenceDatabase.escapeTableName(table.getSchema().getCatalogName(), table.getSchema().getName(),
                    table.getName());
            String sql = "SELECT * FROM " + escapedTableName;
            String sqlRowCount = "SELECT count(*) FROM " + escapedTableName;

            String condition = filter.getCondition();
            if (condition != null && !"".equals(condition)) {
                sql = sql + " " + condition;
                sqlRowCount = sqlRowCount + " " + condition;
            }
            if (condition != null && !condition.contains("order by") && !condition.contains("connect by")) {
                PrimaryKey primaryKey = table.getPrimaryKey();
                if (primaryKey != null) {
                    for (int i = 0; i < primaryKey.getColumnNamesAsList().size(); i++) {
                        sql = sql + (i == 0 ? " order by " : ",");
                        sql = sql + primaryKey.getColumnNamesAsList().get(i);
                    }
                }
            }

            List<ChangeSet> changeSets = new ArrayList<ChangeSet>();

            JdbcConnection connection = (JdbcConnection) referenceDatabase.getConnection();

            int rowCount = executeQueryRowCount(connection, sqlRowCount);
            if (rowCount == 0) {
                return changeSets;
            }

            logger.info("loading data of " + table + "(" + rowCount + ")");
            if (rowCount <= ROWS_LIMIT) {
                List<Map<String, Object>> rs = executeQuery(connection, sql);
                String filename = filter.getFilename() != null ? filter.getFilename() : table.getName();
                ChangeSet changeSet = addInsertDataChanges(outputControl, table, rs, dataDir, filter.getSubdir(), filename, false);
                if (changeSet != null) {
                    changeSets.add(changeSet);
                }
            } else {
                for (int i = 0; i <= (rowCount - 1) / ROWS_PER_FILE; i++) {
                    String prefix = sqlPrefix;
                    String suffix = sqlSuffix.replace(":MIN", String.valueOf(i * ROWS_PER_FILE + 1))//
                            .replace(":MAX", String.valueOf((i + 1) * ROWS_PER_FILE));
                    String sqlRowBlock = prefix + sql + suffix;
                    List<Map<String, Object>> rs = executeQuery(connection, sqlRowBlock);

                    String filename = filter.getFilename() != null ? filter.getFilename() : table.getName();
                    if ((rowCount - 1) / ROWS_PER_FILE > 0) {
                        filename = filename + "." + (i + 1);
                    }

                    ChangeSet changeSet = addInsertDataChanges(outputControl, table, rs, dataDir, filter.getSubdir(), filename, true);
                    if (changeSet != null) {
                        changeSets.add(changeSet);
                    }
                }
            }

            return changeSets;
    }

    public int executeQueryRowCount(JdbcConnection connection, String sql) {
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = connection.createStatement();
            rs = stmt.executeQuery(sql);
            if (rs.next()) {
                return rs.getInt(1);
            }
            throw new IllegalStateException(sql);
        } catch (Exception e) {
            throw new UnexpectedLiquibaseException(e);
        } finally {
            close(stmt, rs);
        }
    }

    public List<Map<String, Object>> executeQuery(JdbcConnection connection, String sql) {
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);

            rs = stmt.executeQuery(sql);
            
            List<Map<String, Object>> list = new ArrayList<Map<String, Object>>(ROWS_PER_FILE);
            ResultSetMetaData md = rs.getMetaData();
            int columns = md.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<String, Object>();
                for (int i = 1; i <= columns; ++i) {
                    Object value = rs.getObject(i);
                    if (value instanceof Blob) {
                        value = rs.getBytes(i);
                    } else if (value instanceof Clob) {
                        value = rs.getString(i);
                    }
                    row.put(md.getColumnName(i), value);
                }
                list.add(row);
            }
    
            return list;
        } catch (Exception e) {
            throw new UnexpectedLiquibaseException(e);
        } finally {
            close(stmt, rs);
        }
    }

    private void close(Statement stmt, ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException ignore) {
            }
        }
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException ignore) {
            }
        }
    }

    private static final String fileextension1 = ".data.xml";
    private static final String fileextension2 = ".xml";

    private ChangeSet addInsertDataChanges(DiffOutputControl outputControl, Table table, List<Map<String, Object>> rs, String rootDir,
            String subDir, String fileName, boolean csv) throws DatabaseException, IOException, ParserConfigurationException {
        String dataDir = rootDir;
        if (subDir != null) {
            dataDir = rootDir + "/" + subDir;
        }
        File dir = new File(dataDir);
        if (!dir.exists()) {
            boolean done = dir.mkdirs();
            if (!done) {
                throw new IllegalStateException(dataDir);
            }
        }

        List<String> columnNames = new ArrayList<String>();
        for (Column column : table.getColumns()) {
            columnNames.add(column.getName());
        }
        updateUserId(rs, columnNames);

        String id = table.getName() + ".DATA";
        ChangeSet changeSet = ChangeSetUtils.generateChangeSet(id);
        if (csv) {
            LoadDataChange change = addInsertDataChangesCsv(outputControl, table, columnNames, rs, dataDir, fileName + ".csv");
            changeSet.addChange(change);
        } else {
            List<InsertDataChange> list = addInsertDataChangesXml(outputControl, table, columnNames, rs, dataDir);
            for (InsertDataChange change : list) {
                changeSet.addChange(change);
            }
        }

        ChangeSetToChangeLog changeLogWriter = new ChangeSetToChangeLog(changeSet);
        String fileextension = fileName.contains(".") ? fileextension2 : fileextension1;
        String filepath = fileName + fileextension;
        if (dataDir != null) {
            filepath = dataDir + "/" + filepath;
        }
        changeLogWriter.print(filepath);

        String relativeFilePath = fileName + fileextension;
        if (subDir != null) {
            relativeFilePath = subDir + "/" + relativeFilePath;
        }
        IncludedFile includedFile = new IncludedFile(relativeFilePath, table.getName());
        return includedFile;
    }

    private List<InsertDataChange> addInsertDataChangesXml(DiffOutputControl outputControl, Table table, List<String> columnNames,
            List<Map<String, Object>> rs, String dataDir) throws FileNotFoundException, IOException, DatabaseException,
            ParserConfigurationException {
        List<InsertDataChange> changes = new ArrayList<InsertDataChange>();
        for (Map row : rs) {
            InsertDataChange change = newInsertDataChange(table);
            if (outputControl.getIncludeCatalog()) {
                change.setCatalogName(table.getSchema().getCatalogName());
            }
            if (outputControl.getIncludeSchema()) {
                change.setSchemaName(table.getSchema().getName());
            }
            change.setTableName(table.getName());

            // loop over all columns for this row
            for (int i = 0; i < columnNames.size(); i++) {
                ColumnConfig column = new ColumnConfig();
                column.setName(columnNames.get(i));

                Object value = row.get(columnNames.get(i).toUpperCase());
                if (value == null) {
                    column.setValue(null);
                } else if (value instanceof Number) {
                    column.setValueNumeric((Number) value);
                } else if (value instanceof Boolean) {
                    column.setValueBoolean((Boolean) value);
                } else if (value instanceof Date) {
                    column.setValueDate((Date) value);
                } else if (table.getColumn(column.getName()).getType().getTypeName().equals("BLOB")) {
                    String lobFileName = writeLobFile(table, table.getColumn(column.getName()), value, row, dataDir);
                    column.setValueBlobFile(lobFileName);
                } else if (table.getColumn(column.getName()).getType().getTypeName().equals("CLOB")) {
                    String lobFileName = writeLobFile(table, table.getColumn(column.getName()), value, row, dataDir);
                    column.setValueClobFile(lobFileName);
                } else if (value instanceof byte[]) {
                    StringBuilder sb = new StringBuilder();
                    for (byte b : (byte[]) value) {
                        sb.append(String.format("%02x", b & 0xff));
                    }
                    column.setValue(sb.toString());
                } else { // string
                    // column.setValue(value.toString().replace("\\", "\\\\"));
                    column.setValue(value.toString());
                }

                change.addColumn(column);
            }

            // for each row, add a new change
            // (there will be one group per table)
            changes.add(change);
        }

        return changes;
    }

    private String writeLobFile(Table table, Column column, Object value, Map<String, Object> row, String dataDir) throws IOException {
        String filename = table.getName() + "." + column.getName();
        PrimaryKey primaryKey = table.getPrimaryKey();
        for (String pkColumnName : primaryKey.getColumnNamesAsList()) {
            filename = filename + "." + row.get(pkColumnName);
        }
        filename = filename + ".lob";

        File dir = new File("lob");
        File f = new File(filename);
        if (dataDir != null) {
            dir = new File(dataDir, "lob");
            f = new File(dir, filename);
        }
        if (!dir.exists()) {
            dir.mkdir();
        }

        if (column.getType().getTypeName().equals("BLOB")) {
            FileOutputStream out = new FileOutputStream(f);
            out.write((byte[]) value);
            out.close();
        } else {
            FileWriter out = new FileWriter(f);
            out.append((String) value);
            out.close();
        }
        return "lob/" + filename;
    }

    private LoadDataChange addInsertDataChangesCsv(DiffOutputControl outputControl, Table table, List<String> columnNames,
            List<Map<String, Object>> rs, String dataDir, String fileName) throws IOException {
        // String fileName = table.getName().toLowerCase() + ".csv";
        String filePath = fileName;
        if (dataDir != null) {
            filePath = dataDir + "/" + fileName;
        }

        CSVWriter outputFile = new CSVWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filePath), "UTF-8")));
        String[] dataTypes = new String[columnNames.size()];
        String[] line = new String[columnNames.size()];
        for (int i = 0; i < columnNames.size(); i++) {
            line[i] = columnNames.get(i);
        }
        outputFile.writeNext(line);

        for (Map row : rs) {
            line = new String[columnNames.size()];

            for (int i = 0; i < columnNames.size(); i++) {
                Object value = row.get(columnNames.get(i).toUpperCase());
                if (dataTypes[i] == null && value != null) {
                    if (value instanceof Number) {
                        dataTypes[i] = "NUMERIC";
                    } else if (value instanceof Boolean) {
                        dataTypes[i] = "BOOLEAN";
                    } else if (value instanceof Date) {
                        dataTypes[i] = "DATE";
                    } else if (table.getColumn(columnNames.get(i)).getType().getTypeName().equals("BLOB")) {
                        dataTypes[i] = "BLOB";
                    } else if (table.getColumn(columnNames.get(i)).getType().getTypeName().equals("CLOB")) {
                        dataTypes[i] = "CLOB";
                    } else {
                        dataTypes[i] = "STRING";
                    }
                }
                if (value == null) {
                    // line[i] = "NULL";
                    line[i] = null;
                } else {
                    if (value instanceof Date) {
                        line[i] = new ISODateFormat().format(((Date) value));
                    } else if (table.getColumn(columnNames.get(i)).getType().getTypeName().equals("BLOB")) {
                        String lobFileName = writeLobFile(table, table.getColumn(columnNames.get(i)), value, row, dataDir);
                        line[i] = lobFileName;
                    } else if (table.getColumn(columnNames.get(i)).getType().getTypeName().equals("CLOB")) {
                        String lobFileName = writeLobFile(table, table.getColumn(columnNames.get(i)), value, row, dataDir);
                        line[i] = lobFileName;
                    } else if (value instanceof byte[]) {
                        StringBuilder sb = new StringBuilder();
                        for (byte b : (byte[]) value) {
                            sb.append(String.format("%02x", b & 0xff));
                        }
                        line[i] = sb.toString();
                    } else {
                        line[i] = value.toString();
                    }
                }
            }
            outputFile.writeNext(line);
        }
        outputFile.flush();
        outputFile.close();

        LoadDataChange change = newLoadDataChange(table);
        change.setFile(fileName);
        change.setEncoding("UTF-8");
        if (outputControl.getIncludeCatalog()) {
            change.setCatalogName(table.getSchema().getCatalogName());
        }
        if (outputControl.getIncludeSchema()) {
            change.setSchemaName(table.getSchema().getName());
        }
        change.setTableName(table.getName());

        for (int i = 0; i < columnNames.size(); i++) {
            String colName = columnNames.get(i);
            LoadDataColumnConfig columnConfig = new LoadDataColumnConfig();
            columnConfig.setHeader(colName);
            columnConfig.setName(colName);
            columnConfig.setType(dataTypes[i]);

            change.addColumn(columnConfig);
        }
        return change;
    }

    private void updateUserId(List<Map<String, Object>> rs, List<String> columnNames) {
        for (String column : columnNames) {
            String key = column.toUpperCase();
            if (DataInterceptor.getUserIdColumnNames().contains(key)) {
                for (Map row : rs) {
                    Object value = row.get(key);
                    if (value != null && value instanceof Number) {
                        row.put(key, 401L);
                    }
                }
            }
        }
    }

    private InsertDataChange newInsertDataChange(Table table) {
        if (EbaoDiffOutputControl.isInsertUpdatePreferred()) {
            InsertUpdateDataChange change = new InsertUpdateDataChange();
            change.setPrimaryKey(table.getPrimaryKey().getColumnNames());
            return change;
        } else {
            return new InsertDataChange();
        }
    }

    private LoadDataChange newLoadDataChange(Table table) {
        if (EbaoDiffOutputControl.isInsertUpdatePreferred()) {
            LoadUpdateDataChange change = new LoadUpdateDataChange();
            change.setPrimaryKey(table.getPrimaryKey().getColumnNames());
            return change;
        } else {
            return new LoadDataChange();
        }
    }

    protected ChangeSet generateChangeSet(Change change) {
        String id = null;
        if (change instanceof CreateTableChange) {
            id = ((CreateTableChange) change).getTableName();
        } else if (change instanceof AddPrimaryKeyChange) {
            id = ((AddPrimaryKeyChange) change).getConstraintName();
        } else if (change instanceof AddForeignKeyConstraintChange) {
            id = ((AddForeignKeyConstraintChange) change).getConstraintName();
        } else if (change instanceof AddUniqueConstraintChange) {
            id = ((AddUniqueConstraintChange) change).getConstraintName();
        } else if (change instanceof CreateIndexChange) {
            id = ((CreateIndexChange) change).getIndexName();
        } else if (change instanceof CreateViewChange) {
            id = ((CreateViewChange) change).getViewName();
        } else if (change instanceof CreateSequenceChange) {
            id = ((CreateSequenceChange) change).getSequenceName();
        }

        ChangeSet changeSet = ChangeSetUtils.generateChangeSet(id);
        changeSet.addChange(change);

        return changeSet;
    }

}