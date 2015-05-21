package org.rakam.plugin;

import com.facebook.presto.sql.SQLFormatter;
import org.rakam.collection.SchemaField;
import org.rakam.collection.event.metastore.QueryMetadataStore;
import org.rakam.report.QueryExecution;
import org.rakam.report.QueryExecutor;
import org.rakam.report.QueryResult;
import org.rakam.report.QueryStats;
import org.rakam.util.RakamException;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.lang.String.format;

/**
 * Created by buremba <Burak Emre Kabakcı> on 02/04/15 05:30.
 */
public abstract class MaterializedViewService {
    private final QueryMetadataStore database;
    private final QueryExecutor queryExecutor;
    private final Clock clock;

    public MaterializedViewService(QueryExecutor queryExecutor, QueryMetadataStore database, Clock clock) {
        this.database = database;
        this.queryExecutor = queryExecutor;
        this.clock = clock;
    }

    public CompletableFuture<Void> create(MaterializedView materializedView) {
        QueryResult result = queryExecutor.executeStatement(materializedView.project, format("CREATE TABLE materialized.%s AS (%s LIMIT 0)",
                materializedView.table_name, SQLFormatter.formatSql(materializedView.query))).getResult().join();
        if(result.isFailed()) {
            throw new RakamException("Couldn't created table: "+result.getError().toString(), 400);
        }
        database.saveMaterializedView(materializedView);
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<QueryResult> delete(String project, String name) {
        MaterializedView materializedView = database.getMaterializedView(project, name);
        database.deleteMaterializedView(project, name);
        return queryExecutor.executeStatement(project, format("DELETE TABLE materialized.%s", materializedView.table_name)).getResult();
    }
    public List<MaterializedView> list(String project) {
        return database.getMaterializedViews(project);
    }

    public MaterializedView get(String project, String name) {
        return database.getMaterializedView(project, name);
    }

    public abstract Map<String, List<SchemaField>> getSchemas(String s);

    public QueryExecution update(MaterializedView materializedView) {
        if(materializedView.lastUpdate!=null) {
            QueryResult result = queryExecutor.executeStatement(materializedView.project,
                    format("DROP TABLE materialized.%s", materializedView.table_name)).getResult().join();
            if(result.isFailed()) {
                return new QueryExecution() {
                    @Override
                    public QueryStats currentStats() {
                        return null;
                    }

                    @Override
                    public boolean isFinished() {
                        return true;
                    }

                    @Override
                    public CompletableFuture<QueryResult> getResult() {
                        return CompletableFuture.completedFuture(result);
                    }

                    @Override
                    public String getQuery() {
                        return null;
                    }
                };
            }
        }
        QueryExecution queryExecution = queryExecutor.executeStatement(materializedView.project, format("CREATE TABLE materialized.%s AS (%s)",
                materializedView.table_name, materializedView.query));

        queryExecution.getResult().thenAccept(result -> {
            if(!result.isFailed()) {
                database.updateMaterializedView(materializedView.project, materializedView.name, clock.instant());
            }
        });

        return queryExecution;
    }}