/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.dataql.fx.db;
import net.hasor.core.AppContext;
import net.hasor.dataql.FragmentProcess;
import net.hasor.dataql.Hints;
import net.hasor.dataql.fx.FxHintNames;
import net.hasor.dataql.fx.FxHintValue;
import net.hasor.dataql.fx.db.dialect.SqlPageDialect;
import net.hasor.dataql.fx.db.parser.FxSql;
import net.hasor.db.jdbc.BatchPreparedStatementSetter;
import net.hasor.db.jdbc.ConnectionCallback;
import net.hasor.db.jdbc.PreparedStatementSetter;
import net.hasor.db.jdbc.core.ArgPreparedStatementSetter;
import net.hasor.db.jdbc.core.JdbcTemplate;
import net.hasor.utils.StringUtils;
import net.hasor.utils.io.IOUtils;

import javax.inject.Inject;
import java.io.IOException;
import java.io.StringReader;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import static net.hasor.dataql.fx.FxHintNames.FRAGMENT_SQL_PAGE_DIALECT;
import static net.hasor.dataql.fx.FxHintValue.FRAGMENT_SQL_QUERY_BY_PAGE_ENABLE;

/**
 * 支持 SQL 的代码片段执行器。整合了分页、批处理能力。
 *  已支持的语句有：insert、update、delete、replace、select、create、drop、alter
 *  暂不支持语句有：exec、其它语句
 *  已经提供原生：insert、update、delete、replace 语句的批量能力。
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2020-03-28
 */
public class SqlFragment implements FragmentProcess {
    @Inject
    protected AppContext   appContext;
    @Inject
    protected JdbcTemplate jdbcTemplate;

    protected static enum SqlMode {
        /** DML：insert、update、delete、replace、exec */
        Insert, Update, Delete, Procedure,
        /** DML：select */
        Query,
        /** DDL：create、drop、alter */
        Create, Drop, Alter,
        /** Other */
        Unknown,
    }

    public List<Object> batchRunFragment(Hints hint, List<Map<String, Object>> params, String fragmentString) throws Throwable {
        // 如果批量参数为空或者只有一个时，自动退化为非批量
        if (params == null || params.size() == 0) {
            return Collections.singletonList(this.runFragment(hint, Collections.emptyMap(), fragmentString));
        }
        if (params.size() == 1) {
            return Collections.singletonList(this.runFragment(hint, params.get(0), fragmentString));
        }
        //
        SqlMode sqlMode = evalSqlMode(fragmentString);
        FxSql fxSql = FxSql.analysisSQL(fragmentString);
        if ((SqlMode.Insert == sqlMode || SqlMode.Update == sqlMode || SqlMode.Delete == sqlMode) && !fxSql.isHavePlaceholder()) {
            fragmentString = fxSql.buildSqlString(params.get(0));
            PreparedStatementSetter[] parameterArrays = new PreparedStatementSetter[params.size()];
            for (int i = 0; i < params.size(); i++) {
                parameterArrays[i] = new ArgPreparedStatementSetter(fxSql.buildParameterSource(params.get(i)).toArray());
            }
            //
            int[] executeBatch = this.jdbcTemplate.executeBatch(fragmentString, new BatchPreparedStatementSetter() {
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    parameterArrays[i].setValues(ps);
                }

                public int getBatchSize() {
                    return parameterArrays.length;
                }
            });
            return Arrays.stream(executeBatch).boxed().collect(Collectors.toList());
        } else {
            List<Object> resultList = new ArrayList<>(params.size());
            for (Map<String, Object> paramItem : params) {
                if (usePage(hint, sqlMode, fxSql)) {
                    resultList.add(this.usePageFragment(fxSql, sqlMode, hint, paramItem));
                } else {
                    resultList.add(this.noPageFragment(fxSql, sqlMode, hint, paramItem));
                }
            }
            return resultList;
        }
    }

    @Override
    public Object runFragment(Hints hint, Map<String, Object> paramMap, String fragmentString) throws Throwable {
        SqlMode sqlMode = evalSqlMode(fragmentString);
        FxSql fxSql = FxSql.analysisSQL(fragmentString);
        if (usePage(hint, sqlMode, fxSql)) {
            return this.usePageFragment(fxSql, sqlMode, hint, paramMap);
        } else {
            return this.noPageFragment(fxSql, sqlMode, hint, paramMap);
        }
    }

    protected Object usePageFragment(FxSql fxSql, SqlMode sqlMode, Hints hint, Map<String, Object> paramMap) {
        String sqlDialect = this.appContext.getEnvironment().getVariable("HASOR_DATAQL_FX_PAGE_DIALECT");
        sqlDialect = hint.getOrDefault(FRAGMENT_SQL_PAGE_DIALECT.name(), sqlDialect).toString();
        if (StringUtils.isBlank(sqlDialect)) {
            throw new IllegalArgumentException("Query dialect missing.");
        }
        SqlPageDialect pageDialect = SqlPageDialectRegister.findOrCreate(sqlDialect, this.appContext);
        //
        return new SqlPageObject(new SqlPageQuery() {
            @Override
            public SqlPageDialect.BoundSql getCountBoundSql() {
                return pageDialect.getCountSql(fxSql, paramMap);
            }

            @Override
            public SqlPageDialect.BoundSql getPageBoundSql(int start, int limit) {
                if (limit < 0) {
                    String sqlString = fxSql.buildSqlString(paramMap);
                    Object[] paramArrays = fxSql.buildParameterSource(paramMap).toArray();
                    return new SqlPageDialect.BoundSql(sqlString, paramArrays);
                }
                return pageDialect.getPageSql(fxSql, paramMap, start, limit);
            }

            @Override
            public <T> T doQuery(ConnectionCallback<T> connectionCallback) throws SQLException {
                return jdbcTemplate.execute(connectionCallback);
            }
        });
    }

    protected Object noPageFragment(FxSql fxSql, SqlMode sqlMode, Hints hint, Map<String, Object> paramMap) throws Throwable {
        String fragmentString = fxSql.buildSqlString(paramMap);
        Object[] source = fxSql.buildParameterSource(paramMap).toArray();
        //
        if (SqlMode.Query == sqlMode) {
            List<Map<String, Object>> mapList = this.jdbcTemplate.queryForList(fragmentString, source);
            String openPackage = hint.getOrDefault(FxHintNames.FRAGMENT_SQL_OPEN_PACKAGE.name(), FxHintNames.FRAGMENT_SQL_OPEN_PACKAGE.getDefaultVal()).toString();
            //
            // .结果有多条记录,或者模式为 off，那么直接返回List
            boolean packageOff = FxHintValue.FRAGMENT_SQL_OPEN_PACKAGE_OFF.equalsIgnoreCase(openPackage);
            if (packageOff || (mapList != null && mapList.size() > 1)) {
                return mapList;
            }
            // .为空或者结果为空，那么看看是返回 null 或者 空对象
            if (mapList == null || mapList.isEmpty()) {
                if (FxHintValue.FRAGMENT_SQL_OPEN_PACKAGE_COLUMN.equalsIgnoreCase(openPackage)) {
                    return null;
                } else {
                    return Collections.emptyMap();
                }
            }
            // .只有1条记录
            Map<String, Object> rowObject = mapList.get(0);
            if (FxHintValue.FRAGMENT_SQL_OPEN_PACKAGE_COLUMN.equalsIgnoreCase(openPackage)) {
                if (rowObject == null) {
                    return null;
                }
                if (rowObject.size() == 1) {
                    Set<Map.Entry<String, Object>> entrySet = rowObject.entrySet();
                    Map.Entry<String, Object> objectEntry = entrySet.iterator().next();
                    return objectEntry.getValue();
                }
            }
            return rowObject;
            //
        } else if (SqlMode.Insert == sqlMode || SqlMode.Update == sqlMode || SqlMode.Delete == sqlMode) {
            return this.jdbcTemplate.executeUpdate(fragmentString, source);
        } else if (SqlMode.Procedure == sqlMode) {
            throw new SQLException("Procedure not support.");
        } else if (SqlMode.Create == sqlMode || SqlMode.Drop == sqlMode || SqlMode.Alter == sqlMode) {
            return this.jdbcTemplate.executeUpdate(fragmentString, source);
        }
        throw new SQLException("Unknown SqlMode.");//不可能走到这里
    }

    private boolean usePage(Hints hint, SqlMode sqlMode, FxSql fxSql) {
        if (SqlMode.Query == sqlMode) {
            FxHintNames queryByPage = FxHintNames.FRAGMENT_SQL_QUERY_BY_PAGE;
            Object hintOrDefault = hint.getOrDefault(queryByPage.name(), queryByPage.getDefaultVal());
            return FRAGMENT_SQL_QUERY_BY_PAGE_ENABLE.equalsIgnoreCase(hintOrDefault.toString());
        }
        return false;
    }

    private static SqlMode evalSqlMode(String fragmentString) throws SQLException, IOException {
        List<String> readLines = IOUtils.readLines(new StringReader(fragmentString));
        SqlMode sqlMode = null;
        boolean multipleLines = false;
        for (String lineStr : readLines) {
            String tempLine = lineStr.trim();
            if (!multipleLines) {
                // 空行
                if (StringUtils.isBlank(tempLine)) {
                    continue;
                }
                // 单行注释
                if (tempLine.startsWith("--") && tempLine.startsWith("#")) {
                    continue;
                }
                // 多行注释
                if (tempLine.startsWith("/*")) {
                    multipleLines = true;
                }
            }
            if (multipleLines) {
                if (tempLine.contains("*/")) {
                    tempLine = tempLine.substring(tempLine.indexOf("*/")).trim();
                    multipleLines = false;
                } else {
                    continue;
                }
            }
            //
            tempLine = tempLine.toLowerCase();
            if (tempLine.startsWith("insert") || tempLine.startsWith("replace")) {
                sqlMode = SqlMode.Insert;
            } else if (tempLine.startsWith("update")) {
                sqlMode = SqlMode.Update;
            } else if (tempLine.startsWith("delete")) {
                sqlMode = SqlMode.Delete;
            } else if (tempLine.startsWith("exec")) {
                sqlMode = SqlMode.Procedure;
            } else if (tempLine.startsWith("select")) {
                sqlMode = SqlMode.Query;
            } else if (tempLine.startsWith("create")) {
                sqlMode = SqlMode.Create;
            } else if (tempLine.startsWith("drop")) {
                sqlMode = SqlMode.Drop;
            } else if (tempLine.startsWith("alter")) {
                sqlMode = SqlMode.Alter;
            } else {
                sqlMode = SqlMode.Unknown;
            }
            break;
        }
        if (sqlMode == null) {
            throw new SQLException("Unknown query statement. -> " + fragmentString);
        }
        return sqlMode;
    }
}