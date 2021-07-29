// Version:   $Id: $
// Copyright: Copyright(c) 2008 Trace Financial Limited
package org.liquibase.maven.plugins;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

import com.ebao.tool.liquibase.util.LinkedProperties;

import liquibase.CatalogAndSchema;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.diff.output.EbaoDiffOutputControl;
import liquibase.exception.LiquibaseException;
import liquibase.integration.commandline.CommandLineUtils;
import liquibase.logging.LogFactory;
import liquibase.logging.Logger;
import liquibase.resource.ResourceAccessor;
import liquibase.util.StreamUtil;
import liquibase.util.StringUtils;

/**
 * Generates a diff between the specified database and the reference database.
 * The output is either a report or a changelog depending on the value of the diffChangeLogFile parameter.
 *
 * @author Peter Murray
 * @goal diff
 */
public class LiquibaseDatabaseDiff extends AbstractLiquibaseChangeLogMojo {

    private final Logger log = LogFactory.getInstance().getLog();

    /**
     * The fully qualified name of the driver class to use to connect to the reference database.
     * If this is not specified, then the {@link #driver} will be used instead.
     *
     * @parameter expression="${liquibase.referenceDriver}"
     */
    protected String referenceDriver;

    /**
     * The reference database URL to connect to for executing Liquibase.
     *
     * @parameter expression="${liquibase.referenceUrl}"
     */
    protected String referenceUrl;

    /**
     * The reference database username to use to connect to the specified database.
     *
     * @parameter expression="${liquibase.referenceUsername}"
     */
    protected String referenceUsername;

    /**
     * The reference database password to use to connect to the specified database. If this is
     * null then an empty password will be used.
     *
     * @parameter expression="${liquibase.referencePassword}"
     */
    protected String referencePassword;

    /**
     * The reference database catalog.
     *
     * @parameter expression="${liquibase.referenceDefaultCatalogName}"
     */
    protected String referenceDefaultCatalogName;

    /**
     * The reference database schema.
     *
     * @parameter expression="${liquibase.referenceDefaultSchemaName}"
     */
    protected String referenceDefaultSchemaName;

    /**
     * The server id in settings.xml to use when authenticating with.
     *
     * @parameter expression="${liquibase.referenceServer}"
     */
    private String referenceServer;


    /**
     * If this parameter is set, the changelog needed to "fix" differences between the two databases is output. If the file exists, it is appended to.
     * If this is null, a comparison report is output to stdout.
     *
     * @parameter expression="${liquibase.diffChangeLogFile}"
     */
    protected String diffChangeLogFile;

    /**
     * Include the catalog in the diff output? If this is null then the catalog will not be included
     *
     * @parameter expression="${liquibase.diffIncludeCatalog}"
     */
    protected boolean diffIncludeCatalog;

    /**
     * Include the schema in the diff output? If this is null then the schema will not be included
     *
     * @parameter expression="${liquibase.diffIncludeSchema}"
     */
    protected boolean diffIncludeSchema;

    /**
     * Include the tablespace in the diff output? If this is null then the tablespace will not be included
     *
     * @parameter expression="${liquibase.diffIncludeTablespace}"
     */
    protected boolean diffIncludeTablespace;

    /**
     * List of diff types to include in Change Log expressed as a comma separated list from: tables, views, columns, indexes, foreignkeys, primarykeys, uniqueconstraints, data.
     * If this is null then the default types will be: tables, views, columns, indexes, foreignkeys, primarykeys, uniqueconstraints
     *
     * @parameter expression="${liquibase.diffTypes}"
     */
    private String diffTypes;

    /**
     * @parameter expression="${liquibase.diffTable}"
     */
    protected String diffTable;

    /**
     * @parameter expression="${liquibase.refPropertyFile}"
     */
    protected String refPropertyFile;

    /**
     * @parameter expression="${liquibase.skipPropertyFile}"
     */
    protected String skipPropertyFile;

    /**
     * @parameter expression="${liquibase.diffAuthor}"
     */
    protected String diffAuthor;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            ClassLoader artifactClassLoader = getMavenArtifactClassLoader();
            configureFieldsAndValues(getFileOpener(artifactClassLoader), refPropertyFile, "reference");
            if (referenceDefaultSchemaName == null) {
            	referenceDefaultSchemaName = referenceUsername;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

//        if(referenceServer!=null) {
//    		AuthenticationInfo referenceInfo = wagonManager.getAuthenticationInfo(referenceServer);
//    		if (referenceInfo != null) {
//    			referenceUsername = referenceInfo.getUserName();
//    			referencePassword = referenceInfo.getPassword();
//    		}
//    	}

        super.execute();
    }

    @Override
    protected void performLiquibaseTask(Liquibase liquibase) throws LiquibaseException {
        ClassLoader cl = null;
        try {
            cl = getClassLoaderIncludingProjectClasspath();
            Thread.currentThread().setContextClassLoader(cl);
        }
        catch (MojoExecutionException e) {
            throw new LiquibaseException("Could not create the class loader, " + e, e);
        }

        Database db = liquibase.getDatabase();
        Database referenceDatabase = CommandLineUtils.createDatabaseObject(cl, referenceUrl, referenceUsername, referencePassword, referenceDriver, referenceDefaultCatalogName, referenceDefaultSchemaName, outputDefaultCatalog, outputDefaultSchema, null, null, null, null);

        getLog().info("Performing diff database\n" + db.toString() + "\n" + referenceDatabase.toString());
        if (diffChangeLogFile != null) {
            CommandLineUtils.createParentDir(diffChangeLogFile);
            try {
            	EbaoDiffOutputControl diffControl = loadDiffOutputControl(db);

            	String dataDir = CommandLineUtils.createParentDir(diffChangeLogFile);
                diffControl.setDataDir(dataDir);

                if (diffTable != null) {
                	String[] tables = diffTable.split("[,;]");
                	for (String table : tables) {
                		table = table.toUpperCase().trim();
                		diffControl.addDiffTable(table, null);
                		log.info("table to be compared is " + table);
					}
                }

                CommandLineUtils.doDiffToChangeLog(diffChangeLogFile, referenceDatabase, db, diffControl, StringUtils.trimToNull(diffTypes));
                getLog().info("Differences written to Change Log File, " + diffChangeLogFile);
            }
            catch (IOException e) {
                throw new LiquibaseException(e);
            }
            catch (ParserConfigurationException e) {
                throw new LiquibaseException(e);
            }
        } else {
            CommandLineUtils.doDiff(referenceDatabase, db, StringUtils.trimToNull(diffTypes));
        }
    }

    private EbaoDiffOutputControl loadDiffOutputControl(Database database) throws LiquibaseException {
        EbaoDiffOutputControl diffConfig = new EbaoDiffOutputControl(diffIncludeCatalog, diffIncludeSchema, diffIncludeTablespace, database);
        diffConfig.addIncludedSchema(new CatalogAndSchema(referenceDefaultCatalogName, referenceDefaultSchemaName));
        diffConfig.setChangeSetAuthor(diffAuthor);

        if (skipPropertyFile != null && !"".equals(skipPropertyFile)) {
          getLog().info("Loading skipped objects property file:" + skipPropertyFile);
          try {
            ResourceAccessor fo = getFileOpener(getMavenArtifactClassLoader());
            InputStream is = StreamUtil.singleInputStream(skipPropertyFile, fo);
            if (is == null) {
                throw new LiquibaseException("Failed to resolve the properties file:" + propertyFile);
            }
            Properties props = new LinkedProperties();
            props.load(is);
            for (Object key : props.keySet()) {
              getLog().info("Object skipped:" + key);
              diffConfig.addSkippedObject((String)key);
            }
          } catch (IOException e) {
            throw new LiquibaseException("Failed to resolve the properties file:" + propertyFile, e);
          } catch (MojoExecutionException e) {
            throw new LiquibaseException("Failed to resolve the properties file:" + propertyFile, e);
          }
        }

        return diffConfig;
    }



    @Override
    protected void printSettings(String indent) {
        super.printSettings(indent);
        getLog().info(indent + "referenceDriver: " + referenceDriver);
        getLog().info(indent + "referenceUrl: " + referenceUrl);
        getLog().info(indent + "referenceUsername: " + referenceUsername);
        getLog().info(indent + "referencePassword: " + referencePassword);
        getLog().info(indent + "referenceDefaultSchema: " + referenceDefaultSchemaName);
        getLog().info(indent + "diffChangeLogFile: " + diffChangeLogFile);
    }

    @Override
    protected void checkRequiredParametersAreSpecified() throws MojoFailureException {
        super.checkRequiredParametersAreSpecified();

        if (referenceUrl == null) {
            throw new MojoFailureException("A reference database must be provided to perform a diff.");
        }

        if (referencePassword == null) {
            referencePassword = "";
        }
    }

    @Override
    protected boolean isPromptOnNonLocalDatabase() {
        return false;
  }
}
