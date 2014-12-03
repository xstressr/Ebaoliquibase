package liquibase.statement.prepared.database;

import java.sql.PreparedStatement;

import liquibase.database.Database;
import liquibase.database.core.PostgresDatabase;
import liquibase.exception.DatabaseException;
import liquibase.statement.ExecutablePreparedStatement;
import liquibase.statement.prepared.AbstractPreparedStatement;
import liquibase.statement.prepared.InsertExecutablePreparedStatement;
import liquibase.statement.prepared.InsertExecutablePreparedStatementChange;
import liquibase.statement.prepared.UpdateExecutablePreparedStatement;

public class OracleUpsertExecutablePreparedStatement extends
    AbstractPreparedStatement implements ExecutablePreparedStatement {

  private final Database database;
  private final InsertExecutablePreparedStatement insert;
  private final InsertExecutablePreparedStatement update;
  private final InsertExecutablePreparedStatementChange change;
  private Info statement;

  public OracleUpsertExecutablePreparedStatement(Database database,
      InsertExecutablePreparedStatementChange change) {
    this.database = database;
    this.insert = new InsertExecutablePreparedStatement(database, change);
  	this.update = new UpdateExecutablePreparedStatement(database, change);
    this.change = change;
  }

  @Override
  public Info getStatement() {
    if (statement != null) {
      return statement;
    }

    StringBuilder sql = new StringBuilder();
    sql.append("BEGIN\n  ");
    sql.append(update.getStatement().sql).append(";\n  ");
    sql.append("IF SQL%ROWCOUNT=0 THEN\n    ");
    sql.append(insert.getStatement().sql).append(";\n  ");
    sql.append("END IF;\n");
    sql.append("END;");

    statement = new Info(sql.toString(), update.getStatement().columns,
        getParameters(update.getStatement().columns, change.getChangeSet()
            .getFilePath()));
    return statement;
  }

  @Override
  public void setParameter(PreparedStatement stmt) throws DatabaseException {
    update.setParameter(stmt);
    if(!(database instanceof PostgresDatabase)){
      insert.setParameter(stmt, update.getParameterSize() + 1);
    }
  }

}
