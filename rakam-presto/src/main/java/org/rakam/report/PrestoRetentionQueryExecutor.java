/*
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
package org.rakam.report;

import com.facebook.presto.sql.ExpressionFormatter;
import com.facebook.presto.sql.tree.Expression;
import com.google.common.primitives.Ints;
import com.google.inject.Inject;
import org.rakam.analysis.RetentionQueryExecutor;
import org.rakam.collection.event.metastore.Metastore;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalField;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.String.format;

/**
 * Created by buremba <Burak Emre Kabakcı> on 27/08/15 06:49.
 */
public class PrestoRetentionQueryExecutor implements RetentionQueryExecutor {
    private final int MAXIMUM_LEAD = 15;
    private final PrestoQueryExecutor executor;
    private final Metastore metastore;
    private final PrestoConfig config;

    @Inject
    public PrestoRetentionQueryExecutor(PrestoQueryExecutor executor, Metastore metastore, PrestoConfig config) {
        this.executor = executor;
        this.metastore = metastore;
        this.config = config;
    }

    @Override
    public QueryExecution query(String project,
                                Optional<RetentionAction> firstAction,
                                Optional<RetentionAction> returningAction,
                                DateUnit dateUnit,
                                Optional<String> dimension,
                                LocalDate startDate,
                                LocalDate endDate) {

        String timeColumn;
        String timeTransformation;

        if(dateUnit == DateUnit.DAY) {
            long seconds = ChronoUnit.DAYS.getDuration().getSeconds();
            timeColumn = format("time/%d", seconds);
            timeTransformation = format("cast(from_unixtime((data.time)*%d) as date)", seconds);
        } else
        if(dateUnit == DateUnit.WEEK) {
            timeColumn = format("cast(date_trunc('week', from_unixtime(time)) as date)");
            timeTransformation ="data.time";
        } else
        if(dateUnit == DateUnit.MONTH) {
            timeColumn = format("cast(date_trunc('month', from_unixtime(time)) as date)");
            timeTransformation ="data.time";
        } else {
            throw new UnsupportedOperationException();
        }

        StringBuilder from = new StringBuilder();

        if(returningAction.isPresent()) {
            RetentionAction retentionAction = returningAction.get();
            from.append(generateQuery(project, retentionAction.collection(), timeColumn, dimension,
                    retentionAction.filter(),
                    startDate, endDate));
        } else {
            Set<String> collectionNames = metastore.getCollectionNames(project);
            from.append(collectionNames.stream()
                    .map(collection -> generateQuery(project, collection, timeColumn, dimension, Optional.empty(), startDate, endDate))
                    .collect(Collectors.joining(" union all ")));
        }

        LocalDate start;
        LocalDate end;
        if(dateUnit == DateUnit.MONTH) {
            start = startDate.withDayOfMonth(1);
            end = endDate.withDayOfMonth(1).plus(1, ChronoUnit.MONTHS);
        }else
        if(dateUnit == DateUnit.WEEK) {
            TemporalField fieldUS = WeekFields.of(Locale.US).dayOfWeek();
            start = startDate.with(fieldUS, 1);
            end = endDate.with(fieldUS, 1).plus(1, ChronoUnit.MONTHS);
        } else
        if(dateUnit == DateUnit.DAY) {
            start = startDate;
            end = endDate;
        } else {
            throw new IllegalStateException();
        }
        int range = Math.min(MAXIMUM_LEAD, Ints.checkedCast(dateUnit.getTemporalUnit().between(start, end)));

        if (range < 0) {
            throw new IllegalArgumentException("startDate and endDate are invalid.");
        }

        String query;
        if(firstAction.isPresent()) {
            String timeSubtraction;
            if (dateUnit == DateUnit.DAY) {
                timeSubtraction = "returning_action.time - data.time";
            } else {
                timeSubtraction = "date_diff('"+dateUnit.name().toLowerCase()+"', data.time, returning_action.time)";
            }

            String firstActionQuery = format("%s group by 1, 2 %s",
                    generateQuery(project, firstAction.get().collection(), timeColumn, dimension,
                            firstAction.get().filter(), startDate, endDate),
                    dimension.isPresent() ? ",3" : "");

            String dimensionColumn = dimension.isPresent() ? "data.dimension" : timeTransformation;

            query = format("with first_action as (\n" +
                    "  %s\n" +
                    "), \n" +
                    "returning_action as (\n" +
                    "  %s\n" +
                    ") \n" +
                    "select %s, null as lead, count(distinct user) count from first_action data group by 1,2 union all\n" +
                    "select %s, %s, count(distinct data.user) \n" +
                    "from first_action data join returning_action on (data.user = returning_action.user) \n" +
                    "where data.time < returning_action.time and %s < %d group by 1, 2",
                    firstActionQuery, from.toString(), dimensionColumn,
                    dimensionColumn, timeSubtraction,
                    timeSubtraction, MAXIMUM_LEAD);
        } else {
            String timeSubtraction;
            if (dateUnit == DateUnit.DAY) {
                timeSubtraction = "lead%d-time";
            } else {
                timeSubtraction = "date_diff('"+dateUnit.name().toLowerCase()+"', time, lead%d)";
            }

            String leadTemplate = "lead(time, %d) over (partition by user order by user, time)";
            String leadColumns = IntStream.range(0, range)
                    .mapToObj(i -> "(" + format(timeSubtraction, i) + ") as lead" + i)
                    .collect(Collectors.joining(", "));
            String groups = IntStream.range(2, range+2).mapToObj(Integer::toString).collect(Collectors.joining(", "));

            String leads = IntStream.range(0, range)
                    .mapToObj(i -> format(leadTemplate+" lead"+i, i))
                    .collect(Collectors.joining(", "));
            String leadColumnNames = IntStream.range(0, range).mapToObj(i -> "lead" + i).collect(Collectors.joining(", "));

            query = format("with daily_groups as (\n" +
                            "  select user, time\n" +
                            "  from (%s) group by 1, 2\n" +
                            "), \n" +
                            "lead_relations as (\n" +
                            "  select user, time, %s\n" +
                            "  from daily_groups\n" +
                            "),\n" +
                            "result as (\n" +
                            "   select %s as time, %s, count(distinct user) as count\n" +
                            "   from lead_relations data group by 1, %s order by 1\n" +
                            ") \n" +
                            "select %s, null as lead, count(user) as count from daily_groups data group by 1\n" +
                            "union all (select * from (select time, lead, count from result \n" +
                            "CROSS JOIN unnest(array[%s]) t(lead)) where lead < %d)",
                    from.toString(), leads, timeTransformation,
                    leadColumns, groups, timeTransformation,
                    leadColumnNames, MAXIMUM_LEAD);
        }

        return executor.executeRawQuery(query);
    }

    private String generateQuery(String project,
                                 String collection,
                                 String timeColumn,
                                 Optional<String> dimension,
                                 Optional<Expression> exp,
                                 LocalDate startDate,
                                 LocalDate endDate) {
        ZoneId utc = ZoneId.of("UTC");

        long startTs = startDate.atStartOfDay().atZone(utc).toEpochSecond();
        long endTs = endDate.atStartOfDay().atZone(utc).toEpochSecond();

        return format("select user, %s as time %s from %s where time between %d and %d %s",
                timeColumn,
                dimension.isPresent() ? ", "+dimension.get()+" as dimension" : "",
                config.getColdStorageConnector() + "." + project +"."+collection,
                startTs, endTs,
                exp.isPresent() ? "and " + exp.get().accept(new ExpressionFormatter.Formatter(), false) : "");
    }
}