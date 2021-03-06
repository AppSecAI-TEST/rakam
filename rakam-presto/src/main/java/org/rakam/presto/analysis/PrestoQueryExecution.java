package org.rakam.presto.analysis;

import com.facebook.presto.client.ClientSession;
import com.facebook.presto.client.ClientTypeSignatureParameter;
import com.facebook.presto.client.QueryResults;
import com.facebook.presto.client.StatementClient;
import com.facebook.presto.client.StatementStats;
import com.facebook.presto.spi.type.StandardTypes;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.net.HostAndPort;
import com.google.common.net.HttpHeaders;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.airlift.http.client.HttpRequestFilter;
import io.airlift.http.client.Request;
import io.airlift.log.Logger;
import io.netty.handler.codec.http.HttpResponseStatus;
import okhttp3.OkHttpClient;
import org.rakam.collection.FieldType;
import org.rakam.collection.SchemaField;
import org.rakam.report.QueryError;
import org.rakam.report.QueryExecution;
import org.rakam.report.QueryResult;
import org.rakam.report.QueryStats;
import org.rakam.util.LogUtil;
import org.rakam.util.RakamException;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static org.rakam.collection.FieldType.BINARY;
import static org.rakam.collection.FieldType.BOOLEAN;
import static org.rakam.collection.FieldType.DATE;
import static org.rakam.collection.FieldType.DECIMAL;
import static org.rakam.collection.FieldType.DOUBLE;
import static org.rakam.collection.FieldType.INTEGER;
import static org.rakam.collection.FieldType.LONG;
import static org.rakam.collection.FieldType.STRING;
import static org.rakam.collection.FieldType.TIME;
import static org.rakam.collection.FieldType.TIMESTAMP;
import static org.rakam.report.QueryStats.State.FINISHED;

public class PrestoQueryExecution
        implements QueryExecution
{
    private final static Logger LOGGER = Logger.get(PrestoQueryExecution.class);
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient().newBuilder()
            .build();

    private static final ThreadPoolExecutor QUERY_EXECUTOR = new ThreadPoolExecutor(0, 1000,
            60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(), new ThreadFactoryBuilder()
            .setNameFormat("presto-query-executor").build());

    private final List<List<Object>> data = Lists.newArrayList();
    private final String query;
    private List<SchemaField> columns;

    private final CompletableFuture<QueryResult> result = new CompletableFuture<>();
    public static final DateTimeFormatter PRESTO_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    public static final DateTimeFormatter PRESTO_TIMESTAMP_WITH_TIMEZONE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z");

    private StatementClient client;
    private final Instant startTime;

    public PrestoQueryExecution(ClientSession session, String query)
    {
        this.startTime = Instant.now();
        this.query = query;
        try {
            QUERY_EXECUTOR.execute(new QueryTracker(session));
        }
        catch (RejectedExecutionException e) {
            throw new RakamException("There are already 1000 running queries. Please calm down.", HttpResponseStatus.TOO_MANY_REQUESTS);
        }
    }

    public static FieldType fromPrestoType(String rawType, Iterator<String> parameter)
    {
        switch (rawType) {
            case StandardTypes.BIGINT:
                return LONG;
            case StandardTypes.BOOLEAN:
                return BOOLEAN;
            case StandardTypes.DATE:
                return DATE;
            case StandardTypes.DOUBLE:
                return DOUBLE;
            case StandardTypes.VARBINARY:
            case StandardTypes.HYPER_LOG_LOG:
                return BINARY;
            case StandardTypes.VARCHAR:
                return STRING;
            case StandardTypes.INTEGER:
                return INTEGER;
            case StandardTypes.DECIMAL:
                return DECIMAL;
            case StandardTypes.TIME:
            case StandardTypes.TIME_WITH_TIME_ZONE:
                return TIME;
            case StandardTypes.TIMESTAMP:
            case StandardTypes.TIMESTAMP_WITH_TIME_ZONE:
                return TIMESTAMP;
            case StandardTypes.ARRAY:
                return fromPrestoType(parameter.next(), null).convertToArrayType();
            case StandardTypes.MAP:
                Preconditions.checkArgument(parameter.next().equals(StandardTypes.VARCHAR),
                        "The first parameter of MAP must be STRING");
                return fromPrestoType(parameter.next(), null).convertToMapValueType();
            default:
                return BINARY;
        }
    }

    @Override
    public QueryStats currentStats()
    {
        if (client == null) {
            return new QueryStats(QueryStats.State.WAITING_FOR_AVAILABLE_THREAD);
        }

        if (client.isFailed()) {
            return new QueryStats(QueryStats.State.FAILED);
        }

        StatementStats stats = (!client.isValid() ? client.finalResults() : client.current())
                .getStats();

        int totalSplits = stats.getTotalSplits();
        QueryStats.State state = QueryStats.State.valueOf(stats.getState().toUpperCase(Locale.ENGLISH));

        int percentage = state == FINISHED ? 100 : (totalSplits == 0 ? 0 : stats.getCompletedSplits() * 100 / totalSplits);
        return new QueryStats(stats.isScheduled() ? percentage : null,
                state,
                stats.getNodes(),
                stats.getProcessedRows(),
                stats.getProcessedBytes(),
                stats.getUserTimeMillis(),
                stats.getCpuTimeMillis(),
                stats.getWallTimeMillis());
    }

    @Override
    public boolean isFinished()
    {
        return result.isDone();
    }

    @Override
    public CompletableFuture<QueryResult> getResult()
    {
        return result;
    }

    public static boolean isServerInactive(QueryError error)
    {
        return error.message.startsWith(SERVER_NOT_ACTIVE);
    }

    @Override
    public void kill()
    {
        client.close();
    }

    private static final String SERVER_NOT_ACTIVE = "Database server is not active.";

    private class QueryTracker
            implements Runnable
    {
        private final ClientSession session;
        private final ZoneId zone;

        public QueryTracker(ClientSession session)
        {
            this.session = session;
            this.zone = Optional.ofNullable(session.getTimeZone()).map(e -> ZoneId.of(e.getId())).orElse(ZoneOffset.UTC);
        }

        private void waitForQuery()
        {
            while (client.isValid()) {
                if (Thread.currentThread().isInterrupted()) {
                    client.close();
                    throw new RakamException("Query executor thread was interrupted", INTERNAL_SERVER_ERROR);
                }
                transformAndAdd(client.current());

                client.advance();
            }
        }

        @Override
        public void run()
        {
            try {
                client = new StatementClient(HTTP_CLIENT, session, query);
            }
            catch (RuntimeException e) {
                String message = SERVER_NOT_ACTIVE + " " + e.getMessage();
                LOGGER.warn(e, message);
                result.complete(QueryResult.errorResult(QueryError.create(message), query));
                return;
            }

            try {
                waitForQuery();

                if (client.isClosed()) {
                    QueryError queryError = QueryError.create("Query aborted by user");
                    result.complete(QueryResult.errorResult(queryError, query));
                }
                else if (client.isGone()) {
                    QueryError queryError = QueryError.create("Query is gone (server restarted?)");
                    result.complete(QueryResult.errorResult(queryError, query));
                }
                else if (client.isFailed()) {
                    com.facebook.presto.client.QueryError error = client.finalResults().getError();
                    com.facebook.presto.client.ErrorLocation errorLocation = error.getErrorLocation();
                    QueryError queryError = new QueryError(
                            Optional.ofNullable(error.getFailureInfo().getMessage())
                                    .orElse(error.getFailureInfo().toException().toString()),
                            error.getSqlState(),
                            error.getErrorCode(),
                            errorLocation != null ? errorLocation.getLineNumber() : null,
                            errorLocation != null ? errorLocation.getColumnNumber() : null);
                    LogUtil.logQueryError(query, queryError, PrestoQueryExecutor.class);
                    result.complete(QueryResult.errorResult(queryError, query));
                }
                else {
                    transformAndAdd(client.finalResults());

                    ImmutableMap<String, Object> stats = ImmutableMap.of(
                            QueryResult.EXECUTION_TIME, startTime.until(Instant.now(), ChronoUnit.MILLIS),
                            QueryResult.QUERY, query);

                    result.complete(new QueryResult(columns, data, stats));
                }
            }
            catch (Exception e) {
                QueryError queryError = QueryError.create(e.getMessage());
                LogUtil.logQueryError(query, queryError, PrestoQueryExecutor.class);
                result.complete(QueryResult.errorResult(queryError, query));
            }
        }

        private void transformAndAdd(QueryResults result)
        {
            if (result.getError() != null || result.getColumns() == null) {
                return;
            }

            if (columns == null) {
                columns = result.getColumns().stream()
                        .map(c -> {
                            List<ClientTypeSignatureParameter> arguments = c.getTypeSignature().getArguments();
                            return new SchemaField(c.getName(), fromPrestoType(c.getTypeSignature().getRawType(),
                                    arguments.stream()
                                            .filter(argument -> argument.getKind() == com.facebook.presto.spi.type.ParameterKind.TYPE)
                                            .map(argument -> argument.getTypeSignature().getRawType()).iterator()));
                        })
                        .collect(Collectors.toList());
            }

            if (result.getData() == null) {
                return;
            }

            for (List<Object> objects : result.getData()) {
                Object[] row = new Object[columns.size()];

                for (int i = 0; i < objects.size(); i++) {
                    String type = result.getColumns().get(i).getTypeSignature().getRawType();
                    Object value = objects.get(i);
                    if (value != null) {
                        if (type.equals(StandardTypes.TIMESTAMP)) {
                            try {
                                row[i] = LocalDateTime.parse((CharSequence) value, PRESTO_TIMESTAMP_FORMAT).atZone(zone);
                            }
                            catch (Exception e) {
                                LOGGER.error(e, "Error while parsing Presto TIMESTAMP.");
                            }
                        }
                        else if (type.equals(StandardTypes.TIMESTAMP_WITH_TIME_ZONE)) {
                            try {
                                row[i] = ZonedDateTime.parse((CharSequence) value, PRESTO_TIMESTAMP_WITH_TIMEZONE_FORMAT);
                            }
                            catch (Exception e) {
                                LOGGER.error(e, "Error while parsing Presto TIMESTAMP WITH TIMEZONE.");
                            }
                        }
                        else if (type.equals(StandardTypes.DATE)) {
                            row[i] = LocalDate.parse((CharSequence) value);
                        }
                        else {
                            row[i] = objects.get(i);
                        }
                    }
                    else {
                        row[i] = objects.get(i);
                    }
                }

                data.add(Arrays.asList(row));
            }
        }
    }
}
