package liquibase.actionlogic.core;

import liquibase.database.Database;
import liquibase.exception.ValidationErrors;
import liquibase.sql.Sql;
import liquibase.sql.UnparsedSql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.sqlgenerator.core.AbstractSqlGenerator;
import liquibase.statement.core.DropProcedureStatement;
import liquibase.structure.ObjectReference;
import liquibase.structure.core.StoredProcedure;

public class DropProcedureGenerator extends AbstractSqlGenerator<DropProcedureStatement> {
    @Override
    public ValidationErrors validate(DropProcedureStatement statement, Database database, SqlGeneratorChain sqlGeneratorChain) {
        ValidationErrors validationErrors = new ValidationErrors();
        validationErrors.checkRequiredField("procedureName", statement.getProcedureName());
        return validationErrors;
    }

    @Override
    public Sql[] generateSql(DropProcedureStatement statement, Database database, SqlGeneratorChain sqlGeneratorChain) {
        return new Sql[] {
                new UnparsedSql("DROP PROCEDURE "+database.escapeObjectName(new ObjectReference(statement.getCatalogName(), statement.getSchemaName(), statement.getProcedureName())),
                        new StoredProcedure(new ObjectReference(statement.getCatalogName(), statement.getSchemaName(), statement.getProcedureName())))
        };
    }
}