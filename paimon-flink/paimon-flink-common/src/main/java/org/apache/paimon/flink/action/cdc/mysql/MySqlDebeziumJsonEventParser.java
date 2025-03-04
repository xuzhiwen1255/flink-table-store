/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.action.cdc.mysql;

import org.apache.paimon.flink.sink.cdc.CdcRecord;
import org.apache.paimon.flink.sink.cdc.EventParser;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.RowKind;
import org.apache.paimon.utils.DateTimeUtils;
import org.apache.paimon.utils.Preconditions;

import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.core.type.TypeReference;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.connect.json.JsonConverterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link EventParser} for MySQL Debezium JSON.
 *
 * <p>Some implementation is referenced from <a
 * href="https://github.com/apache/doris-flink-connector/blob/master/flink-doris-connector/src/main/java/org/apache/doris/flink/sink/writer/JsonDebeziumSchemaSerializer.java">apache
 * / doris-flink-connector</a>.
 */
public class MySqlDebeziumJsonEventParser implements EventParser<String> {

    private static final Logger LOG = LoggerFactory.getLogger(MySqlDebeziumJsonEventParser.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ZoneId serverTimeZone;
    private final boolean caseSensitive;

    private JsonNode payload;
    private Map<String, String> mySqlFieldTypes;
    private Map<String, String> fieldClassNames;

    public MySqlDebeziumJsonEventParser(ZoneId serverTimeZone, boolean caseSensitive) {
        this.serverTimeZone = serverTimeZone;
        this.caseSensitive = caseSensitive;
    }

    @Override
    public void setRawEvent(String rawEvent) {
        try {
            JsonNode root = objectMapper.readValue(rawEvent, JsonNode.class);
            JsonNode schema =
                    Preconditions.checkNotNull(
                            root.get("schema"),
                            "MySqlDebeziumJsonEventParser only supports debezium JSON with schema. "
                                    + "Please make sure that `includeSchema` is true "
                                    + "in the JsonDebeziumDeserializationSchema you created");
            payload = root.get("payload");

            if (!isUpdatedDataFields()) {
                updateFieldTypes(schema);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String tableName() {
        String tableName = payload.get("source").get("table").asText();
        return caseSensitive ? tableName : tableName.toLowerCase();
    }

    private void updateFieldTypes(JsonNode schema) {
        mySqlFieldTypes = new HashMap<>();
        fieldClassNames = new HashMap<>();
        JsonNode arrayNode = schema.get("fields");
        for (int i = 0; i < arrayNode.size(); i++) {
            JsonNode elementNode = arrayNode.get(i);
            String field = elementNode.get("field").asText();
            if ("before".equals(field) || "after".equals(field)) {
                JsonNode innerArrayNode = elementNode.get("fields");
                for (int j = 0; j < innerArrayNode.size(); j++) {
                    JsonNode innerElementNode = innerArrayNode.get(j);
                    String fieldName = innerElementNode.get("field").asText();
                    String fieldType = innerElementNode.get("type").asText();
                    mySqlFieldTypes.put(fieldName, fieldType);
                    if (innerElementNode.get("name") != null) {
                        String className = innerElementNode.get("name").asText();
                        fieldClassNames.put(fieldName, className);
                    }
                }
            }
        }
    }

    @Override
    public boolean isUpdatedDataFields() {
        return payload.get("op") == null;
    }

    @Override
    public Optional<List<DataField>> getUpdatedDataFields() {
        JsonNode historyRecord = payload.get("historyRecord");
        if (historyRecord == null) {
            return Optional.empty();
        }

        JsonNode columns;
        try {
            String historyRecordString = historyRecord.asText();
            JsonNode tableChanges = objectMapper.readTree(historyRecordString).get("tableChanges");
            if (tableChanges.size() != 1) {
                throw new IllegalArgumentException(
                        "Invalid historyRecord, because tableChanges should contain exactly 1 item.\n"
                                + historyRecordString);
            }
            columns = tableChanges.get(0).get("table").get("columns");
        } catch (Exception e) {
            LOG.info("Failed to parse history record for schema changes", e);
            return Optional.empty();
        }
        if (columns == null) {
            return Optional.empty();
        }

        List<DataField> result = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            JsonNode column = columns.get(i);
            JsonNode length = column.get("length");
            JsonNode scale = column.get("scale");
            DataType type =
                    MySqlTypeUtils.toDataType(
                            column.get("typeName").asText(),
                            length == null ? null : length.asInt(),
                            scale == null ? null : scale.asInt());
            if (column.get("optional").asBoolean()) {
                type = type.nullable();
            } else {
                type = type.notNull();
            }

            String fieldName = column.get("name").asText();
            result.add(new DataField(i, caseSensitive ? fieldName : fieldName.toLowerCase(), type));
        }
        return Optional.of(result);
    }

    @Override
    public List<CdcRecord> getRecords() {
        List<CdcRecord> records = new ArrayList<>();

        Map<String, String> before = extractRow(payload.get("before"));
        if (before.size() > 0) {
            before = caseSensitive ? before : keyCaseInsensitive(before);
            records.add(new CdcRecord(RowKind.DELETE, before));
        }

        Map<String, String> after = extractRow(payload.get("after"));
        if (after.size() > 0) {
            after = caseSensitive ? after : keyCaseInsensitive(after);
            records.add(new CdcRecord(RowKind.INSERT, after));
        }

        return records;
    }

    private Map<String, String> extractRow(JsonNode recordRow) {
        Map<String, String> recordMap =
                objectMapper.convertValue(recordRow, new TypeReference<Map<String, String>>() {});
        if (recordMap == null) {
            return new HashMap<>();
        }

        for (Map.Entry<String, String> field : mySqlFieldTypes.entrySet()) {
            String fieldName = field.getKey();
            String mySqlType = field.getValue();
            if (recordMap.containsKey(fieldName)) {
                String className = fieldClassNames.get(fieldName);
                String oldValue = recordMap.get(fieldName);
                String newValue = oldValue;

                if (newValue == null) {
                    continue;
                }

                // pay attention to the temporal types
                // https://debezium.io/documentation/reference/stable/connectors/mysql.html#mysql-temporal-types
                if ("bytes".equals(mySqlType) && className == null) {
                    // MySQL binary, varbinary, blob
                    newValue = new String(Base64.getDecoder().decode(oldValue));
                } else if ("bytes".equals(mySqlType)
                        && "org.apache.kafka.connect.data.Decimal".equals(className)) {
                    // MySQL numeric, fixed, decimal
                    try {
                        new BigDecimal(oldValue);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "Invalid big decimal value "
                                        + oldValue
                                        + ". Make sure that in the `customConverterConfigs` "
                                        + "of the JsonDebeziumDeserializationSchema you created, set '"
                                        + JsonConverterConfig.DECIMAL_FORMAT_CONFIG
                                        + "' to 'numeric'",
                                e);
                    }
                } else if ("io.debezium.time.Date".equals(className)) {
                    // MySQL date
                    newValue = DateTimeUtils.toLocalDate(Integer.parseInt(oldValue)).toString();
                } else if ("io.debezium.time.Timestamp".equals(className)) {
                    // MySQL datetime (precision 0-3)

                    // display value of datetime is not affected by timezone, see
                    // https://dev.mysql.com/doc/refman/8.0/en/datetime.html for standard, and
                    // RowDataDebeziumDeserializeSchema#convertToTimestamp in flink-cdc-connector
                    // for implementation
                    LocalDateTime localDateTime =
                            Instant.ofEpochMilli(Long.parseLong(oldValue))
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDateTime();
                    newValue = DateTimeUtils.formatLocalDateTime(localDateTime, 3);
                } else if ("io.debezium.time.MicroTimestamp".equals(className)) {
                    // MySQL datetime (precision 4-6)
                    long microseconds = Long.parseLong(oldValue);
                    long microsecondsPerSecond = 1_000_000;
                    long nanosecondsPerMicros = 1_000;
                    long seconds = microseconds / microsecondsPerSecond;
                    long nanoAdjustment =
                            (microseconds % microsecondsPerSecond) * nanosecondsPerMicros;

                    // display value of datetime is not affected by timezone, see
                    // https://dev.mysql.com/doc/refman/8.0/en/datetime.html for standard, and
                    // RowDataDebeziumDeserializeSchema#convertToTimestamp in flink-cdc-connector
                    // for implementation
                    LocalDateTime localDateTime =
                            Instant.ofEpochSecond(seconds, nanoAdjustment)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDateTime();
                    newValue = DateTimeUtils.formatLocalDateTime(localDateTime, 6);
                } else if ("io.debezium.time.ZonedTimestamp".equals(className)) {
                    // MySQL timestamp

                    // dispaly value of timestamp is affected by timezone, see
                    // https://dev.mysql.com/doc/refman/8.0/en/datetime.html for standard, and
                    // RowDataDebeziumDeserializeSchema#convertToTimestamp in flink-cdc-connector
                    // for implementation
                    newValue =
                            Instant.parse(oldValue)
                                    .atZone(serverTimeZone)
                                    .toLocalDateTime()
                                    .toString()
                                    .replace('T', ' ');
                }

                recordMap.put(fieldName, newValue);
            }
        }

        return recordMap;
    }

    private Map<String, String> keyCaseInsensitive(Map<String, String> origin) {
        Map<String, String> keyCaseInsensitive = new HashMap<>();
        for (Map.Entry<String, String> entry : origin.entrySet()) {
            String fieldName = entry.getKey().toLowerCase();
            if (keyCaseInsensitive.containsKey(fieldName)) {
                LOG.warn(
                        "Duplicate key appears when converting map keys to case-insensitive form. Original map is:\n{}",
                        origin);
            }
            keyCaseInsensitive.put(fieldName, entry.getValue());
        }
        return keyCaseInsensitive;
    }
}
